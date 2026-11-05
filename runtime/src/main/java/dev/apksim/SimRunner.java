package dev.apksim;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
import org.robolectric.internal.bytecode.Interceptor;
import org.robolectric.internal.bytecode.MethodSignature;
import org.robolectric.internal.bytecode.ShadowProviders;
import org.robolectric.interceptors.AndroidInterceptors;
import org.robolectric.pluginapi.Sdk;
import org.robolectric.pluginapi.SdkPicker;
import org.robolectric.pluginapi.UsesSdk;
import org.robolectric.plugins.SdkCollection;
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
  private static final Injector INJECTOR = defaultInjector().bind(SandboxManager.SandboxBuilder.class, FlatSandboxBuilder.class)
      .bind(SdkPicker.class, OneSdk.class).build();

  /**
   * Every test runs on the one framework this runtime ships (API 36), whatever the APK targets. Robolectric's picker
   * takes the manifest's targetSdk when a class has no @Config(sdk): an app targeting 37, which is not enabled, had its
   * test classes left with no tests to run (Host itself carries @Config(sdk = 36)).
   */
  public static final class OneSdk implements SdkPicker {
    private final SdkCollection sdks;

    @Inject
    public OneSdk(SdkCollection sdks) {
      this.sdks = sdks;
    }

    @Override
    public List<Sdk> selectSdks(ConfigurationStrategy.Configuration configuration, UsesSdk usesSdk) {
      return List.of(sdks.getSdk(36));
    }
  }

  public SimRunner(Class<?> c) throws InitializationError {
    super(c, INJECTOR);
  }

  /** Robolectric's interceptors, with the framework's wall clock replaced by the real one ({@link WallClock}). */
  @Override
  protected Collection<Interceptor> findInterceptors() {
    List<Interceptor> all = new ArrayList<>();
    for (Interceptor i : super.findInterceptors()) {
      all.add(i instanceof AndroidInterceptors.SystemTimeInterceptor ? new WallClock() : i);
    }
    return all;
  }

  /**
   * System.currentTimeMillis in framework code is the real wall clock, as it already is in the app's own code and the
   * JDK's (Instant.now, Date, java.time). Robolectric points it at its simulated uptime, which starts 100 s after 1970,
   * so the framework thought it was January 1970 while the app knew the date: DateUtils.isToday(System
   * .currentTimeMillis()) in an app was false, android.icu.util.Calendar.getInstance() was in 1970, relative time spans
   * were 50 years long. The uptime clock (SystemClock, nanoTime, the loopers) stays Robolectric's: as on a device the
   * two are separate clocks, though here the wall clock does not jump when the simulated one skips idle time. It is
   * SimClock's, so `clock ISO` (screenshot tests) moves the framework's wall clock with the app's.
   * Public: Robolectric makes its own instance by reflection when it links a call site.
   */
  public static final class WallClock extends AndroidInterceptors.SystemTimeInterceptor {
    public WallClock() {}

    @Override
    public org.robolectric.util.Function<Object, Object> handle(MethodSignature signature) {
      if ("currentTimeMillis".equals(signature.methodName)) return (theClass, value, params) -> SimClock.currentTimeMillis();
      return super.handle(signature);
    }

    @Override
    public MethodHandle getMethodHandle(String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
      if ("currentTimeMillis".equals(name)) {
        return MethodHandles.lookup().findStatic(SimClock.class, "currentTimeMillis", MethodType.methodType(long.class));
      }
      return super.getMethodHandle(name, type);
    }
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
      Timing.detail("sandbox-build");
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
     * The test's configuration, with the Application the test APK's runner asks for in newApplication
     * (RunnerApplication) when it is not the manifest's. Set here, where Robolectric makes the Application: its global
     * configuration is fixed when the first runner is made, before the probe runs.
     */
    private static ConfigurationStrategy.Configuration withRunnerApplication(ConfigurationStrategy.Configuration c) {
      Class<? extends android.app.Application> chosen = RunnerApplication.chosen;
      Config base = c.get(Config.class);
      if (chosen == null || base == null || !Config.Builder.isDefaultApplication(base.application())) return c;
      Config changed = new Config.Builder(base).setApplication(chosen).build();
      java.util.Map<Class<?>, Object> map = new java.util.HashMap<>(c.map());
      map.put(Config.class, changed);
      return new ConfigurationStrategy.Configuration() {
        @Override
        public <T> T get(Class<T> key) {
          return key.cast(map.get(key));
        }

        @Override
        public java.util.Collection<Class<?>> keySet() {
          return map.keySet();
        }

        @Override
        public java.util.Map<Class<?>, Object> map() {
          return map;
        }
      };
    }

    /**
     * The device's time zone, `--timezone ZONE` (-Dapksim.timezone): the JDK's default and android.icu's. ICU keeps
     * the zone it first worked out, and a standby may have formatted a date before the launch named one, so its cached
     * zone is dropped too and taken again from the JDK's. Here, inside the sandbox, rather than in Main: ICU's classes
     * are instrumented framework classes.
     */
    private static void applyTimeZone() {
      String zone = System.getProperty("apksim.timezone");
      if (zone == null) return;
      // ZoneId.of rejects an unknown name, which TimeZone.getTimeZone would quietly turn into GMT.
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneId.of(zone)));
      android.icu.util.TimeZone.setICUDefault(null);
    }

    /**
     * For a test run (Instrument), also sets the device's screen (Host does that itself) and hands the tests am
     * instrument's -e arguments, as InstrumentationRegistry does.
     */
    @Override
    public void setUpApplicationState(String testName, ConfigurationStrategy.Configuration config, AndroidManifest manifest) {
      Timing.detail("setup-app-state");
      applyTimeZone();
      RunnerApplication.noteManifest(manifest);  // before Robolectric puts the configured Application in its place
      super.setUpApplicationState(testName, withRunnerApplication(config), manifest);
      if (RealTimeClock.requested()) RealTimeClock.startTest();  // after: Looper needs Robolectric's configuration
      Timing.detail("setup-app-state-done");
      if (System.getProperty("apksim.instrument") == null) return;
      RuntimeEnvironment.setQualifiers(System.getProperty("apksim.qualifiers"));
      if (Boolean.getBoolean("apksim.logcat")) org.robolectric.shadows.ShadowLog.stream = System.err;
      Bundle args = new Bundle();
      String prefix = "apksim.instrument.";
      for (String k : System.getProperties().stringPropertyNames()) {
        if (k.startsWith(prefix)) args.putString(k.substring(prefix.length()), System.getProperty(k));
      }
      InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), args);
      initJniConstants();
      try {
        Host.useDeviceGestureThresholds(RuntimeEnvironment.getApplication());  // as on a device, as Host does
      } catch (Exception e) {
        System.err.println("[apksim] gesture thresholds: " + e);
      }
    }

    private static boolean jniConstantsReady;

    /**
     * The framework's natives cache java.io/java.nio classes (JniConstants) on first use, in a pthread_once. Run from
     * a coroutine worker (ImageDecoder.nCreateFd under Coil, the first use in one app's test run), the lookup failed
     * and the process aborted ("Class not found: java/nio/ShortBuffer"); from the main thread it works. Host meets
     * it on the main thread first; a test run does it here, before any test starts threads.
     */
    private static void initJniConstants() {
      if (jniConstantsReady) return;
      jniConstantsReady = true;
      try {
        java.io.File f = java.io.File.createTempFile("jniconstants", ".bin");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
          android.graphics.BitmapFactory.decodeFileDescriptor(in.getFD());  // empty file: null, after the cache is filled
        } finally {
          f.delete();
        }
      } catch (java.io.IOException e) {
        System.err.println("[apksim] JniConstants warm-up failed: " + e);
      }
    }

    /** As AndroidTestEnvironment.resetState, but with a tracker this process can answer. */
    @Override
    public void resetState() {
      RealTimeClock.endTest();
      if (System.getProperty("apksim.instrument") != null) TestActivities.beforeReset();
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
    List<Class<?>> shadows = new ArrayList<>(java.util.Arrays.asList(baseShadows()));
    if (RealTimeClock.requested()) {  // --realtime-clock
      shadows.add(RealTimeClock.ShadowRealTimeSystemClock.class);
      shadows.add(RealTimeClock.ShadowRealTimeDisplayEventReceiver.class);
    }
    if (Snapshot.ON) shadows.add(Snapshot.ShadowSteadyCaret.class);  // --snapshot: carets that do not blink
    return shadows.toArray(new Class<?>[0]);
  }

  private static Class<?>[] baseShadows() {
    return new Class<?>[] {ShadowFastRipple.class, ShadowDeviceKeyCharacterMap.class, ShadowNoBiometrics.class, ShadowSimBiometricPrompt.class, ShadowSimUiAutomation.class, TestActivities.ShadowSimActivityClient.class, TestActivities.ShadowSimActivity.class, TestActivities.ShadowSimContextImpl.class, dev.apksim.gl.ShadowSimGLES20.class, dev.apksim.gl.ShadowSimGLES30.class, dev.apksim.gl.ShadowSimEGL14.class, TestActivities.ShadowSimDecorView.class, TestActivities.ShadowSimInstrumentation.class, dev.apksim.video.ShadowSimTextureView.class, dev.apksim.video.ShadowSimMediaCodec.class, dev.apksim.video.ShadowSimAudioTrack.class, LocalSockets.ShadowSimLocalServerSocket.class, LocalSockets.ShadowSimLocalSocket.class, LocalSockets.ShadowSimLinux.class};
  }

  @Override
  protected Config buildGlobalConfig() {
    return new Config.Builder().setSdk(36).build();
  }

  @Override
  protected ManifestFactory getManifestFactory(Config config) {
    if (System.getProperty("apksim.apk") == null) {
      // A standby's warm-up (StandbyWarmup): no APK yet, so Robolectric's default application.
      return c -> new ManifestIdentifier(null, null, null, null, List.of(), null);
    }
    Path cache = Path.of(System.getProperty("apksim.cache"));
    Path apk = Path.of(System.getProperty("apksim.apk"));
    String pkg = System.getProperty("apksim.package");
    return c -> new ManifestIdentifier(pkg, cache.resolve("AndroidManifest.xml"), null, null, List.of(), apk);
  }
}
