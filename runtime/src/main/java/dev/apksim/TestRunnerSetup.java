package dev.apksim;

import android.app.ActivityThread;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.robolectric.RuntimeEnvironment;

/**
 * What a suite's instrumentation runner sets up before its tests, in a test run. apksim does not run the runner (the
 * test body runs under SimRunner), but a runner's onCreate often prepares the run: allure-kotlin's sets the Allure
 * lifecycle and adds its JUnit listener to the "listener" argument, then calls AndroidJUnitRunner.onCreate, which
 * starts the tests on a thread of its own. So, once per process at the first test's setup, the runner is created and
 * its onCreate runs with the run's arguments up to that super call (MethodTranslator sends it to {@link
 * #superOnCreate}, which keeps the arguments it was given instead). The listeners those arguments name, and any
 * `-e listener`, then hear the run through {@link #LISTENERS}, as AndroidJUnitRunner registers them, and the arguments
 * the runner passed on are the run's from then on ({@link #arguments}), as AndroidJUnitRunner registers them. A suite
 * whose every test is ignored reaches no test setup, so its runner and listeners are not set up.
 */
public final class TestRunnerSetup {
  private static final String ANDROID_JUNIT_RUNNER = "androidx.test.runner.AndroidJUnitRunner";

  /** Registered with JUnitCore by Instrument before the run; the runner's listeners join it at the first test. */
  static final Deferred LISTENERS = new Deferred();

  private static final ThreadLocal<Bundle[]> CAPTURE = new ThreadLocal<>();
  private static boolean done;
  private static volatile Bundle effective;

  private TestRunnerSetup() {}

  /**
   * AndroidJUnitRunner.onCreate as a subclass calls it (super.onCreate(arguments)). While apksim runs the runner's
   * onCreate, it records the arguments and returns; otherwise (an instrumentation started over adb) the real one runs.
   */
  public static void superOnCreate(Object self, Bundle arguments, Class<?> caller) throws Throwable {
    Bundle[] capture = CAPTURE.get();
    if (capture != null) {
      capture[0] = arguments;
      return;
    }
    Class<?> runner = Class.forName(ANDROID_JUNIT_RUNNER, false, caller.getClassLoader());
    MethodHandles.privateLookupIn(caller, MethodHandles.lookup())
        .findSpecial(runner, "onCreate", MethodType.methodType(void.class, Bundle.class), caller)
        .invoke(self, arguments);
  }

  /** The run's arguments for a test's setup: the ones the runner passed on, once its onCreate has run. */
  static Bundle arguments(Bundle fromLaunch) {
    Bundle e = effective;
    return e != null ? new Bundle(e) : fromLaunch;
  }

  /**
   * At the first test's setup in a process, in the test environment: the runner's onCreate, then its listeners. Not in
   * RunnerApplication's probe, which runs before the suite (and before Instrument names the runner).
   */
  static synchronized void beforeFirstTest() {
    if (done || !TestActivities.testRun() || RunnerApplication.probing) return;
    done = true;
    Bundle arguments = new Bundle(InstrumentationRegistry.getArguments());
    String runner = System.getProperty("apksim.runner");
    if (runner != null && !runner.equals(ANDROID_JUNIT_RUNNER)) {
      try {
        Class<?> c = Class.forName(runner, true, ClassLoader.getSystemClassLoader());
        Class<?> base = Class.forName(ANDROID_JUNIT_RUNNER, false, ClassLoader.getSystemClassLoader());
        // Only a runner's own onCreate: AndroidJUnitRunner's would start a run of its own.
        Method onCreate = c.getMethod("onCreate", Bundle.class);
        if (base.isAssignableFrom(c) && onCreate.getDeclaringClass() != base) {
          Instrumentation instance = (Instrumentation) c.getDeclaredConstructor().newInstance();
          init(instance, runner);
          Bundle[] capture = new Bundle[1];
          CAPTURE.set(capture);
          try {
            onCreate.invoke(instance, arguments);
          } finally {
            CAPTURE.remove();
          }
          if (capture[0] != null) {
            arguments = capture[0];
            effective = new Bundle(arguments);
            InstrumentationRegistry.registerInstance(InstrumentationRegistry.getInstrumentation(), new Bundle(arguments));
          }
        }
      } catch (InvocationTargetException e) {
        LISTENERS.fail(runner + ".onCreate", e.getCause());  // on a device the instrumentation crashes
      } catch (ReflectiveOperationException | LinkageError e) {
        LISTENERS.fail(runner, e);
      }
    }
    List<RunListener> listeners = new ArrayList<>();
    CharSequence names = arguments.getCharSequence("listener");
    if (names != null) {
      for (String name : names.toString().split(",")) {
        name = name.trim();
        if (name.isEmpty()) continue;
        try {
          listeners.add((RunListener) Class.forName(name, true, ClassLoader.getSystemClassLoader()).getDeclaredConstructor().newInstance());
        } catch (InvocationTargetException e) {
          LISTENERS.fail("listener " + name, e.getCause());
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
          LISTENERS.fail("listener " + name, e);
        }
      }
    }
    if (!listeners.isEmpty()) System.err.println("[apksim] listeners: " + names);
    LISTENERS.install(listeners);
  }

  /**
   * As ActivityThread does before a runner's onCreate: its own context (the test APK's) and the target's, so a runner
   * that reads getContext or getTargetContext there sees them.
   */
  private static void init(Instrumentation instance, String runner) throws ReflectiveOperationException {
    Context target = RuntimeEnvironment.getApplication();
    Context own = TestActivities.testApkContext != null ? TestActivities.testApkContext : target;
    String pkg = System.getProperty("apksim.testPackage", target.getPackageName());
    Method init = Instrumentation.class.getDeclaredMethod("init", ActivityThread.class, Context.class, Context.class,
        ComponentName.class, Class.forName("android.app.IInstrumentationWatcher"),
        Class.forName("android.app.IUiAutomationConnection"));
    init.setAccessible(true);
    init.invoke(instance, RuntimeEnvironment.getActivityThread(), own, target, new ComponentName(pkg, runner), null, null);
  }

  /** Forwards the run to listeners that join late, replaying the run's start and the current test's start to them. */
  static final class Deferred extends RunListener {
    private final List<RunListener> active = new CopyOnWriteArrayList<>();
    /**
     * Listeners that threw (as JUnit does, each stops hearing the run) and setup failures: either fails the run
     * (Instrument's exit status).
     */
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final List<Description> ignoredBefore = new ArrayList<>();
    private final java.util.ArrayDeque<Description> openSuites = new java.util.ArrayDeque<>();
    private boolean installed;
    private Description run;
    private Description current;
    /** A test's start, held until its environment is set up (environmentReady): JUnit reports it before that. */
    private Description pendingStart;

    synchronized void install(List<RunListener> listeners) {
      installed = true;
      for (RunListener l : listeners) {
        active.add(l);
        safely(l, () -> {
          withInstrumentation(l);
          if (run != null) l.testRunStarted(run);
          for (java.util.Iterator<Description> i = openSuites.descendingIterator(); i.hasNext(); ) {
            l.testSuiteStarted(i.next());  // outermost first
          }
          for (Description d : ignoredBefore) l.testIgnored(d);
          if (current != null && pendingStart == null) l.testStarted(current);  // a held start goes out at environmentReady
        });
      }
      ignoredBefore.clear();
    }

    private interface Call {
      void run() throws Exception;
    }

    /**
     * Once a test's environment is set up (SimRunner, after the instrumentation and the test APK's context): its held
     * start goes to the listeners, so an InstrumentationRunListener reading its context there sees this test's.
     */
    synchronized void environmentReady() {
      Description d = pendingStart;
      if (d == null) return;
      pendingStart = null;
      each(l -> l.testStarted(d));
    }

    /** A held start goes out before anything else about its test, if the test ends before its environment is ready. */
    private void flushStart() {
      if (pendingStart != null) environmentReady();
    }

    private void each(ListenerCall c) {
      for (RunListener l : active) {
        safely(l, () -> {
          withInstrumentation(l);
          c.run(l);
        });
      }
    }

    /** A setup failure (the runner's, or a listener that could not be made): the run fails, as on a device. */
    void fail(String what, Throwable e) {
      failures.add(e);
      System.err.println("[apksim] " + what + " failed: " + e);
      e.printStackTrace();
    }

    /**
     * AndroidJUnitRunner gives each InstrumentationRunListener the instrumentation (TestExecutor); here it is given
     * before every call, since Robolectric makes a new one for each test.
     */
    private static void withInstrumentation(RunListener l) throws ReflectiveOperationException {
      for (Class<?> c = l.getClass(); c != null && c != RunListener.class; c = c.getSuperclass()) {
        if (!"androidx.test.internal.runner.listener.InstrumentationRunListener".equals(c.getName())) continue;
        Method set = c.getMethod("setInstrumentation", Instrumentation.class);
        set.invoke(l, InstrumentationRegistry.getInstrumentation());
        return;
      }
    }

    private interface ListenerCall {
      void run(RunListener l) throws Exception;
    }

    private void safely(RunListener l, Call c) {
      try {
        c.run();
      } catch (Exception | LinkageError e) {
        active.remove(l);
        failures.add(e);
        System.err.println("[apksim] listener " + l.getClass().getName() + " failed and stops hearing the run: " + e);
        e.printStackTrace();
      }
    }

    @Override
    public synchronized void testRunStarted(Description d) {
      run = d;
      each(l -> l.testRunStarted(d));
    }

    @Override
    public synchronized void testRunFinished(Result r) {
      flushStart();
      SimTestStorage.ensureInstalled();
      each(l -> l.testRunFinished(r));
    }

    @Override
    public synchronized void testSuiteStarted(Description d) {
      openSuites.push(d);
      each(l -> l.testSuiteStarted(d));
    }

    @Override
    public synchronized void testSuiteFinished(Description d) {
      openSuites.remove(d);
      each(l -> l.testSuiteFinished(d));
    }

    @Override
    public synchronized void testStarted(Description d) {
      flushStart();
      current = d;
      pendingStart = d;
    }

    @Override
    public synchronized void testFinished(Description d) {
      flushStart();
      SimTestStorage.ensureInstalled();
      each(l -> l.testFinished(d));
      current = null;
    }

    @Override
    public synchronized void testFailure(Failure f) {
      flushStart();
      SimTestStorage.ensureInstalled();
      each(l -> l.testFailure(f));
    }

    @Override
    public synchronized void testAssumptionFailure(Failure f) {
      flushStart();
      SimTestStorage.ensureInstalled();
      each(l -> l.testAssumptionFailure(f));
    }

    @Override
    public synchronized void testIgnored(Description d) {
      if (!installed) {
        ignoredBefore.add(d);  // before the first test's setup: replayed when the listeners join
        return;
      }
      SimTestStorage.ensureInstalled();
      each(l -> l.testIgnored(d));
    }
  }
}
