package org.robolectric.internal.bytecode;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.robolectric.util.ReflectionHelpers;

/**
 * apksim's copy of Robolectric 4.17's InvokeDynamicSupport, ahead of it on the class path: the bootstrap every
 * framework method's trampoline links through. Same entry points, same choice of target (the shadow method, or the
 * original), but bound straight to it.
 *
 * <p>Robolectric binds each site to {@code SwitchPoint.guardWithTest(catchException(target, cleanStackTrace),
 * fallback)}: the guard so a later test with other shadows can re-bind, the catch to strip {@code $$robo$$} frames from
 * every exception passing through. Building those chains (a LambdaForm per shape, a few thousand hidden classes) was a
 * quarter of a cold launch, and each framework call ran through them until C2 inlined it (test runs stop at C1). apksim
 * runs one set of shadows per process, so instead each site is bound directly, and {@link ShadowInvalidator} resets the
 * sites of the classes it invalidates to a fallback that binds again at their next call, which is when Robolectric's
 * guard would have re-bound them.
 *
 * <p>Stack traces are cleaned where apksim shows them instead (dev.apksim.StackTraces), unless
 * {@code -Dapksim.cleanStackTraces=true} keeps Robolectric's per-site cleaning. {@code -Dapksim.directCallSites=false}
 * restores Robolectric's binding altogether (A/B).
 */
public class InvokeDynamicSupport {
  /** Set by Sandbox.configure (reflectively, by name). */
  @SuppressWarnings("unused")
  private static Interceptors INTERCEPTORS;

  private static final MethodHandle BIND_CALL_SITE;
  private static final MethodHandle BIND_INIT_CALL_SITE;
  private static final MethodHandle EXCEPTION_HANDLER;
  private static final MethodHandle GET_SHADOW;
  private static final MethodHandle NOTHING;

  private static final boolean DIRECT = !"false".equals(System.getProperty("apksim.directCallSites"));
  private static final boolean CLEAN = !DIRECT || Boolean.getBoolean("apksim.cleanStackTraces");

  /** Sites bound directly, by the name of the class that declares them: what an invalidation must reset. */
  private static final Map<String, List<RoboCallSite>> SITES = new HashMap<>();

  static {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      BIND_CALL_SITE = lookup.findStatic(InvokeDynamicSupport.class, "bindCallSite",
          MethodType.methodType(MethodHandle.class, MethodCallSite.class));
      BIND_INIT_CALL_SITE = lookup.findStatic(InvokeDynamicSupport.class, "bindInitCallSite",
          MethodType.methodType(MethodHandle.class, RoboCallSite.class));
      MethodHandle clean = lookup.findStatic(RobolectricInternals.class, "cleanStackTrace",
          MethodType.methodType(Throwable.class, Throwable.class));
      EXCEPTION_HANDLER = MethodHandles.filterArguments(MethodHandles.throwException(void.class, Throwable.class), 0, clean);
      GET_SHADOW = lookup.findVirtual(ShadowedObject.class, "$$robo$getData", MethodType.methodType(Object.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
    NOTHING = MethodHandles.constant(Void.class, null).asType(MethodType.methodType(void.class));
  }

  public InvokeDynamicSupport() {}

  public static CallSite bootstrapInit(MethodHandles.Lookup caller, String name, MethodType type) {
    RoboCallSite site = new RoboCallSite(type, caller.lookupClass());
    bindInitCallSite(site);
    return site;
  }

  public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, MethodHandle original,
      int isNative) throws IllegalAccessException {
    MethodCallSite site = new MethodCallSite(caller.lookupClass(), type, name, original, MethodCallSite.Kind.REGULAR,
        isNative == 1);
    bindCallSite(site);
    return site;
  }

  public static CallSite bootstrapStatic(MethodHandles.Lookup caller, String name, MethodType type, MethodHandle original,
      int isNative) throws IllegalAccessException {
    MethodCallSite site = new MethodCallSite(caller.lookupClass(), type, name, original, MethodCallSite.Kind.STATIC,
        isNative == 1);
    bindCallSite(site);
    return site;
  }

  public static CallSite bootstrapIntrinsic(MethodHandles.Lookup caller, String name, MethodType type, String callee) {
    MethodHandle mh = getMethodHandle(callee, name, type);
    if (mh == null) throw new IllegalArgumentException("Could not find intrinsic for " + callee + ":" + name);
    return new ConstantCallSite(mh.asType(type));
  }

  private static MethodHandle getMethodHandle(String className, String methodName, MethodType type) {
    Interceptor interceptor = INTERCEPTORS.findInterceptor(className, methodName);
    if (interceptor != null) {
      try {
        // reload interceptor in sandbox...
        Class<? extends Interceptor> theClass = ReflectionHelpers.loadClass(RobolectricInternals.getClassLoader(),
            interceptor.getClass().getName()).asSubclass(Interceptor.class);
        return ReflectionHelpers.newInstance(theClass).getMethodHandle(methodName, type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
    if (type.parameterCount() != 0) return MethodHandles.dropArguments(NOTHING, 0, type.parameterArray());
    return NOTHING;
  }

  private static MethodHandle bindInitCallSite(RoboCallSite site) {
    MethodHandle mh = RobolectricInternals.getShadowCreator(site.getTheClass());
    return bind(site, mh, BIND_INIT_CALL_SITE);
  }

  private static MethodHandle bindCallSite(MethodCallSite site) throws IllegalAccessException {
    MethodHandle mh = RobolectricInternals.findShadowMethodHandle(site.getTheClass(), site.getName(), site.type(),
        site.isStatic(), site.isNative());
    if (mh == null) {
      mh = site.getOriginal();
    } else if (!site.isStatic()) {
      Class<?> shadowType = mh.type().parameterType(0);
      if (!shadowType.equals(site.getTheClass())) {
        mh = MethodHandles.filterArguments(mh, 0, GET_SHADOW.asType(MethodType.methodType(shadowType, site.thisType())));
      }
    }
    try {
      return bind(site, CLEAN ? cleanStackTraces(mh) : mh, BIND_CALL_SITE);
    } catch (Throwable t) {
      t.printStackTrace();
      System.err.println(site.getTheClass());
      throw t;
    }
  }

  private static MethodHandle bind(RoboCallSite site, MethodHandle mh, MethodHandle binder) {
    if (!DIRECT) return bindWithFallback(site, mh, binder);
    try {
      mh = mh.asType(site.type());
    } catch (java.lang.invoke.WrongMethodTypeException e) {
      throw failed(site, e);
    }
    // Registered before the target is set: an invalidation that comes in between resets it after we set it.
    synchronized (SITES) {
      List<RoboCallSite> list = SITES.computeIfAbsent(site.getTheClass().getName(), k -> new ArrayList<>());
      if (list.isEmpty() || list.get(list.size() - 1) != site) list.add(site);
    }
    site.setTarget(mh);
    return mh;
  }

  /**
   * The classes' shadows changed (ShadowInvalidator): their sites bind again at their next call, as Robolectric's
   * SwitchPoint fallback would. Their entries are dropped; a re-bind registers the site again.
   */
  static void invalidated(Collection<String> classNames) {
    if (!DIRECT) return;
    List<RoboCallSite> reset = new ArrayList<>();
    synchronized (SITES) {
      for (String name : classNames) {
        List<RoboCallSite> list = SITES.remove(name);
        if (list != null) reset.addAll(list);
      }
    }
    for (RoboCallSite site : reset) {
      MethodHandle binder = site instanceof MethodCallSite ? BIND_CALL_SITE : BIND_INIT_CALL_SITE;
      site.setTarget(fallback(site, binder));
    }
  }

  /** Calls binder(site) to get the target, then invokes it with the call's arguments. */
  private static MethodHandle fallback(RoboCallSite site, MethodHandle binder) {
    return MethodHandles.foldArguments(MethodHandles.exactInvoker(site.type()), binder.bindTo(site));
  }

  /** Robolectric's own binding: guarded by the class's SwitchPoint, re-bound through the fallback when invalidated. */
  private static MethodHandle bindWithFallback(RoboCallSite site, MethodHandle mh, MethodHandle binder) {
    java.lang.invoke.SwitchPoint switchPoint = RobolectricInternals.getShadowInvalidator().getSwitchPoint(site.getTheClass());
    MethodType type = site.type();
    try {
      mh = switchPoint.guardWithTest(mh.asType(type), fallback(site, binder));
    } catch (java.lang.invoke.WrongMethodTypeException e) {
      throw failed(site, e);
    }
    site.setTarget(mh);
    return mh;
  }

  private static RuntimeException failed(RoboCallSite site, RuntimeException e) {
    if (site instanceof MethodCallSite m) return new RuntimeException(m.thisType() + "." + m.getName(), e);
    return e;
  }

  private static MethodHandle cleanStackTraces(MethodHandle mh) {
    MethodType type = EXCEPTION_HANDLER.type().changeReturnType(mh.type().returnType());
    return MethodHandles.catchException(mh, Throwable.class, EXCEPTION_HANDLER.asType(type));
  }
}
