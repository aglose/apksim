package dev.apksim;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.robolectric.internal.bytecode.ClassHandler;
import org.robolectric.internal.bytecode.InvokeDynamicSupport;
import org.robolectric.internal.bytecode.RobolectricInternals;
import org.robolectric.internal.bytecode.ShadowInvalidator;

/**
 * Checks apksim's InvokeDynamicSupport (docs/perf-plan.md, B1) on a plain JVM, through its real bootstrap methods and
 * ShadowInvalidator, with a stand-in ClassHandler whose shadows a check can switch: instance, static and constructor
 * sites bind to the shadow of the moment, keep it while nothing is invalidated, and take the new one after their class
 * is invalidated, while another class's sites keep theirs; and a binding that looked its target up before an
 * invalidation, published after it, still ends on the new target (the generation check). Exits 1 on any failure.
 * scripts/check-call-sites.sh runs it (CI).
 */
public final class CallSiteCheck {
  private CallSiteCheck() {}

  /** A framework class as the instrumenter leaves it: each method's original body, reached through a call site. */
  public static final class Target {
    public String hello() {
      return "original-hello";
    }

    public static String shout() {
      return "original-shout";
    }
  }

  /** Another class, whose sites an invalidation of Target must leave alone. */
  public static final class Other {
    public String hello() {
      return "original-other";
    }
  }

  /** Shadow implementations, by name: "A" and "B" stand for two tests' shadow configurations. */
  public static String helloA(Target t) {
    return "A-hello";
  }

  public static String helloB(Target t) {
    return "B-hello";
  }

  public static String shoutA() {
    return "A-shout";
  }

  public static String shoutB() {
    return "B-shout";
  }

  public static Object createA(Target t) {
    return "A-shadow";
  }

  public static Object createB(Target t) {
    return "B-shadow";
  }

  public static String otherA(Other o) {
    return "A-other";
  }

  public static String otherB(Other o) {
    return "B-other";
  }

  /** The ClassHandler: answers with the current configuration's shadows; a lookup can be held to stage a race. */
  static final class Handler implements ClassHandler {
    volatile String config = "A";
    volatile CountDownLatch lookedUp;
    volatile CountDownLatch release;

    @Override
    public void classInitializing(Class<?> clazz) {}

    @Override
    public MethodHandle getShadowCreator(Class<?> theClass) {
      return find("create" + config, MethodType.methodType(Object.class, Target.class));
    }

    @Override
    public MethodHandle findShadowMethodHandle(Class<?> theClass, String name, MethodType type, boolean isStatic,
        boolean isNative) {
      String seen = config;
      MethodHandle mh = theClass == Other.class ? find("other" + seen, MethodType.methodType(String.class, Other.class))
          : isStatic ? find("shout" + seen, MethodType.methodType(String.class))
          : find("hello" + seen, MethodType.methodType(String.class, Target.class));
      CountDownLatch held = release;
      if (held != null && Thread.currentThread().getName().equals("racer")) {
        lookedUp.countDown();
        try {
          held.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return mh;
    }

    @Override
    public Object intercept(String signature, Object instance, Object[] params, Class<?> theClass) {
      return null;
    }

    @Override
    public <T extends Throwable> T stripStackTrace(T throwable) {
      return throwable;
    }

    private static MethodHandle find(String name, MethodType type) {
      try {
        return MethodHandles.lookup().findStatic(CallSiteCheck.class, name, type);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static int failures;

  private static void expect(String what, Object got, Object want) {
    boolean ok = want.equals(got);
    if (!ok) failures++;
    System.out.printf("%s %-62s %s%n", ok ? "ok  " : "FAIL", what, ok ? got : got + " (expected " + want + ")");
  }

  private static CallSite instanceSite(Class<?> c) throws Exception {
    MethodHandles.Lookup in = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
    MethodHandle original = in.findVirtual(c, "hello", MethodType.methodType(String.class));
    return InvokeDynamicSupport.bootstrap(in, "hello", MethodType.methodType(String.class, c), original, 0);
  }

  private static CallSite staticSite() throws Exception {
    MethodHandles.Lookup in = MethodHandles.privateLookupIn(Target.class, MethodHandles.lookup());
    MethodHandle original = in.findStatic(Target.class, "shout", MethodType.methodType(String.class));
    return InvokeDynamicSupport.bootstrapStatic(in, "shout", MethodType.methodType(String.class), original, 0);
  }

  private static CallSite initSite() throws Exception {
    MethodHandles.Lookup in = MethodHandles.privateLookupIn(Target.class, MethodHandles.lookup());
    return InvokeDynamicSupport.bootstrapInit(in, "$$robo$init", MethodType.methodType(Object.class, Target.class));
  }

  private static void setStatic(String field, Object value) throws Exception {
    Field f = RobolectricInternals.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(null, value);
  }

  public static void main(String[] args) throws Throwable {
    Handler handler = new Handler();
    ShadowInvalidator invalidator = new ShadowInvalidator();
    setStatic("classHandler", handler);
    setStatic("shadowInvalidator", invalidator);
    System.out.println("call sites " + (!"false".equals(System.getProperty("apksim.directCallSites"))
        ? "bound directly" : "bound as Robolectric binds them"));

    Target t = new Target();
    Other o = new Other();
    CallSite hello = instanceSite(Target.class);
    CallSite shout = staticSite();
    CallSite init = initSite();
    CallSite other = instanceSite(Other.class);
    expect("instance site, configuration A", (String) hello.dynamicInvoker().invokeExact(t), "A-hello");
    expect("static site, configuration A", (String) shout.dynamicInvoker().invokeExact(), "A-shout");
    expect("constructor site, configuration A", (Object) init.dynamicInvoker().invokeExact(t), "A-shadow");
    expect("another class's site, configuration A", (String) other.dynamicInvoker().invokeExact(o), "A-other");

    handler.config = "B";
    invalidator.invalidateClasses(List.of(Target.class.getName()));
    expect("instance site after Target is invalidated", (String) hello.dynamicInvoker().invokeExact(t), "B-hello");
    expect("static site after Target is invalidated", (String) shout.dynamicInvoker().invokeExact(), "B-shout");
    expect("constructor site after Target is invalidated", (Object) init.dynamicInvoker().invokeExact(t), "B-shadow");
    expect("another class's site keeps its binding", (String) other.dynamicInvoker().invokeExact(o), "A-other");
    expect("the same site again (now bound to B)", (String) hello.dynamicInvoker().invokeExact(t), "B-hello");

    handler.config = "A";
    invalidator.invalidateClasses(List.of(Target.class.getName(), Other.class.getName()));
    expect("instance site after a second invalidation", (String) hello.dynamicInvoker().invokeExact(t), "A-hello");
    expect("another class's site once it is invalidated too", (String) other.dynamicInvoker().invokeExact(o), "A-other");

    // The race: a new site's binding looks up configuration A, then an invalidation (to B) completes, then the binding
    // publishes. Its next call must reach B.
    handler.config = "A";
    handler.lookedUp = new CountDownLatch(1);
    handler.release = new CountDownLatch(1);
    CallSite[] raced = new CallSite[1];
    Throwable[] failed = new Throwable[1];
    Thread racer = new Thread(() -> {
      try {
        raced[0] = instanceSite(Target.class);
      } catch (Throwable e) {
        failed[0] = e;
      }
    }, "racer");
    racer.start();
    if (!handler.lookedUp.await(10, TimeUnit.SECONDS)) throw new AssertionError("the racing binding never looked up");
    handler.config = "B";
    invalidator.invalidateClasses(List.of(Target.class.getName()));
    handler.release.countDown();
    racer.join(10_000);
    handler.release = null;
    if (failed[0] != null) throw failed[0];
    expect("a binding that raced an invalidation reaches the new shadow", (String) raced[0].dynamicInvoker().invokeExact(t), "B-hello");
    expect("and keeps it", (String) raced[0].dynamicInvoker().invokeExact(t), "B-hello");
    handler.config = "A";
    invalidator.invalidateClasses(List.of(Target.class.getName()));
    expect("and is invalidated with its class afterwards", (String) raced[0].dynamicInvoker().invokeExact(t), "A-hello");

    System.out.println(failures == 0 ? "call sites: all checks pass" : "call sites: " + failures + " failed");
    System.exit(failures == 0 ? 0 : 1);
  }
}
