package dev.apksim;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * What a suite's instrumentation runner sets up before its tests, in a test run. apksim does not run the runner (the
 * test body runs under SimRunner), but a runner's onCreate often prepares the run: allure-kotlin's sets the Allure
 * lifecycle and adds its JUnit listener to the "listener" argument, then calls AndroidJUnitRunner.onCreate, which
 * starts the tests on a thread of its own. So, once per process at the first test's setup, the runner is created and
 * its onCreate runs with the run's arguments up to that super call (MethodTranslator sends it to {@link
 * #superOnCreate}, which keeps the arguments it was given instead). The listeners those arguments name, and any
 * `-e listener`, then hear the run through {@link #LISTENERS}, as AndroidJUnitRunner registers them.
 */
public final class TestRunnerSetup {
  private static final String ANDROID_JUNIT_RUNNER = "androidx.test.runner.AndroidJUnitRunner";

  /** Registered with JUnitCore by Instrument before the run; the runner's listeners join it at the first test. */
  static final Deferred LISTENERS = new Deferred();

  private static final ThreadLocal<Bundle[]> CAPTURE = new ThreadLocal<>();
  private static boolean done;

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
        if (base.isAssignableFrom(c)) {
          Object instance = c.getDeclaredConstructor().newInstance();
          Bundle[] capture = new Bundle[1];
          CAPTURE.set(capture);
          try {
            c.getMethod("onCreate", Bundle.class).invoke(instance, arguments);
          } finally {
            CAPTURE.remove();
          }
          if (capture[0] != null) arguments = capture[0];
        }
      } catch (InvocationTargetException e) {
        System.err.println("[apksim] " + runner + ".onCreate: " + e.getCause());
      } catch (ReflectiveOperationException | LinkageError e) {
        System.err.println("[apksim] " + runner + ": " + e);
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
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
          System.err.println("[apksim] listener " + name + ": " + e);
        }
      }
    }
    if (!listeners.isEmpty()) System.err.println("[apksim] listeners: " + names);
    LISTENERS.install(listeners);
  }

  /** Forwards the run to listeners that join late, replaying the run's start and the current test's start to them. */
  static final class Deferred extends RunListener {
    private final List<RunListener> active = new CopyOnWriteArrayList<>();
    private Description run;
    private Description current;

    synchronized void install(List<RunListener> listeners) {
      for (RunListener l : listeners) {
        safely(() -> {
          if (run != null) l.testRunStarted(run);
          if (current != null) l.testStarted(current);
        });
        active.add(l);
      }
    }

    private interface Call {
      void run() throws Exception;
    }

    private static void safely(Call c) {
      try {
        c.run();
      } catch (Exception | LinkageError e) {
        System.err.println("[apksim] listener: " + e);
      }
    }

    @Override
    public synchronized void testRunStarted(Description d) {
      run = d;
      for (RunListener l : active) safely(() -> l.testRunStarted(d));
    }

    @Override
    public void testRunFinished(Result r) {
      SimTestStorage.ensureInstalled();
      for (RunListener l : active) safely(() -> l.testRunFinished(r));
    }

    @Override
    public void testSuiteStarted(Description d) {
      for (RunListener l : active) safely(() -> l.testSuiteStarted(d));
    }

    @Override
    public void testSuiteFinished(Description d) {
      for (RunListener l : active) safely(() -> l.testSuiteFinished(d));
    }

    @Override
    public synchronized void testStarted(Description d) {
      current = d;
      for (RunListener l : active) safely(() -> l.testStarted(d));
    }

    @Override
    public synchronized void testFinished(Description d) {
      SimTestStorage.ensureInstalled();
      for (RunListener l : active) safely(() -> l.testFinished(d));
      current = null;
    }

    @Override
    public void testFailure(Failure f) {
      SimTestStorage.ensureInstalled();
      for (RunListener l : active) safely(() -> l.testFailure(f));
    }

    @Override
    public void testAssumptionFailure(Failure f) {
      SimTestStorage.ensureInstalled();
      for (RunListener l : active) safely(() -> l.testAssumptionFailure(f));
    }

    @Override
    public void testIgnored(Description d) {
      SimTestStorage.ensureInstalled();
      for (RunListener l : active) safely(() -> l.testIgnored(d));
    }
  }
}
