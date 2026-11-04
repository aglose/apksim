package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Path tmp = cache.resolve("AndroidManifest.xml." + ProcessHandle.current().pid() + ".tmp");
    Files.writeString(tmp, xml);
    Files.move(tmp, manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    String pkg = doc.getDocumentElement().getAttribute("package");
    String launcher = null;
    for (String tag : new String[] {"activity", "activity-alias"}) {
      NodeList list = doc.getElementsByTagName(tag);
      for (int i = 0; launcher == null && i < list.getLength(); i++) {
        Element a = (Element) list.item(i);
        if (!isLauncher(a)) continue;
        String name = a.getAttribute(NS + "targetActivity");
        if (name.isEmpty()) name = a.getAttribute(NS + "name");
        launcher = name.startsWith(".") ? pkg + name : name;
      }
    }
    // A test run needs none: a library's test APK (KMP androidDeviceTest, a library's androidTest) is its own app.
    boolean testRun = System.getProperty("apksim.instrument") != null;
    if (launcher == null && !testRun) throw new IOException("no launcher activity in " + apk);
    System.setProperty("apksim.apk", apk.toString());
    System.setProperty("apksim.cache", cache.toString());
    System.setProperty("apksim.package", pkg);
    if (launcher != null) System.setProperty("apksim.launcher", launcher);
  }

  private static boolean isLauncher(Element activity) {
    NodeList filters = activity.getElementsByTagName("intent-filter");
    for (int i = 0; i < filters.getLength(); i++) {
      Element f = (Element) filters.item(i);
      if (has(f, "action", "android.intent.action.MAIN") && has(f, "category", "android.intent.category.LAUNCHER")) return true;
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
