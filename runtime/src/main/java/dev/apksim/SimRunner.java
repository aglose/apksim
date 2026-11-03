package dev.apksim;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Named;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ResourcesMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.android.internal.AndroidTestEnvironment;
import org.robolectric.fakes.FakeMediaProvider;
import org.robolectric.internal.AndroidSandbox;
import org.robolectric.internal.ManifestFactory;
import org.robolectric.internal.ManifestIdentifier;
import org.robolectric.internal.ClassTracker;
import org.robolectric.internal.SandboxManager;
import org.robolectric.internal.ShadowProvider;
import org.robolectric.internal.bytecode.ClassInstrumentor;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.ShadowProviders;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;
import org.robolectric.pluginapi.config.ConfigurationStrategy;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.inject.Injector;

/** Points Robolectric's framework bootstrap at the unmodified APK instead of a test project. */
public final class SimRunner extends RobolectricTestRunner {
  /**
   * One injector, so one SandboxManager and one sandbox, for every class run here. The framework is loaded once per
   * process ({@link FlatLoader}), so a second sandbox would bring a second "main thread" to a main Looper already
   * bound to the first.
   */
  private static final Injector INJECTOR = defaultInjector().bind(SandboxManager.SandboxBuilder.class, FlatSandboxBuilder.class).build();

  public SimRunner(Class<?> c) throws InitializationError {
    super(c, INJECTOR);
  }

  /**
   * Robolectric normally loads the framework through its own instrumenting loader, which the JVM
   * cannot archive. The framework jar is already instrumented, so everything can load straight from
   * the application class loader, which makes it eligible for class data sharing.
   */
  static final class FlatLoader extends AndroidSandbox.SdkSandboxClassLoader {
    FlatLoader(InstrumentationConfiguration config, Sdk sdk, ClassInstrumentor instrumentor) {
      super(config, sdk, instrumentor);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      return ClassLoader.getSystemClassLoader().loadClass(name);
    }
  }

  /** Builds the one sandbox this process needs, around {@link FlatLoader}. */
  public static final class FlatSandboxBuilder implements SandboxManager.SandboxBuilder {
    private final ClassInstrumentor instrumentor;
    private final ShadowProviders shadowProviders;

    @Inject
    public FlatSandboxBuilder(ClassInstrumentor instrumentor, ShadowProviders shadowProviders) {
      this.instrumentor = instrumentor;
      this.shadowProviders = shadowProviders;
    }

    @Override
    public AndroidSandbox build(InstrumentationConfiguration config, Sdk runtimeSdk, Sdk compileSdk,
        ResourcesMode.Mode resourcesMode, SQLiteMode.Mode sqliteMode) {
      return new AndroidSandbox(runtimeSdk, compileSdk, resourcesMode, new AndroidSandbox.TestEnvironmentSpec(SimTestEnvironment.class),
          new FlatLoader(config, runtimeSdk, instrumentor), shadowProviders, sqliteMode);
    }
  }

  /**
   * Robolectric's environment, with a reset between tests that works under {@link FlatLoader}. Its own reset asks the
   * framework's class loader which classes are loaded, as a {@link ClassTracker}, which the JVM's loader is not, so the
   * second test of a run (Instrument) found the first one's state. Host runs one test and never resets.
   */
  public static final class SimTestEnvironment extends AndroidTestEnvironment {
    private static final Method FIND_LOADED;

    static {
      try {
        FIND_LOADED = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        FIND_LOADED.setAccessible(true);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final ShadowProvider[] providers;

    public SimTestEnvironment(@Named("runtimeSdk") Sdk runtimeSdk, @Named("compileSdk") Sdk compileSdk, ShadowProvider[] providers,
        TestEnvironmentLifecyclePlugin[] plugins) {
      super(runtimeSdk, compileSdk, providers, plugins);
      this.providers = providers;
    }

    /**
     * For a test run (Instrument), also sets the device's screen (Host does that itself) and hands the tests am
     * instrument's -e arguments, as InstrumentationRegistry does.
     */
    @Override
    public void setUpApplicationState(String testName, ConfigurationStrategy.Configuration config, AndroidManifest manifest) {
      super.setUpApplicationState(testName, config, manifest);
      if (System.getProperty("apksim.instrument") == null) return;
      RuntimeEnvironment.setQualifiers(System.getProperty("apksim.qualifiers"));
      if (Boolean.getBoolean("apksim.logcat")) org.robolectric.shadows.ShadowLog.stream = System.err;
      Bundle args = new Bundle();
      String prefix = "apksim.instrument.";
      for (String k : System.getProperties().stringPropertyNames()) {
        if (k.startsWith(prefix)) args.putString(k.substring(prefix.length()), System.getProperty(k));
      }
      InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), args);
    }

    /** As AndroidTestEnvironment.resetState, but with a tracker this process can answer. */
    @Override
    public void resetState() {
      Locale.setDefault(ReflectionHelpers.getField(this, "initialLocale"));
      FakeMediaProvider media = ReflectionHelpers.getField(this, "fakeMediaProvider");
      if (media != null) {
        media.shutdown();
        ReflectionHelpers.setField(AndroidTestEnvironment.class, this, "fakeMediaProvider", null);
      }
      ClassLoader loader = ClassLoader.getSystemClassLoader();
      ClassTracker loaded = name -> {
        try {
          return FIND_LOADED.invoke(loader, name) != null;
        } catch (ReflectiveOperationException e) {
          return true;
        }
      };
      RuntimeException failed = null;
      for (ShadowProvider p : providers) {
        try {
          p.reset(loaded);
        } catch (RuntimeException e) {
          if (failed == null) failed = e; else failed.addSuppressed(e);
        }
      }
      if (failed != null) throw failed;
    }
  }

  @Override
  protected Class<?>[] getExtraShadows(FrameworkMethod method) {
    return new Class<?>[] {ShadowFastRipple.class, ShadowDeviceKeyCharacterMap.class, ShadowNoBiometrics.class, ShadowSimBiometricPrompt.class, dev.apksim.video.ShadowSimTextureView.class, dev.apksim.video.ShadowSimMediaCodec.class, dev.apksim.video.ShadowSimAudioTrack.class};
  }

  @Override
  protected Config buildGlobalConfig() {
    return new Config.Builder().setSdk(36).build();
  }

  @Override
  protected ManifestFactory getManifestFactory(Config config) {
    Path cache = Path.of(System.getProperty("apksim.cache"));
    Path apk = Path.of(System.getProperty("apksim.apk"));
    String pkg = System.getProperty("apksim.package");
    return c -> new ManifestIdentifier(pkg, cache.resolve("AndroidManifest.xml"), null, null, List.of(), apk);
  }
}
