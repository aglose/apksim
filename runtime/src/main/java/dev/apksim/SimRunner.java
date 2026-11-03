package dev.apksim;

import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ResourcesMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.internal.AndroidSandbox;
import org.robolectric.internal.ManifestFactory;
import org.robolectric.internal.ManifestIdentifier;
import org.robolectric.internal.SandboxManager;
import org.robolectric.internal.bytecode.ClassInstrumentor;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.ShadowProviders;
import org.robolectric.pluginapi.Sdk;

/** Points Robolectric's framework bootstrap at the unmodified APK instead of a test project. */
public final class SimRunner extends RobolectricTestRunner {
  public SimRunner(Class<?> c) throws InitializationError {
    super(c, defaultInjector().bind(SandboxManager.SandboxBuilder.class, FlatSandboxBuilder.class).build());
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
      return new AndroidSandbox(runtimeSdk, compileSdk, resourcesMode, new AndroidSandbox.TestEnvironmentSpec(),
          new FlatLoader(config, runtimeSdk, instrumentor), shadowProviders, sqliteMode);
    }
  }

  @Override
  protected Class<?>[] getExtraShadows(FrameworkMethod method) {
    return new Class<?>[] {ShadowFastRipple.class, ShadowDeviceKeyCharacterMap.class, ShadowNoBiometrics.class, ShadowSimBiometricPrompt.class, dev.apksim.video.ShadowSimTextureView.class, dev.apksim.video.ShadowSimMediaCodec.class};
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
