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

  /** Set while the probe's run lasts: its plain Application is not the test's, so install state is not set up for it. */
  static volatile boolean probing;

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
    Result r;
    probing = true;
    try {
      r = new JUnitCore().run(Request.aClass(RunnerApplication.class));
    } finally {
      probing = false;
    }
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

  /**
   * At each test's setup, before Robolectric replaces ApplicationInfo.className with the configured Application (the
   * probe's plain one): the manifest's own class, which a device passes to newApplication and a runner may test.
   */
  static void noteManifest(org.robolectric.manifest.AndroidManifest manifest) {
    if (manifest == null || manifestApplication != null) return;
    String name = manifest.getApplicationName();
    String pkg = manifest.getPackageName();
    if (name == null || name.isEmpty()) name = Application.class.getName();
    else if (name.startsWith(".")) name = pkg + name;
    else if (name.indexOf('.') < 0) name = pkg + "." + name;
    manifestApplication = name;
  }

  @Test
  public void probe() {
    Application app = RuntimeEnvironment.getApplication();
    if (manifestApplication == null) manifestApplication = Application.class.getName();  // a manifest without one
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
