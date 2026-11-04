package dev.apksim;

import android.app.ActivityThread;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.os.Bundle;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.robolectric.RuntimeEnvironment;

/**
 * `am instrument` over adb for an instrumentation installed beside the running app (see Packages) that targets its own
 * package: a tool's on-device server, such as the Android CLI's layout server, which reads the screen through
 * UiAutomation and answers on a local socket. On a device it runs in a process of its own; here it runs in the app's
 * process, as a second package: its classes in a loader of their own (they bring their own Kotlin and AndroidX Test),
 * its own context (package name, assets), and an Instrumentation set up as ActivityThread would, then onCreate on the
 * main thread and onStart on its own thread. sendStatus and finish (TestActivities.ShadowSimInstrumentation) print
 * what `am instrument` prints. It keeps running when the adb stream that started it closes, as with `&` on a device.
 *
 * <p>Test APKs that target the app run with `apksim --instrument` instead: they need Robolectric's test environment,
 * which a running instance does not have.
 */
final class LiveInstrumentation {
  private static final Map<Instrumentation, LiveInstrumentation> BY_INSTANCE = new ConcurrentHashMap<>();
  private static final Map<String, LiveInstrumentation> BY_PACKAGE = new ConcurrentHashMap<>();

  private final Packages.Installed pkg;
  private final ComponentName component;
  private final boolean raw;
  private volatile OutputStream out;
  private final CompletableFuture<Integer> done = new CompletableFuture<>();
  private PackageContext context;

  private LiveInstrumentation(Packages.Installed pkg, ComponentName component, boolean raw, OutputStream out) {
    this.pkg = pkg;
    this.component = component;
    this.raw = raw;
    this.out = out;
  }

  static LiveInstrumentation of(Instrumentation i) {
    return BY_INSTANCE.get(i);
  }

  static LiveInstrumentation running(String pkg) {
    return BY_PACKAGE.get(pkg);
  }

  /**
   * Runs `am instrument` for COMPONENT (pkg/class): starts it and, with -w, prints its status until it finishes or
   * the caller goes away. Returns the shell's exit status.
   */
  static int start(String target, Bundle args, boolean raw, boolean await, OutputStream out, BooleanSupplier gone) throws Exception {
    int slash = target.indexOf('/');
    String pkgName = slash < 0 ? target : target.substring(0, slash);
    Packages.Installed pkg = Packages.get(pkgName);
    String cls = slash < 0 ? (pkg == null ? null : pkg.instrumentation()) : target.substring(slash + 1);
    if (cls != null && cls.startsWith(".")) cls = pkgName + cls;
    String app = System.getProperty("apksim.package");
    if (pkg == null || cls == null || !cls.equals(pkg.instrumentation())) {
      String why = pkgName.equals(app) || pkgName.equals(app + ".test")
          ? "apksim runs test APKs with `apksim --instrument TEST.apk APP.apk` (or scripts/instrument.py), not in a running instance"
          : "Unable to find instrumentation info for: ComponentInfo{" + pkgName + "/" + cls + "}";
      return failed(out, why, pkgName + "/" + cls);
    }
    if (!pkg.target().equals(pkg.pkg())) {
      return failed(out, "apksim runs over adb only an instrumentation that targets its own package; " + pkg.pkg()
          + " targets " + pkg.target() + ": run it with `apksim --instrument`", pkgName + "/" + cls);
    }
    LiveInstrumentation live = new LiveInstrumentation(pkg, new ComponentName(pkg.pkg(), cls), raw, out);
    if (BY_PACKAGE.putIfAbsent(pkg.pkg(), live) != null) {
      return failed(out, pkg.pkg() + " is already running its instrumentation in this instance", pkgName + "/" + cls);
    }
    try {
      live.launch(args);
    } catch (Throwable t) {
      BY_PACKAGE.remove(pkg.pkg(), live);
      BY_INSTANCE.values().remove(live);
      if (live.context != null) {
        PackageContext c = live.context;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(c::unregisterAll);
      }
      System.err.println("[apksim] am instrument " + target + ": " + t);
      t.printStackTrace();
      Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
      live.print("INSTRUMENTATION_RESULT: shortMsg=" + cause + "\nINSTRUMENTATION_CODE: 0\n");
      return 1;
    }
    if (!await) {
      live.out = null;
      return 0;
    }
    while (!live.done.isDone()) {
      try {
        live.done.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        if (gone.getAsBoolean()) {
          live.out = null;  // keeps running, as a backgrounded `am instrument -w ... &` does when adb disconnects
          return 0;
        }
      }
    }
    return 0;
  }

  private static int failed(OutputStream out, String why, String component) throws IOException {
    String s = "INSTRUMENTATION_STATUS: id=ActivityManagerService\nINSTRUMENTATION_STATUS: Error=" + why
        + "\nINSTRUMENTATION_STATUS_CODE: -1\nandroid.util.AndroidException: INSTRUMENTATION_FAILED: " + component + "\n";
    out.write(s.getBytes(StandardCharsets.UTF_8));
    out.flush();
    return 1;
  }

  private void launch(Bundle args) throws Exception {
    AppClasses classes = AppClasses.isolated(pkg.apk());
    Loader loader = new Loader(classes);
    Context base = RuntimeEnvironment.getApplication();
    PackageContext context = new PackageContext(base, pkg.pkg(), loader, assets(pkg.apk()));
    this.context = context;
    Instrumentation instr = (Instrumentation) loader.loadClass(component.getClassName()).getDeclaredConstructor().newInstance();
    BY_INSTANCE.put(instr, this);
    System.err.println("[apksim] am instrument: starting " + component.flattenToShortString());
    AdbShell.onMain(() -> {
      // As ActivityThread.handleBindApplication: init, then onCreate (which calls start(), whose thread runs onStart).
      Method init = Instrumentation.class.getDeclaredMethod("init", ActivityThread.class, Context.class, Context.class,
          ComponentName.class, Class.forName("android.app.IInstrumentationWatcher"), Class.forName("android.app.IUiAutomationConnection"));
      init.setAccessible(true);
      init.invoke(instr, RuntimeEnvironment.getActivityThread(), context, context, component, null, null);
      // AndroidX Test's onCreate makes the target context's loader the thread's context loader. On a device that is
      // the instrumentation's own main thread; here it is the app's, whose code must keep its own.
      Thread main = Thread.currentThread();
      ClassLoader kept = main.getContextClassLoader();
      Thread.currentThread().setContextClassLoader(loader);
      try {
        instr.onCreate(args);
      } finally {
        main.setContextClassLoader(kept);
      }
      return null;
    });
  }

  /** The instrumentation APK's assets, as its own context would have them (the Android CLI reads its VERSION). */
  private static AssetManager assets(java.nio.file.Path apk) throws Exception {
    AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
    Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    add.invoke(am, apk.toString());
    return am;
  }

  void status(int code, Bundle results) {
    StringBuilder b = new StringBuilder();
    String pretty = !raw && results != null ? results.getString(Instrumentation.REPORT_KEY_STREAMRESULT) : null;
    if (pretty != null) {
      b.append(pretty);
    } else {
      if (results != null) for (String k : results.keySet()) b.append("INSTRUMENTATION_STATUS: ").append(k).append('=').append(results.get(k)).append('\n');
      b.append("INSTRUMENTATION_STATUS_CODE: ").append(code).append('\n');
    }
    print(b.toString());
  }

  void finished(int code, Bundle results) {
    StringBuilder b = new StringBuilder();
    String pretty = !raw && results != null ? results.getString(Instrumentation.REPORT_KEY_STREAMRESULT) : null;
    if (pretty != null) {
      b.append(pretty);
    } else {
      if (results != null) for (String k : results.keySet()) b.append("INSTRUMENTATION_RESULT: ").append(k).append('=').append(results.get(k)).append('\n');
      b.append("INSTRUMENTATION_CODE: ").append(code).append('\n');
    }
    print(b.toString());
    System.err.println("[apksim] am instrument: " + component.flattenToShortString() + " finished (" + code + ")");
    if (AdbServer.TRACE) new Throwable("finish(" + code + ", " + results + ") called from").printStackTrace();
    BY_PACKAGE.remove(pkg.pkg(), this);
    // Nothing here keeps the finished instrumentation, so its loader and classes can go once its threads end.
    BY_INSTANCE.values().remove(this);
    if (context != null) {
      PackageContext c = context;
      new android.os.Handler(android.os.Looper.getMainLooper()).post(c::unregisterAll);
    }
    done.complete(code);
  }

  private void print(String s) {
    OutputStream o = out;
    if (o == null) return;
    try {
      o.write(s.getBytes(StandardCharsets.UTF_8));
      o.flush();
    } catch (IOException e) {
      out = null;  // the adb stream closed; the instrumentation goes on
    }
  }

  /** Loads the instrumentation APK's classes first, from its own DEX, and everything else (the framework) from apksim's. */
  static final class Loader extends ClassLoader {
    private final AppClasses classes;

    static {
      registerAsParallelCapable();
    }

    Loader(AppClasses classes) {
      super("apksim-instrumentation", ClassLoader.getSystemClassLoader());
      this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
          byte[] bytes = name.startsWith("java.") ? null : classes.translate(name.replace('.', '/'));
          c = bytes != null ? defineClass(name, bytes, 0, bytes.length) : getParent().loadClass(name);
        }
        if (resolve) resolveClass(c);
        return c;
      }
    }
  }

  /** The instrumentation package's context: the app's, with that package's name, classes and assets. */
  static final class PackageContext extends ContextWrapper {
    private final String pkg;
    private final ClassLoader loader;
    private final AssetManager assets;

    PackageContext(Context base, String pkg, ClassLoader loader, AssetManager assets) {
      super(base);
      this.pkg = pkg;
      this.loader = loader;
      this.assets = assets;
    }

    @Override
    public String getPackageName() {
      return pkg;
    }

    @Override
    public String getOpPackageName() {
      return pkg;
    }

    @Override
    public ClassLoader getClassLoader() {
      return loader;
    }

    @Override
    public AssetManager getAssets() {
      return assets;
    }

    @Override
    public Context getApplicationContext() {
      return this;
    }

    // Receivers it registers are unregistered when it finishes: on a device they would go with its process, and here
    // a stale one would get the next run's broadcasts, with classes from another loader.
    final java.util.Set<android.content.BroadcastReceiver> receivers = ConcurrentHashMap.newKeySet();

    @Override
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r, android.content.IntentFilter f) {
      if (r != null) receivers.add(r);
      return super.registerReceiver(r, f);
    }

    @Override
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r, android.content.IntentFilter f, int flags) {
      if (r != null) receivers.add(r);
      return super.registerReceiver(r, f, flags);
    }

    @Override
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r, android.content.IntentFilter f,
        String permission, android.os.Handler h) {
      if (r != null) receivers.add(r);
      return super.registerReceiver(r, f, permission, h);
    }

    @Override
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver r, android.content.IntentFilter f,
        String permission, android.os.Handler h, int flags) {
      if (r != null) receivers.add(r);
      return super.registerReceiver(r, f, permission, h, flags);
    }

    @Override
    public void unregisterReceiver(android.content.BroadcastReceiver r) {
      receivers.remove(r);
      super.unregisterReceiver(r);
    }

    void unregisterAll() {
      for (android.content.BroadcastReceiver r : receivers) {
        try {
          getBaseContext().unregisterReceiver(r);
        } catch (RuntimeException e) {
          // Already gone.
        }
      }
      receivers.clear();
    }
  }
}
