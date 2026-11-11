package dev.apksim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A pre-warmed test process ({@code --pooled}, which instrument.py --isolate adds to the processes of its pool:
 * docs/test-pool.md). It is started with the run's whole command line except the arguments that name the test, boots
 * everything that does not depend on which test it gets (the JVM, the APKs' indexes and the classes they loaded last
 * time, the sandbox, native runtime and a plain Application through the runner probe's test without a runner), then reads the test's {@code -e} arguments
 * from stdin, to its end, and goes on as a process started with them would. No app code runs before that: the probe's
 * Application is a plain one, as in every cold process of a suite with its own runner. One test, then the process
 * exits, as before: only the boot moved ahead.
 *
 * <p>stdin holds {@code KEY\0VALUE\0} pairs (NUL cannot be in an argument, so a Parameterized name with a comma or a
 * line break goes as it is). An empty stdin (instrument.py closing a process its run no longer needs, or instrument.py
 * itself gone) ends the process quietly, with status 0.
 */
final class TestPool {
  private TestPool() {}

  static boolean requested() {
    return Boolean.getBoolean("apksim.pooled");
  }

  /** Boots what any test of the run needs, then waits for the test's arguments and sets them (apksim.instrument.KEY). */
  static void await(String instrumentation, AppClasses tests) throws IOException {
    long t0 = System.nanoTime();
    // Until the hand-over, instrument.py reads only this process's stderr: anything printed to System.out meanwhile
    // goes there, so a talkative warm-up cannot fill the unread stdout pipe and stall the process.
    java.io.PrintStream out = System.out;
    System.setOut(System.err);
    tests.loadAhead();  // the test APK's classes the last test process loaded (defined only: no static initializers)
    // The sandbox, native runtime and Robolectric's first environment, through the runner probe's test with no runner
    // (a plain Application). The probe itself (a suite's own runner's newApplication) runs after the hand-over, as in a
    // cold process, since the runner may read the test's arguments.
    RunnerApplication.warm();
    long ready = System.nanoTime();
    System.err.printf("[apksim] pool: ready, %d ms after the JVM started (%d ms of it warming up)%n",
        System.currentTimeMillis() - ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli)
            .orElse(System.currentTimeMillis()), (ready - t0) / 1_000_000);
    System.err.flush();
    byte[] in = System.in.readAllBytes();
    if (in.length == 0) {
      // Not needed (the run had no test left for it, or instrument.py went away): gone without a word on stdout.
      ClassCache.flush();
      Storage.deleteScratch();
      Runtime.getRuntime().halt(0);
    }
    List<String> parts = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < in.length; i++) {
      if (in[i] == 0) {
        parts.add(new String(in, start, i - start, StandardCharsets.UTF_8));
        start = i + 1;
      }
    }
    for (int i = 0; i + 1 < parts.size(); i += 2) System.setProperty("apksim.instrument." + parts.get(i), parts.get(i + 1));
    System.err.printf("[apksim] pool: test handed over after %d ms ready%n", (System.nanoTime() - ready) / 1_000_000);
    System.setOut(out);
  }
}
