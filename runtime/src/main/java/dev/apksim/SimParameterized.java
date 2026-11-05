package dev.apksim;

import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.runner.Runner;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.internal.SandboxTestRunner;

/**
 * JUnit's {@code @RunWith(Parameterized.class)} in a test run. On a device every runner runs inside the
 * instrumentation, so a parameterized class has the app, its arguments and the main looper; JUnit's own Parameterized
 * here ran outside Robolectric entirely, and a base class that reads InstrumentationRegistry.getArguments() in its
 * constructor failed every test ("No instrumentation arguments registered"). Each parameter set runs as a SimRunner
 * child, the test built inside the environment from the class's {@code @Parameters} (as Robolectric's
 * ParameterizedRobolectricTestRunner does with its own annotation), named "method[name]" as JUnit names it.
 */
public final class SimParameterized extends Suite {
  private final List<Runner> runners = new ArrayList<>();

  public SimParameterized(Class<?> klass) throws Throwable {
    super(klass, Collections.emptyList());
    TestClass tc = getTestClass();
    FrameworkMethod method = parametersMethod(tc);
    String pattern = method.getAnnotation(Parameterized.Parameters.class).name();
    List<Object[]> all = parameters(method);
    for (int i = 0; i < all.size(); i++) {
      String name = MessageFormat.format(pattern.replace("{index}", Integer.toString(i)), all.get(i));
      runners.add(new ForParameters(klass, all.get(i), "[" + name + "]"));
    }
  }

  @Override
  protected List<Runner> getChildren() {
    return runners;
  }

  /**
   * One parameter set, between its @Parameterized.BeforeParam and @AfterParam methods (JUnit 4.13's per-parameter
   * callbacks: public static, with no arguments or the set's values), as JUnit's Parameterized runs them. They run
   * around the set's SimRunner, so not inside its test environment (which each test sets up and resets).
   */
  @Override
  protected void runChild(Runner child, org.junit.runner.notification.RunNotifier notifier) {
    Object[] values = child instanceof ForParameters p ? p.values : new Object[0];
    // As JUnit's RunAfterParams wraps RunBeforeParams: @AfterParam runs even when @BeforeParam fails (the set's tests
    // are then not run), so a failed setup still gets its cleanup.
    try {
      boolean setUp;
      try {
        callbacks(Parameterized.BeforeParam.class, values);
        setUp = true;
      } catch (org.junit.internal.AssumptionViolatedException a) {
        // An assumption skips the set, as JUnit's Parameterized does; its cleanup still runs (finally).
        notifier.fireTestAssumptionFailed(new org.junit.runner.notification.Failure(child.getDescription(), a));
        setUp = false;
      } catch (Throwable t) {
        notifier.fireTestFailure(new org.junit.runner.notification.Failure(child.getDescription(), t));
        setUp = false;
      }
      if (setUp) super.runChild(child, notifier);
    } finally {
      // Every @AfterParam runs, each failure reported, as JUnit collects them: one failing cleanup does not skip the rest.
      for (FrameworkMethod m : getTestClass().getAnnotatedMethods(Parameterized.AfterParam.class)) {
        try {
          invoke(m, values);
        } catch (Throwable t) {
          notifier.fireTestFailure(new org.junit.runner.notification.Failure(child.getDescription(), t));
        }
      }
    }
  }

  /** The @BeforeParam methods, in order; the first that throws ends them (as JUnit's RunBeforeParams). */
  private void callbacks(Class<? extends java.lang.annotation.Annotation> kind, Object[] values) throws Throwable {
    for (FrameworkMethod m : getTestClass().getAnnotatedMethods(kind)) invoke(m, values);
  }

  private static void invoke(FrameworkMethod m, Object[] values) throws Throwable {
    m.invokeExplosively(null, m.getMethod().getParameterCount() == 0 ? new Object[0] : values);
  }

  /** A class whose @Parameters made an assumption that failed: reported skipped, as JUnit's Parameterized does. */
  static Runner assumptionFailed(Class<?> klass, org.junit.internal.AssumptionViolatedException a) {
    org.junit.runner.Description d = org.junit.runner.Description.createTestDescription(klass, "parameters");
    return new Runner() {
      @Override
      public org.junit.runner.Description getDescription() {
        return d;
      }

      @Override
      public void run(org.junit.runner.notification.RunNotifier notifier) {
        notifier.fireTestStarted(d);
        notifier.fireTestAssumptionFailed(new org.junit.runner.notification.Failure(d, a));
        notifier.fireTestFinished(d);
      }
    };
  }

  /**
   * Only the children: each is a SimRunner, which runs the class's @BeforeClass, @AfterClass and @ClassRule inside the
   * test environment. Suite's own class block would run them once more, outside it, where an instrumentation-dependent
   * fixture fails. (So they run once per parameter set, not once for the class as JUnit's Parameterized does.)
   */
  @Override
  protected org.junit.runners.model.Statement classBlock(org.junit.runner.notification.RunNotifier notifier) {
    return childrenInvoker(notifier);
  }

  static FrameworkMethod parametersMethod(TestClass tc) throws Exception {
    for (FrameworkMethod m : tc.getAnnotatedMethods(Parameterized.Parameters.class)) {
      if (m.isStatic() && m.isPublic()) return m;
    }
    throw new Exception("No public static @Parameters method in " + tc.getName());
  }

  static List<Object[]> parameters(FrameworkMethod method) throws Throwable {
    Object result = method.invokeExplosively(null);
    Iterable<?> items = result instanceof Object[] array ? java.util.Arrays.asList(array) : (Iterable<?>) result;
    List<Object[]> out = new ArrayList<>();
    for (Object o : items) out.add(o instanceof Object[] a ? a : new Object[] {o});
    return out;
  }

  /** One parameter set: a SimRunner whose test instance gets the set's values, built in the test environment. */
  static final class ForParameters extends SimRunner {
    /** The set's values, from the one @Parameters call the suite made: the factory is not called again per test. */
    final Object[] values;
    private final String name;

    ForParameters(Class<?> klass, Object[] values, String name) throws InitializationError {
      super(klass);
      this.values = values;
      this.name = name;
    }

    @Override
    protected String getName() {
      return name;
    }

    @Override
    protected String testName(FrameworkMethod method) {
      return method.getName() + name;
    }

    @Override
    protected void validateConstructor(List<Throwable> errors) {
      validateOnlyOneConstructor(errors);
    }

    @Override
    protected void validateFields(List<Throwable> errors) {}

    @Override
    protected SandboxTestRunner.HelperTestRunner getHelperTestRunner(Class<?> bootstrapped) {
      try {
        return new RobolectricTestRunner.HelperTestRunner(bootstrapped) {
          @Override
          protected Object createTest() throws Exception {
            TestClass tc = getTestClass();
            List<FrameworkField> fields = tc.getAnnotatedFields(Parameterized.Parameter.class);
            if (fields.isEmpty()) return tc.getOnlyConstructor().newInstance(values);
            Object test = tc.getOnlyConstructor().newInstance();
            for (FrameworkField f : fields) {
              Field field = f.getField();
              field.setAccessible(true);
              field.set(test, values[field.getAnnotation(Parameterized.Parameter.class).value()]);
            }
            return test;
          }

          @Override
          protected org.junit.runners.model.Statement methodInvoker(FrameworkMethod method, Object test) {
            // Failure bundles capture in the test body here too, before teardown (SimRunner.bundled).
            return SimRunner.bundled(method, super.methodInvoker(method, test));
          }

          @Override
          protected void validateConstructor(List<Throwable> errors) {
            validateOnlyOneConstructor(errors);
          }

          @Override
          protected void validateFields(List<Throwable> errors) {}
        };
      } catch (InitializationError e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
