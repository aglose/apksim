package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** What the runtime needs from AndroidManifest.xml. Published as system properties for the sandbox. */
final class AppInfo {
  private static final String NS = "android:";

  private AppInfo() {}

  static void load(Path apk, Path cache) throws Exception {
    Files.createDirectories(cache);
    String xml;
    try (ZipFile zip = new ZipFile(apk.toFile()); InputStream in = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
      xml = Axml.decode(in.readAllBytes());
    }
    Path manifest = cache.resolve("AndroidManifest.xml");
    // Written beside it and renamed: processes started together (instrument.py --isolate) read it while others write.
    Path tmp = cache.resolve("AndroidManifest.xml" + Storage.tmpTag());
    Files.writeString(tmp, xml);
    Files.move(tmp, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    String pkg = doc.getDocumentElement().getAttribute("package");
    // The MAIN/LAUNCHER activity or alias a device's launcher starts, the MAIN/HOME one its home app: the way
    // PackageManager orders matches, highest intent-filter priority first, then CATEGORY_DEFAULT, then manifest order.
    Element launcher = null;
    long launcherRank = Long.MIN_VALUE;
    Element home = null;
    long homeRank = Long.MIN_VALUE;
    Map<String, Element> activities = new LinkedHashMap<>();  // full name -> element, aliases too
    for (String tag : new String[] {"activity", "activity-alias"}) {
      NodeList list = doc.getElementsByTagName(tag);
      for (int i = 0; i < list.getLength(); i++) {
        Element a = (Element) list.item(i);
        activities.putIfAbsent(fullName(pkg, a.getAttribute(NS + "name")), a);
        long l = mainRank(a, "android.intent.category.LAUNCHER");
        if (l > launcherRank) { launcher = a; launcherRank = l; }
        long h = mainRank(a, "android.intent.category.HOME");
        if (h > homeRank) { home = a; homeRank = h; }
      }
    }
    // A test run needs none: a library's test APK (KMP androidDeviceTest, a library's androidTest) is its own app.
    boolean testRun = System.getProperty("apksim.instrument") != null;
    String asked = System.getProperty("apksim.activity", "");
    Element start = launcher;
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
    if (launcher != null) System.setProperty("apksim.launcher", fullName(pkg, launcher.getAttribute(NS + "name")));
    StringBuilder aliases = new StringBuilder(",");
    for (Map.Entry<String, Element> e : activities.entrySet()) {
      if (!e.getValue().getAttribute(NS + "targetActivity").isEmpty()) {
        aliases.append(e.getKey()).append('=').append(target(pkg, e.getValue())).append(',');
      }
    }
    System.setProperty("apksim.aliases", aliases.toString());
    if (start != null) {
      // The component the intent names: an alias stays the alias, as on a device (the app can tell which one started
      // it); activityClass gives the class to make.
      System.setProperty("apksim.startActivity", fullName(pkg, start.getAttribute(NS + "name")));
      // Started as a device would: MAIN with its filter's category (launcher, home), else by component only (am start -n).
      System.setProperty("apksim.startCategory", hasMain(start, "android.intent.category.LAUNCHER") ? "LAUNCHER"
          : hasMain(start, "android.intent.category.HOME") ? "HOME" : "");
    }
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
  private static Element find(String pkg, String asked, Map<String, Element> activities) throws LaunchError {
    String name = asked;
    int slash = asked.indexOf('/');
    if (slash >= 0) {
      String p = asked.substring(0, slash);
      if (!p.equals(pkg)) throw new LaunchError("--activity " + asked + ": the app's package is " + pkg + ", not " + p);
      name = asked.substring(slash + 1);
    }
    if (name.startsWith(".")) name = pkg + name;  // as ComponentName.unflattenFromString
    Element a = activities.get(name);
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
  private static String target(String pkg, Element a) {
    String name = a.getAttribute(NS + "targetActivity");
    if (name.isEmpty()) name = a.getAttribute(NS + "name");
    return fullName(pkg, name);
  }

  /** A manifest class name made whole as the package parser does: ".X" and "X" are in the app's package. */
  private static String fullName(String pkg, String name) {
    if (name.startsWith(".")) return pkg + name;
    return name.indexOf('.') < 0 ? pkg + "." + name : name;
  }

  /** Whether one of the activity's intent filters has action MAIN and this category. */
  private static boolean hasMain(Element activity, String category) {
    NodeList filters = activity.getElementsByTagName("intent-filter");
    for (int i = 0; i < filters.getLength(); i++) {
      Element f = (Element) filters.item(i);
      if (has(f, "action", "android.intent.action.MAIN") && has(f, "category", category)) return true;
    }
    return false;
  }

  /**
   * The best rank of the activity's MAIN filters with this category (priority, then CATEGORY_DEFAULT), else Long.MIN_VALUE.
   * A disabled activity or alias is never a candidate: PackageManager leaves it out, and Host would start it anyway.
   */
  private static long mainRank(Element activity, String category) {
    if ("false".equals(activity.getAttribute(NS + "enabled"))) return Long.MIN_VALUE;
    long best = Long.MIN_VALUE;
    NodeList filters = activity.getElementsByTagName("intent-filter");
    for (int i = 0; i < filters.getLength(); i++) {
      Element f = (Element) filters.item(i);
      if (!has(f, "action", "android.intent.action.MAIN") || !has(f, "category", category)) continue;
      int priority = 0;
      try {
        String p = f.getAttribute(NS + "priority");
        if (!p.isEmpty()) priority = (int) Long.decode(p).longValue();
      } catch (NumberFormatException e) {
        // an unreadable priority counts as 0
      }
      long rank = priority * 2L + (has(f, "category", "android.intent.category.DEFAULT") ? 1 : 0);
      best = Math.max(best, rank);
    }
    return best;
  }

  private static boolean has(Element filter, String tag, String value) {
    NodeList l = filter.getElementsByTagName(tag);
    for (int i = 0; i < l.getLength(); i++) {
      if (value.equals(((Element) l.item(i)).getAttribute(NS + "name"))) return true;
    }
    return false;
  }
}
