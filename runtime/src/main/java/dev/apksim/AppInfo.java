package dev.apksim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** What the runtime needs from AndroidManifest.xml. Published as system properties for the sandbox. */
final class AppInfo {
  private static final String NS = "android:";
  private static volatile List<String> startup = List.of();

  private AppInfo() {}

  /** Startup entry-point classes (internal names) read from AndroidManifest.xml, for speculative translation. */
  static List<String> startupClasses() {
    return startup;
  }

  private static final class ActivityEntry {
    final String name;
    final String enabled;
    final String targetActivity;
    final List<FilterEntry> filters = new ArrayList<>();

    ActivityEntry(String name, String enabled, String targetActivity) {
      this.name = name;
      this.enabled = enabled;
      this.targetActivity = targetActivity;
    }
  }

  private static final class FilterEntry {
    final String priority;
    boolean main;
    boolean launcher;
    boolean home;
    boolean dflt;

    FilterEntry(String priority) {
      this.priority = priority;
    }
  }

  static void load(Path apk, Path cache) throws Exception {
    Files.createDirectories(cache);
    Path manifest = cache.resolve("AndroidManifest.xml");
    boolean needWrite = !Files.isRegularFile(manifest) || Files.size(manifest) == 0;
    Resources arsc = new Resources(apk);  // resources.arsc, read only if a launch filter's attribute names a resource
    byte[] raw;
    try (ZipFile zip = new ZipFile(apk.toFile()); InputStream in = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
      raw = in.readAllBytes();
    }

    String[] pkgRef = {""};
    int[] targetSdk = {0};
    int[] minSdk = {0};
    boolean[] seenUsesSdk = {false};
    Map<String, ActivityEntry> activities = new LinkedHashMap<>();
    List<ActivityEntry> activityOrder = new ArrayList<>();
    Set<String> seeds = new LinkedHashSet<>();

    String xml = Axml.parse(raw, Integer.MAX_VALUE, needWrite, new Axml.Visitor() {
      ActivityEntry curAct;
      FilterEntry curFilter;
      boolean inProvider;

      @Override
      public void startTag(String tag, Map<String, String> attrs) {
        switch (tag) {
          case "manifest" -> {
            if (pkgRef[0].isEmpty()) pkgRef[0] = attrs.getOrDefault("package", "");
          }
          case "uses-sdk" -> {
            if (!seenUsesSdk[0]) {
              seenUsesSdk[0] = true;
              targetSdk[0] = parseSdk(attrs.getOrDefault(NS + "targetSdkVersion", ""));
              minSdk[0] = parseSdk(attrs.getOrDefault(NS + "minSdkVersion", ""));
            }
          }
          case "application" -> {
            addSeed(seeds, pkgRef[0], attrs.get(NS + "appComponentFactory"));
            addSeed(seeds, pkgRef[0], attrs.get(NS + "name"));
          }
          case "provider" -> {
            inProvider = !"false".equals(attrs.get(NS + "enabled"));
            if (inProvider) addSeed(seeds, pkgRef[0], attrs.get(NS + "name"));
          }
          case "meta-data" -> {
            if (inProvider && "androidx.startup".equals(attrs.get(NS + "value"))) {
              addSeed(seeds, pkgRef[0], attrs.get(NS + "name"));
            }
          }
          case "activity", "activity-alias" -> {
            String n = attrs.getOrDefault(NS + "name", "");
            String full = fullName(pkgRef[0], n);
            ActivityEntry entry = new ActivityEntry(full, attrs.getOrDefault(NS + "enabled", ""), attrs.getOrDefault(NS + "targetActivity", ""));
            activities.putIfAbsent(full, entry);
            activityOrder.add(entry);
            curAct = entry;
          }
          case "intent-filter" -> {
            if (curAct != null) {
              curFilter = new FilterEntry(attrs.getOrDefault(NS + "priority", ""));
              curAct.filters.add(curFilter);
            }
          }
          case "action" -> {
            if (curFilter != null && "android.intent.action.MAIN".equals(attrs.get(NS + "name"))) {
              curFilter.main = true;
            }
          }
          case "category" -> {
            if (curFilter != null) {
              String c = attrs.get(NS + "name");
              if ("android.intent.category.LAUNCHER".equals(c)) curFilter.launcher = true;
              else if ("android.intent.category.HOME".equals(c)) curFilter.home = true;
              else if ("android.intent.category.DEFAULT".equals(c)) curFilter.dflt = true;
            }
          }
          default -> {}
        }
      }

      @Override
      public void endTag(String tag) {
        switch (tag) {
          case "activity", "activity-alias" -> { curAct = null; curFilter = null; }
          case "intent-filter" -> curFilter = null;
          case "provider" -> inProvider = false;
          default -> {}
        }
      }
    });

    if (needWrite && xml != null) {
      // Written beside it and renamed: processes started together (instrument.py --isolate) read it while others write.
      Path tmp = cache.resolve("AndroidManifest.xml" + Storage.tmpTag());
      Files.writeString(tmp, xml);
      Files.move(tmp, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    String pkg = pkgRef[0];
    // The MAIN/LAUNCHER activity or alias a device's launcher starts, the MAIN/HOME one its home app: the way
    // PackageManager orders matches, highest intent-filter priority first, then CATEGORY_DEFAULT, then manifest order.
    ActivityEntry launcher = null;
    long launcherRank = Long.MIN_VALUE;
    ActivityEntry home = null;
    long homeRank = Long.MIN_VALUE;
    for (ActivityEntry a : activityOrder) {
      long l = mainRank(a, true, arsc);
      if (l > launcherRank) { launcher = a; launcherRank = l; }
      long h = mainRank(a, false, arsc);
      if (h > homeRank) { home = a; homeRank = h; }
    }
    // A test run needs none: a library's test APK (KMP androidDeviceTest, a library's androidTest) is its own app.
    boolean testRun = System.getProperty("apksim.instrument") != null;
    String asked = System.getProperty("apksim.activity", "");
    ActivityEntry start = launcher;
    if (!asked.isEmpty()) {
      if (testRun) throw new LaunchError("--activity is for running the app: a test run starts what its tests start");
      start = find(pkg, asked, activities);
    } else if (start == null) {
      start = home;  // a home app with no launcher entry
    }
    if (start == null && !testRun) throw new LaunchError("no launcher activity in " + apk + " (choose one with --activity)");
    System.setProperty("apksim.apk", apk.toString());
    System.setProperty("apksim.cache", cache.toString());
    System.setProperty("apksim.package", pkg);
    // The target SDK compat changes follow (CompatTable): the manifest's, as a device reads it; a launch sets it anew.
    int target = targetSdk[0] > 0 ? targetSdk[0] : minSdk[0];
    CompatTable.configure(pkg, target > 0 ? target : 1);
    Gaps.reset();
    if (launcher != null) System.setProperty("apksim.launcher", launcher.name);
    StringBuilder aliases = new StringBuilder(",");
    for (Map.Entry<String, ActivityEntry> e : activities.entrySet()) {
      if (!e.getValue().targetActivity.isEmpty()) {
        aliases.append(e.getKey()).append('=').append(target(pkg, e.getValue())).append(',');
      }
    }
    System.setProperty("apksim.aliases", aliases.toString());
    if (start != null) {
      // The component the intent names: an alias stays the alias, as on a device (the app can tell which one started
      // it); activityClass gives the class to make.
      System.setProperty("apksim.startActivity", start.name);
      // Started as a device would: MAIN with its filter's category (launcher, home), else by component only (am start -n).
      System.setProperty("apksim.startCategory", hasMain(start, true) ? "LAUNCHER"
          : hasMain(start, false) ? "HOME" : "");
      addSeed(seeds, pkg, target(pkg, start));
    }
    startup = List.copyOf(seeds);
  }

  private static void addSeed(Set<String> seeds, String pkg, String name) {
    if (name == null || name.isEmpty()) return;
    seeds.add(fullName(pkg, name).replace('.', '/'));
  }

  /**
   * The class an activity component of the app makes: an activity-alias's targetActivity, else the name itself. Every
   * start of a component goes through it (Host.start), so an alias started by the app, `am start -n` or --activity
   * makes its target while its intent keeps the alias. A system property: AppInfo.load runs before the sandbox.
   */
  static String activityClass(String name) {
    String aliases = System.getProperty("apksim.aliases", ",");
    int at = aliases.indexOf("," + name + "=");
    if (at < 0) return name;
    int from = at + name.length() + 2;
    return aliases.substring(from, aliases.indexOf(',', from));
  }

  /** A launch the options or the APK rule out: the message says it all, no stack needed. */
  static final class LaunchError extends IOException {
    LaunchError(String message) {
      super(message);
    }
  }

  /**
   * --activity NAME: PKG/.Act or PKG/full.Name (am start -n's form), .Act, a full class name, or a simple name such as
   * SecondActivity when one activity has it. Any activity or alias in the manifest, exported or not.
   */
  private static ActivityEntry find(String pkg, String asked, Map<String, ActivityEntry> activities) throws LaunchError {
    String name = asked;
    int slash = asked.indexOf('/');
    if (slash >= 0) {
      String p = asked.substring(0, slash);
      if (!p.equals(pkg)) throw new LaunchError("--activity " + asked + ": the app's package is " + pkg + ", not " + p);
      name = asked.substring(slash + 1);
    }
    if (name.startsWith(".")) name = pkg + name;  // as ComponentName.unflattenFromString
    ActivityEntry a = activities.get(name);
    if (a != null) return a;
    if (slash < 0 && name.indexOf('.') < 0) {
      List<String> same = new ArrayList<>();
      for (String n : activities.keySet()) if (n.endsWith("." + name)) same.add(n);
      if (same.size() == 1) return activities.get(same.get(0));
      if (same.size() > 1) throw new LaunchError("--activity " + asked + " could be any of " + String.join(", ", same));
    }
    StringBuilder b = new StringBuilder("--activity " + asked + ": no activity " + name + " in " + pkg + "'s manifest. It has:");
    for (String n : activities.keySet()) b.append("\n  ").append(n.startsWith(pkg + ".") ? n.substring(pkg.length()) : n);
    throw new LaunchError(b.toString());
  }

  /** The class an activity or alias starts. */
  private static String target(String pkg, ActivityEntry a) {
    return a.targetActivity.isEmpty() ? a.name : fullName(pkg, a.targetActivity);
  }

  /** A uses-sdk attribute as a number (decimal or 0x hex), 0 when absent, the platform's SDK for a codename (a preview). */
  private static int parseSdk(String v) {
    if (v.isEmpty()) return 0;
    try {
      return Integer.decode(v.trim());
    } catch (NumberFormatException e) {
      return CompatTable.PLATFORM_SDK;
    }
  }

  /** A manifest class name made whole as the package parser does: ".X" and "X" are in the app's package. */
  private static String fullName(String pkg, String name) {
    if (name.startsWith(".")) return pkg + name;
    return name.indexOf('.') < 0 ? pkg + "." + name : name;
  }

  /** Whether one of the activity's intent filters has action MAIN and this category. */
  private static boolean hasMain(ActivityEntry activity, boolean launcher) {
    for (FilterEntry f : activity.filters) {
      if (f.main && (launcher ? f.launcher : f.home)) return true;
    }
    return false;
  }

  /**
   * The best rank of the activity's MAIN filters with this category (priority, then CATEGORY_DEFAULT), else Long.MIN_VALUE.
   * A disabled activity or alias is never a candidate: PackageManager leaves it out, and Host would start it anyway.
   */
  private static long mainRank(ActivityEntry activity, boolean launcher, Resources arsc) {
    Long enabled = "false".equals(activity.enabled) ? Long.valueOf(0) : intAttr(activity.enabled, arsc);
    if (enabled != null && enabled == 0) return Long.MIN_VALUE;  // an unresolvable value counts as the default, enabled
    long best = Long.MIN_VALUE;
    for (FilterEntry f : activity.filters) {
      if (!f.main || !(launcher ? f.launcher : f.home)) continue;
      Long v = intAttr(f.priority, arsc);
      int priority = v == null ? 0 : v.intValue();  // unreadable or unresolvable (a framework resource): 0
      long rank = priority * 2L + (f.dflt ? 1 : 0);
      best = Math.max(best, rank);
    }
    return best;
  }

  /** An integer attribute's value: a literal, or a resource of the APK (Axml prints its id as `@0x7f...`); else null. */
  private static Long intAttr(String s, Resources arsc) {
    if (s.isEmpty()) return null;
    try {
      if (s.startsWith("@0x")) return arsc.intValue((int) Long.decode(s.substring(1)).longValue());
      return Long.decode(s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The APK's resources.arsc, read on first use: most launches name no resource. A missing or bad one is absent. */
  private static final class Resources {
    private final Path apk;
    private byte[] data;
    private boolean tried;

    Resources(Path apk) {
      this.apk = apk;
    }

    Long intValue(int resId) {
      if (!tried) {
        tried = true;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
          java.util.zip.ZipEntry e = zip.getEntry("resources.arsc");
          if (e != null) {
            try (InputStream r = zip.getInputStream(e)) {
              data = r.readAllBytes();
            }
          }
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
          data = null;
        }
      }
      return data == null ? null : Arsc.intValue(data, resId);
    }
  }
}
