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
    String launcher = null;  // the first MAIN/LAUNCHER activity, as a device's launcher starts it
    String home = null;  // the first MAIN/HOME one, as a device starts its home app
    Map<String, Element> activities = new LinkedHashMap<>();  // full name -> element, aliases too
    for (String tag : new String[] {"activity", "activity-alias"}) {
      NodeList list = doc.getElementsByTagName(tag);
      for (int i = 0; i < list.getLength(); i++) {
        Element a = (Element) list.item(i);
        activities.putIfAbsent(fullName(pkg, a.getAttribute(NS + "name")), a);
        if (launcher == null && hasMain(a, "android.intent.category.LAUNCHER")) launcher = target(pkg, a);
        if (home == null && hasMain(a, "android.intent.category.HOME")) home = target(pkg, a);
      }
    }
    // A test run needs none: a library's test APK (KMP androidDeviceTest, a library's androidTest) is its own app.
    boolean testRun = System.getProperty("apksim.instrument") != null;
    String asked = System.getProperty("apksim.activity", "");
    String start = launcher;
    String category = "LAUNCHER";
    if (!asked.isEmpty()) {
      if (testRun) throw new LaunchError("--activity is for running the app: a test run starts what its tests start");
      Element a = find(pkg, asked, activities);
      start = target(pkg, a);
      // Started as a device would: MAIN with its filter's category (launcher, home), else by component only (am start -n).
      category = hasMain(a, "android.intent.category.LAUNCHER") ? "LAUNCHER"
          : hasMain(a, "android.intent.category.HOME") ? "HOME" : "";
    } else if (start == null && home != null) {
      start = home;  // a home app with no launcher entry
      category = "HOME";
    }
    if (start == null && !testRun) throw new LaunchError("no launcher activity in " + apk + " (choose one with --activity)");
    System.setProperty("apksim.apk", apk.toString());
    System.setProperty("apksim.cache", cache.toString());
    System.setProperty("apksim.package", pkg);
    if (launcher != null) System.setProperty("apksim.launcher", launcher);
    if (start != null) {
      System.setProperty("apksim.startActivity", start);
      System.setProperty("apksim.startCategory", category);
    }
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

  private static boolean has(Element filter, String tag, String value) {
    NodeList l = filter.getElementsByTagName(tag);
    for (int i = 0; i < l.getLength(); i++) {
      if (value.equals(((Element) l.item(i)).getAttribute(NS + "name"))) return true;
    }
    return false;
  }
}
