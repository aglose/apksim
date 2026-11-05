package org.junit.internal.runners.statements;

import java.util.concurrent.TimeUnit;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestTimedOutException;

/**
 * apksim's own FailOnTimeout, with JUnit 4.13's API: it comes before JUnit's on the class path (apksim.jar is first),
 * so JUnit's Timeout rule and @Test(timeout) in any runner use it.
 *
 * <p>JUnit's runs the statement on a thread of its own and waits for it. In a test run here the test body has to stay
 * on the main thread: Robolectric's main looper, Espresso and Compose's test rules can only be driven from it ("Main
 * looper can only be controlled from its thread in PAUSED mode"), and there is no instrumentation thread beside it as
 * on a device. So this runs the statement on the calling thread, as Robolectric's TimeLimitedStatement does for its own
 * @Test(timeout), and a watchdog interrupts that thread once the time is up, and again every second until the
 * statement returns, in case something swallows an interrupt. Then it fails the test with TestTimedOutException, its
 * stack trace being where the test was when the time ran out (JUnit's lookingForStuckThread).
 *
 * <p>On a device a test that ignores interrupts is left running on its abandoned thread and the run goes on. Here
 * it holds the main thread, and the run would hang for good. So if it is still running {@link #GRACE_MS} after its
 * timeout, the watchdog prints where it is and ends the process with status 1: the run then has no result for that
 * test, which `instrument.py` reports failed, running the tests left in a new process (--isolate: its own anyway).
 */
public class FailOnTimeout extends Statement {
  /** How long past its timeout a test that ignores interrupts may run before the process ends. */
  static final long GRACE_MS = 10_000;

  private final Statement statement;
  private final long timeout;
  private final TimeUnit unit;

  public static Builder builder() {
    return new Builder();
  }

  /** @deprecated as JUnit's: use {@link #builder()}. */
  @Deprecated
  public FailOnTimeout(Statement statement, long timeoutMillis) {
    this(builder().withTimeout(timeoutMillis, TimeUnit.MILLISECONDS), statement);
  }

  private FailOnTimeout(Builder builder, Statement statement) {
    this.statement = statement;
    this.timeout = builder.timeout;
    this.unit = builder.unit;
  }

  public static class Builder {
    private long timeout;
    private TimeUnit unit = TimeUnit.SECONDS;

    private Builder() {}

    public Builder withTimeout(long timeout, TimeUnit unit) {
      if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
      if (unit == null) throw new NullPointerException("TimeUnit cannot be null");
      this.timeout = timeout;
      this.unit = unit;
      return this;
    }

    /** Accepted for compatibility: the stuck thread is always the caller's, and its stack is always reported. */
    public Builder withLookingForStuckThread(boolean enable) {
      return this;
    }

    public FailOnTimeout build(Statement statement) {
      if (statement == null) throw new NullPointerException("statement cannot be null");
      return new FailOnTimeout(this, statement);
    }
  }

  @Override
  public void evaluate() throws Throwable {
    if (timeout <= 0) {
      statement.evaluate();
      return;
    }
    Watchdog dog = new Watchdog(Thread.currentThread(), unit.toNanos(timeout));
    dog.start();
    Throwable failure = null;
    try {
      statement.evaluate();
    } catch (Throwable t) {
      failure = t;
    }
    StackTraceElement[] stuck = dog.finish();
    if (stuck != null) {
      TestTimedOutException e = new TestTimedOutException(timeout, unit);
      e.setStackTrace(stuck);
      throw e;
    }
    if (failure != null) throw failure;
  }

  private static final class Watchdog extends Thread {
    private final Thread target;
    private final long nanos;
    private boolean done;
    private StackTraceElement[] stuck;

    Watchdog(Thread target, long nanos) {
      super("Time-limited test watchdog");
      setDaemon(true);
      this.target = target;
      this.nanos = nanos;
    }

    @Override
    public void run() {
      long deadline = System.nanoTime() + nanos;
      synchronized (this) {
        try {
          for (long left; !done && (left = deadline - System.nanoTime()) > 0; ) {
            TimeUnit.NANOSECONDS.timedWait(this, left);
          }
          if (done) return;
          stuck = target.getStackTrace();
          long giveUp = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MS);
          while (!done) {
            if (System.nanoTime() - giveUp >= 0) halt();
            target.interrupt();
            wait(1000);
          }
        } catch (InterruptedException e) {
          // finish() interrupts only after done is set
        }
      }
    }

    /** The test ignored every interrupt for GRACE_MS past its timeout: say where it is, then end the process. */
    private void halt() {
      StringBuilder b = new StringBuilder("[apksim] test still running ").append(GRACE_MS / 1000)
          .append(" s after its timeout of ").append(TimeUnit.NANOSECONDS.toMillis(nanos))
          .append(" ms, ignoring interrupts; ending the process (on a device its thread would be abandoned). It is at:\n");
      for (StackTraceElement e : target.getStackTrace()) b.append("\tat ").append(e).append('\n');
      dev.apksim.Bundles.watchdog(b.toString());  // with --bundles: the failure, threads and log, before the process ends
      System.err.print(b);
      System.err.flush();
      System.out.flush();
      Runtime.getRuntime().halt(1);
    }

    /**
     * Stops the watchdog; returns where the test was when its time ran out, or null if it finished in time. After a
     * timeout it clears the interrupt the watchdog may have left on the caller; any other interrupt is kept.
     */
    StackTraceElement[] finish() {
      StackTraceElement[] s;
      synchronized (this) {
        done = true;  // from here on the watchdog interrupts nobody
        notifyAll();
        s = stuck;
      }
      if (s != null) Thread.interrupted();
      boolean interrupted = false;
      while (true) {
        try {
          join();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted && s == null) Thread.currentThread().interrupt();
      return s;
    }
  }
}
