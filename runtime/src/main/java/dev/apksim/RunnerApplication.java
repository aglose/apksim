package dev.apksim;

import android.app.Application;
import android.app.Instrumentation;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Application a test APK's instrumentation class (an AndroidJUnitRunner subclass) asks for in newApplication. On
 * a device the runner creates the app's Application, and a custom one often swaps in a test Application there
 * ({@code super.newApplication(cl, TestApp::class.java.name, context)}). apksim does not run the runner, so
 * Robolectric made the manifest's Application instead.
 *
 * <p>So before a test run, when the runner is not plain AndroidJUnitRunner, a probe runs as a test of its own, with a
 * plain Application so that the app's own onCreate does not run. It creates the runner and calls its newApplication
 * with the manifest's class name; the Instrumentation shadow records the class the runner hands on to
 * Instrumentation.newApplication and stops there, so no Application is made and nothing after that call runs. The
 * runner's own onCreate and onStart (which start a device's test run) never run. SimRunner then gives every test that
 * class ({@code Config.application}). If the runner's newApplication needs more than it is given (its context is the
 * probe's), the run keeps the manifest's Application and says so.
 */
@RunWith(SimRunner.class)
@Config(sdk = 36, application = Application.class)
public class RunnerApplication {
  /** What the probe found, when it differs from the manifest's; read by SimRunner for every test after it. */
  static volatile Class<? extends Application> chosen;

  /** Set while the probe calls newApplication: the Instrumentation shadow records the class instead of making it. */
  static volatile boolean recording;

  private static volatile String runnerClass;
  private static volatile String manifestApplication;
  private static volatile String recorded;
  private static volatile Throwable failure;

  /** Thrown by the Instrumentation shadow once it has the class, so that nothing is made. */
  static final class Recorded extends RuntimeException {
    Recorded(String className) {
      super(className, null, false, false);
    }
  }

  /** Before the test classes' runners are made: finds what the runner's newApplication asks for. */
  static void probe(String instrumentation) {
    if (instrumentation.equals("androidx.test.runner.AndroidJUnitRunner")) return;
    if (!Boolean.parseBoolean(System.getProperty("apksim.runnerApplication", "true"))) return;  // the manifest's, as before
    long t0 = System.nanoTime();
    runnerClass = instrumentation;
    Result r = new JUnitCore().run(Request.aClass(RunnerApplication.class));
    String manifest = manifestApplication;
    String ms = (System.nanoTime() - t0) / 1_000_000 + " ms";
    if (!r.wasSuccessful() || failure != null || recorded == null) {
      Throwable why = failure != null ? failure : r.getFailureCount() > 0 ? r.getFailures().get(0).getException() : null;
      System.err.println("[apksim] " + instrumentation + ".newApplication could not be run here (" + why
          + "): tests get the manifest's Application, " + manifest);
      return;
    }
    if (recorded.equals(manifest)) return;
    try {
      chosen = Class.forName(recorded, false, RunnerApplication.class.getClassLoader()).asSubclass(Application.class);
      System.err.println("[apksim] Application from " + instrumentation + ".newApplication: " + recorded + " (" + ms + ")");
    } catch (ClassNotFoundException | ClassCastException e) {
      System.err.println("[apksim] " + instrumentation + ".newApplication asked for " + recorded + ", which cannot be used ("
          + e + "): tests get the manifest's Application, " + manifest);
    }
  }

  @Test
  public void probe() {
    Application app = RuntimeEnvironment.getApplication();
    String manifest = app.getApplicationInfo().className;
    manifestApplication = manifest != null ? manifest : Application.class.getName();
    try {
      Instrumentation runner = (Instrumentation) Class.forName(runnerClass, true, RunnerApplication.class.getClassLoader())
          .getDeclaredConstructor().newInstance();
      recording = true;
      try {
        runner.newApplication(RunnerApplication.class.getClassLoader(), manifestApplication, app.getBaseContext());
        failure = new IllegalStateException("newApplication returned without asking Instrumentation for an Application");
      } finally {
        recording = false;
      }
    } catch (Recorded r) {
      recorded = r.getMessage();
    } catch (Throwable t) {
      failure = t instanceof java.lang.reflect.InvocationTargetException e && e.getCause() != null ? e.getCause() : t;
    }
  }
}
