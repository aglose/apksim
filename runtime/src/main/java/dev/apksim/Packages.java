package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Packages installed over adb (`adb install`, `pm install`) beside the running app. One instance runs one app, so
 * what can be installed is an APK that only brings an instrumentation (a tool's on-device server, such as the Android
 * CLI's layout server), which `am instrument` then runs in this process (LiveInstrumentation). Installing the running
 * app's own APK again succeeds when it is the same file; any other app is refused with the reason.
 */
final class Packages {
  /** An installed package: its APK (copied into this instance's scratch folder) and its first instrumentation. */
  record Installed(String pkg, Path apk, String instrumentation, String target) {}

  private static final Map<String, Installed> INSTALLED = new ConcurrentHashMap<>();

  private Packages() {}

  static Installed get(String pkg) {
    return INSTALLED.get(pkg);
  }

  static List<Installed> all() {
    return new ArrayList<>(INSTALLED.values());
  }

  static boolean uninstall(String pkg) {
    Installed i = INSTALLED.remove(pkg);
    if (i == null) return false;
    try {
      Files.deleteIfExists(i.apk);
    } catch (IOException e) {
      // A running instrumentation still has it mapped; the scratch folder goes when the instance exits.
    }
    return true;
  }

  /** Installs an APK file, as `pm install` does, and returns pm's answer: "Success" or "Failure [REASON: why]". */
  static String install(Path apk) {
    String[] info;
    try {
      info = manifest(apk);
    } catch (Exception e) {
      return "Failure [INSTALL_PARSE_FAILED_NOT_APK: " + e.getMessage() + "]";
    }
    String pkg = info[0];
    String running = System.getProperty("apksim.package");
    if (pkg.equals(running)) {
      String appApk = System.getProperty("apksim.apk");
      try {
        if (appApk != null && Files.mismatch(apk, Path.of(appApk)) == -1) return "Success";
      } catch (IOException e) {
        // Treated as a different build.
      }
      return "Failure [INSTALL_FAILED_INTERNAL_ERROR: apksim runs one app per instance and " + pkg
          + " is running from another build; relaunch apksim with the new APK]";
    }
    if (info[1] == null) {
      return "Failure [INSTALL_FAILED_INTERNAL_ERROR: apksim runs one app per instance (" + running
          + "); beside it, it installs only APKs that bring an instrumentation]";
    }
    try {
      Path dir = Path.of(System.getProperty("java.io.tmpdir"), "packages");
      Files.createDirectories(dir);
      Path to = dir.resolve(pkg + "-" + System.nanoTime() + ".apk");
      Files.copy(apk, to, StandardCopyOption.REPLACE_EXISTING);
      Installed old = INSTALLED.put(pkg, new Installed(pkg, to, info[1], info[2]));
      if (old != null && LiveInstrumentation.running(pkg) == null) Files.deleteIfExists(old.apk);
      System.err.println("[apksim] adb: installed " + pkg + " (instrumentation " + info[1] + ")");
      return "Success";
    } catch (IOException e) {
      return "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: " + e.getMessage() + "]";
    }
  }

  /** {package, instrumentation class or null, its target package or null}. */
  private static String[] manifest(Path apk) throws Exception {
    String xml;
    try (ZipFile zip = new ZipFile(apk.toFile())) {
      var entry = zip.getEntry("AndroidManifest.xml");
      if (entry == null) throw new IOException("no AndroidManifest.xml");
      try (InputStream in = zip.getInputStream(entry)) {
        xml = Axml.decode(in.readAllBytes());
      }
    }
    Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    String pkg = root.getAttribute("package");
    NodeList list = root.getElementsByTagName("instrumentation");
    if (list.getLength() == 0) return new String[] {pkg, null, null};
    Element e = (Element) list.item(0);
    String name = e.getAttribute("android:name");
    if (name.startsWith(".")) name = pkg + name;
    else if (!name.contains(".")) name = pkg + "." + name;
    return new String[] {pkg, name, e.getAttribute("android:targetPackage")};
  }
}
