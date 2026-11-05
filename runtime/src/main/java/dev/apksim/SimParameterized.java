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
      runners.add(new ForParameters(klass, i, "[" + name + "]"));
    }
  }

  @Override
  protected List<Runner> getChildren() {
    return runners;
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
    private final int index;
    private final String name;

    ForParameters(Class<?> klass, int index, String name) throws InitializationError {
      super(klass);
      this.index = index;
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
            Object[] values;
            try {
              values = parameters(parametersMethod(tc)).get(index);
            } catch (Throwable t) {
              throw t instanceof Exception e ? e : new Exception(t);
            }
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
