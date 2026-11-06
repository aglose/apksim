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
public class SimRunner extends RobolectricTestRunner {
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

  /**
   * Robolectric's helper runner, with the test method wrapped for failure bundles ({@link Bundles#wrap}): a failure is
   * captured there, before @After methods and rules tear the activity down. Unchanged when bundles are off.
   */
  @Override
  protected org.robolectric.internal.SandboxTestRunner.HelperTestRunner getHelperTestRunner(Class<?> bootstrapped)
      throws InitializationError {
    if (!Bundles.ON) return super.getHelperTestRunner(bootstrapped);
    return new HelperTestRunner(bootstrapped) {
      @Override
      protected org.junit.runners.model.Statement methodInvoker(FrameworkMethod method, Object test) {
        return bundled(method, super.methodInvoker(method, test));
      }
    };
  }

  /** A test method's statement with its failure bundle hook (Bundles.wrap); every helper runner here uses it. */
  static org.junit.runners.model.Statement bundled(FrameworkMethod method, org.junit.runners.model.Statement s) {
    org.junit.Test t = method.getAnnotation(org.junit.Test.class);
    return Bundles.wrap(s, t == null ? org.junit.Test.None.class : t.expected());
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
     * For a test run (Instrument), also sets the device's screen (Host does that itself) and hands the tests am
     * instrument's -e arguments, as InstrumentationRegistry does.
     */
    @Override
    public void setUpApplicationState(String testName, ConfigurationStrategy.Configuration config, AndroidManifest manifest) {
      Timing.detail("setup-app-state");
      TimeZones.applyLaunchZone();  // --timezone: the JDK's, android.icu's and user.timezone (Joda-Time)
      // --clock: before the Application and its startup providers run, so nothing they cache saw real time.
      SimClock.applyLaunchOption();
      RunnerApplication.noteManifest(manifest);  // before Robolectric puts the configured Application in its place
      // --network-profile offline: no network from the start, so Application.onCreate sees none (Host's and tests').
      NetConnectivity.beforeApplication();
      if (System.getProperty("apksim.instrument") == null) untracedDevice();
      super.setUpApplicationState(testName, withRunnerApplication(config), manifest);
      if (RealTimeClock.requested()) RealTimeClock.startTest();  // after: Looper needs Robolectric's configuration
      Timing.detail("setup-app-state-done");
      if (System.getProperty("apksim.instrument") == null) return;
      RuntimeEnvironment.setQualifiers(System.getProperty("apksim.qualifiers"));
      SystemBars.testSetUp();  // no system bars, as before, unless --status-bar/--nav-bar ask for them
      if (Boolean.getBoolean("apksim.logcat")) org.robolectric.shadows.ShadowLog.stream = System.err;
      // That replaced the log tee failure bundles read (AdbLog): put it back now, not at its watcher's next turn
      // (250 ms), which a short test can outrun.
      if (Bundles.ON) AdbLog.install();
      Bundle args = new Bundle();
      String prefix = "apksim.instrument.";
      for (String k : System.getProperties().stringPropertyNames()) {
        if (k.startsWith(prefix)) args.putString(k.substring(prefix.length()), System.getProperty(k));
      }
      // The arguments the suite's runner passed on, once its onCreate has run (TestRunnerSetup), as on a device.
      InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), TestRunnerSetup.arguments(args));
      initJniConstants();
      useTestApkContext();
      SimTestStorage.install();
      TestRunnerSetup.beforeFirstTest();
      TestRunnerSetup.LISTENERS.environmentReady();  // this test's start, now that its environment is set up
      try {
        Host.useDeviceGestureThresholds(RuntimeEnvironment.getApplication());  // as on a device, as Host does
      } catch (Exception e) {
        System.err.println("[apksim] gesture thresholds: " + e);
      }
    }

    /**
     * A running instance is a device nobody is tracing: android.os.Trace's enabled tags are 0, so every Trace call
     * returns at once. Robolectric's ShadowTrace acts as if a trace were always being captured (TRACE_TAG_APP), keeps
     * every section in static sets for the life of the process, and throws "Section is already running" for an async
     * section (name and cookie) begun twice, which a device never does: a large app's screen-load tracker ended its
     * process. Its reset between tests (the standby's warmup is one) puts the checks back on but leaves the tags, so
     * this runs before every setup. Test runs keep Robolectric's behaviour, which a test may assert on
     * (ShadowTrace.getCurrentAsyncSections()).
     */
    private static void untracedDevice() {
      org.robolectric.shadows.ShadowTrace.doNotUseSetCrashOnIncorrectUsage(false);
      org.robolectric.shadows.ShadowTrace.setEnabled(false);
      ReflectionHelpers.setStaticField(org.robolectric.shadows.ShadowTrace.class, "tags", 0L);
    }

    private static android.content.res.AssetManager testAssets;

    /**
     * InstrumentationRegistry.getInstrumentation().getContext() is the test APK's own on a device: its package and its
     * assets. Robolectric's is the app's, so a suite reading its JSON fixtures from the test APK's assets got
     * FileNotFoundException. A context over the test APK (as LiveInstrumentation gives an adb-started one), which test
     * code's Instrumentation.getContext() returns (MethodTranslator; RoboMonitoringInstrumentation's returns the app).
     */
    private static void useTestApkContext() {
      String apk = System.getProperty("apksim.testApk");
      if (apk == null) return;
      try {
        if (testAssets == null) testAssets = LiveInstrumentation.assets(java.nio.file.Path.of(apk));
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.Context ctx = new LiveInstrumentation.PackageContext(app, System.getProperty("apksim.testPackage"),
            app.getClassLoader(), testAssets);
        TestActivities.testApkContext = ctx;
      } catch (Exception e) {
        System.err.println("[apksim] test APK context: " + e);
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
    } else if (!TestActivities.testRun()) {
      shadows.add(ShadowRealSleep.class);  // running instances: SystemClock.sleep takes real time, not simulated
    }
    if (Snapshot.ON) shadows.add(Snapshot.ShadowSteadyCaret.class);  // --snapshot: carets that do not blink
    return shadows.toArray(new Class<?>[0]);
  }

  private static Class<?>[] baseShadows() {
    return new Class<?>[] {ShadowFastRipple.class, ShadowDeviceKeyCharacterMap.class, ShadowNoBiometrics.class, ShadowSimBiometricPrompt.class, ShadowSimUiAutomation.class, TestActivities.ShadowSimActivityClient.class, TestActivities.ShadowSimActivity.class, TestActivities.ShadowSimContextImpl.class, dev.apksim.gl.ShadowSimGLES20.class, dev.apksim.gl.ShadowSimGLES30.class, dev.apksim.gl.ShadowSimEGL14.class, TestActivities.ShadowSimDecorView.class, TestActivities.ShadowSimInstrumentation.class, dev.apksim.video.ShadowSimTextureView.class, dev.apksim.video.ShadowSimSurfaceView.class, dev.apksim.video.ShadowSimMediaCodec.class, dev.apksim.video.ShadowSimAudioTrack.class, LocalSockets.ShadowSimLocalServerSocket.class, LocalSockets.ShadowSimLocalSocket.class, LocalSockets.ShadowSimLinux.class, ShadowSimSSLSockets.class, ShadowSimViewRootImpl.class, ShadowSimWindowManagerImpl.class, dev.apksim.gpu.ShadowGpuRecordingCanvas.class, dev.apksim.gpu.ShadowGpuRecordingCanvas.Recording.class};
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
