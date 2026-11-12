package dev.apksim.skin;

import dev.apksim.DeviceSpec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * `apksim skin`: the local skin store and its commands (docs/skins.md). Runs on a plain JVM with only apksim.jar.
 * The store is `~/.apksim/skins` (`APKSIM_SKINS_DIR` chooses another), and `APKSIM_SKINS` names more folders of skins
 * (colon-separated) that are searched after it.
 */
public final class Skins {
  private static final String USAGE = """
      usage: apksim skin add NAME --frame frame.png [--screen X,Y,W,H | --screen auto] [--corner-radius PX]
                              [--device NAME|WxH@D] [--kind KIND] [--title T] [--cutout hole:X,Y,R]
                              [--mask mask.png] [--frame-land F] [--license L] [--author A] [--source-url U] [--force]
             apksim skin add --from DIR|ZIP|URL [--force]
             apksim skin list [--json]
             apksim skin show NAME [--json]
             apksim skin preview NAME [--out PNG]
             apksim skin remove NAME
             apksim skin export NAME [--out ZIP]""";
  private static final long MAX_UNPACKED = 64L << 20;

  /** A reason to stop, printed as "apksim skin: MESSAGE" and the exit status. */
  private static final class Fail extends Exception {
    final int status;

    Fail(String message, int status) {
      super(message);
      this.status = status;
    }

    Fail(String message) {
      this(message, 1);
    }
  }

  public static void main(String[] args) {
    System.setProperty("java.awt.headless", "true");
    try {
      run(args);
    } catch (Fail f) {
      System.err.println(f.status == 2 && f.getMessage().startsWith("usage") ? f.getMessage() : "apksim skin: " + f.getMessage());
      System.exit(f.status);
    } catch (IOException e) {
      System.err.println("apksim skin: " + e.getMessage());
      System.exit(1);
    }
  }

  /**
   * The store. A running instance from a standby has the standby's environment, not the launcher's, so the launcher
   * passes APKSIM_SKINS_DIR and APKSIM_SKINS on as the properties apksim.skinsDir and apksim.skinsShared.
   */
  public static Path store() {
    String d = setting("apksim.skinsDir", "APKSIM_SKINS_DIR");
    if (d != null && !d.isEmpty()) return Path.of(d);
    return Path.of(System.getProperty("user.home"), ".apksim", "skins");
  }

  public static List<Path> sharedDirs() {
    List<Path> l = new ArrayList<>();
    String s = setting("apksim.skinsShared", "APKSIM_SKINS");
    if (s != null) for (String p : s.split(":")) if (!p.isEmpty()) l.add(Path.of(p));
    return l;
  }

  private static String setting(String property, String env) {
    String v = System.getProperty(property);
    return v != null ? v : System.getenv(env);
  }

  /** The skin of that name (store first, then the shared folders); the IOException says why there is none. */
  public static Skin lookup(String name) throws IOException {
    try {
      return find(name);
    } catch (Fail f) {
      throw new IOException(f.getMessage());
    }
  }

  /** Every skin that reads, store first, a name once; those that do not read are added to `bad` (if given). */
  public static List<Skin> all(List<String> bad) throws IOException {
    List<Skin> skins = new ArrayList<>();
    List<String> seen = new ArrayList<>();
    List<Path> dirs = new ArrayList<>();
    dirs.add(store());
    dirs.addAll(sharedDirs());
    for (int i = 0; i < dirs.size(); i++) {
      if (!Files.isDirectory(dirs.get(i))) continue;
      List<Path> subs;
      try (Stream<Path> st = Files.list(dirs.get(i))) {
        subs = st.filter(p -> !p.getFileName().toString().startsWith(".") && Files.isRegularFile(p.resolve("skin.json")))
            .sorted().toList();
      }
      for (Path p : subs) {
        String n = p.getFileName().toString();
        if (seen.contains(n)) continue;
        seen.add(n);
        try {
          Skin s = Skin.read(p);
          s.origin = i == 0 ? "local" : "shared";
          skins.add(s);
        } catch (IOException e) {
          if (bad != null) bad.add(n + ": " + e.getMessage());
        }
      }
    }
    return skins;
  }

  private static void run(String[] a) throws Fail, IOException {
    if (a.length == 0 || a[0].equals("-h") || a[0].equals("--help")) {
      System.out.println(USAGE);
      if (a.length == 0) System.exit(2);
      return;
    }
    String cmd = a[0];
    List<String> pos = new ArrayList<>();
    Map<String, String> opt = new LinkedHashMap<>();
    for (int i = 1; i < a.length; i++) {
      if (a[i].startsWith("--")) {
        String k = a[i].substring(2);
        if (k.equals("force") || k.equals("json")) opt.put(k, "true");
        else if (i + 1 < a.length) opt.put(k, a[++i]);
        else throw new Fail("--" + k + " needs a value\n" + USAGE, 2);
      } else {
        pos.add(a[i]);
      }
    }
    switch (cmd) {
      case "add" -> add(pos, opt);
      case "list" -> list(opt.containsKey("json"));
      case "show" -> show(one(pos, "show"), opt.containsKey("json"));
      case "preview" -> preview(one(pos, "preview"), opt.get("out"));
      case "remove" -> remove(one(pos, "remove"));
      case "export" -> export(one(pos, "export"), opt.get("out"));
      default -> throw new Fail("usage: unknown command " + cmd + "\n" + USAGE, 2);
    }
  }

  private static String one(List<String> pos, String cmd) throws Fail {
    if (pos.size() != 1) throw new Fail("usage: apksim skin " + cmd + " NAME", 2);
    return pos.get(0);
  }

  // ---- finding skins

  private static Skin find(String name) throws Fail {
    if (!Skin.NAME.matcher(name).matches()) throw new Fail("\"" + name + "\" is not a skin name (letters a-z, digits and - only)");
    List<Path> dirs = new ArrayList<>();
    dirs.add(store());
    dirs.addAll(sharedDirs());
    for (int i = 0; i < dirs.size(); i++) {
      Path d = dirs.get(i).resolve(name);
      if (!Files.isRegularFile(d.resolve("skin.json"))) continue;
      try {
        Skin s = Skin.read(d);
        s.origin = i == 0 ? "local" : "shared";
        return s;
      } catch (IOException e) {
        throw new Fail("skin " + name + " (" + d + "): " + e.getMessage());
      }
    }
    throw new Fail("no skin named " + name + " (apksim skin list)");
  }

  private static void list(boolean json) throws IOException {
    List<String> bad = new ArrayList<>();
    List<Skin> skins = all(bad);
    if (json) {
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < skins.size(); i++) b.append(i > 0 ? "," : "").append(skins.get(i).describeJson());
      System.out.println(b.append("]"));
    } else {
      if (skins.isEmpty()) System.out.println("no skins (apksim skin add, docs/skins.md); the store is " + store());
      else System.out.printf("%-20s %-24s %-18s %-9s %s%n", "NAME", "TITLE", "DEVICE", "KIND", "FROM");
      for (Skin s : skins) {
        String kind;
        try {
          kind = s.effectiveKind();
        } catch (IOException e) {
          kind = "?";
        }
        System.out.printf("%-20s %-24s %-18s %-9s %s%n", s.name, s.title, s.device, kind, s.origin);
      }
    }
    for (String b : bad) System.err.println("apksim skin: skipped " + b);
  }

  // ---- show, preview

  private static BufferedImage frameOf(Skin s) throws Fail {
    try {
      return ImageIO.read(s.dir.resolve(s.frame).toFile());
    } catch (IOException e) {
      throw new Fail("cannot read " + s.frame + ": " + e.getMessage());
    }
  }

  private static void show(String name, boolean json) throws Fail, IOException {
    Skin s = find(name);
    List<String> problems = s.check();
    String warning = null;
    BufferedImage fr = problems.isEmpty() ? frameOf(s) : null;
    double open = -1;
    if (fr != null) {
      open = Frames.openShare(fr, s);
      if (open < 0.9) {
        warning = String.format("the frame covers %.0f%% of the screen rectangle: the screen position may be wrong (apksim skin preview %s)", (1 - open) * 100, s.name);
      }
    }
    if (json) {
      String d = s.describeJson();
      StringBuilder b = new StringBuilder(d.substring(0, d.length() - 1)).append(",\"problems\":[");
      for (int i = 0; i < problems.size(); i++) b.append(i > 0 ? "," : "").append(Skin.q(problems.get(i)));
      b.append("]");
      if (warning != null) b.append(",\"warning\":").append(Skin.q(warning));
      System.out.println(b.append("}"));
    } else {
      System.out.print(Files.readString(s.dir.resolve("skin.json")));
      System.out.println("\nfrom:    " + s.dir + " (" + s.origin + ")");
      try {
        DeviceSpec d = s.spec();
        System.out.println("device:  " + s.device + " = " + d.widthDp() + "x" + d.heightDp() + " dp at density " + d.density() + ", "
            + d.widthPx() + "x" + d.heightPx() + " px, kind " + s.effectiveKind());
      } catch (IOException e) {
        System.out.println("device:  " + e.getMessage());
      }
      if (fr != null) System.out.println("frame:   " + s.frame + " " + fr.getWidth() + "x" + fr.getHeight() + ", " + String.format("%.0f%%", open * 100) + " of the screen is open");
      if (warning != null) System.out.println("warning: " + warning);
      System.out.println(problems.isEmpty() ? "check:   ok" : "check:   " + problems.size() + " problem(s)");
      for (String p : problems) System.out.println("  - " + p);
    }
    if (!problems.isEmpty()) System.exit(1);
  }

  private static void preview(String name, String out) throws Fail, IOException {
    Skin s = find(name);
    List<String> problems = s.check();
    if (!problems.isEmpty()) throw new Fail("skin " + name + " is not valid: " + String.join("; ", problems));
    BufferedImage fr = frameOf(s);
    BufferedImage mk = s.mask == null ? null : ImageIO.read(s.dir.resolve(s.mask).toFile());
    DeviceSpec d = s.spec();
    String label = s.device.length() < 14 ? s.device + " " + d.widthPx() + "x" + d.heightPx() : d.widthPx() + "x" + d.heightPx();
    BufferedImage img = Frames.preview(fr, mk, s, label);
    Path f = Path.of(out != null ? out : name + "-preview.png");
    ImageIO.write(img, "png", f.toFile());
    System.out.println("wrote " + f.toAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + "): the pattern's red, green, blue and yellow squares mark the screen's corners");
  }

  // ---- remove, export

  private static void remove(String name) throws Fail, IOException {
    if (!Skin.NAME.matcher(name).matches()) throw new Fail("\"" + name + "\" is not a skin name");
    Path d = store().resolve(name);
    if (!Files.isRegularFile(d.resolve("skin.json"))) {
      boolean shared = false;
      for (Path p : sharedDirs()) shared |= Files.isRegularFile(p.resolve(name).resolve("skin.json"));
      throw new Fail(shared ? "skin " + name + " is in a shared folder (APKSIM_SKINS), not the store: remove it there" : "no skin named " + name + " in " + store());
    }
    deleteTree(d);
    System.out.println("removed " + name);
  }

  private static void export(String name, String out) throws Fail, IOException {
    Skin s = find(name);
    Path z = Path.of(out != null ? out : name + ".zip");
    List<Path> files;
    try (Stream<Path> st = Files.list(s.dir)) {
      files = st.filter(Files::isRegularFile).sorted().toList();
    }
    Path tmp = z.toAbsolutePath().resolveSibling(z.getFileName() + ".tmp");
    try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zo = new ZipOutputStream(os)) {
      for (Path f : files) {
        zo.putNextEntry(new ZipEntry(f.getFileName().toString()));
        Files.copy(f, zo);
        zo.closeEntry();
      }
    }
    Files.move(tmp, z, StandardCopyOption.REPLACE_EXISTING);
    System.out.println("wrote " + z.toAbsolutePath() + " (" + files.size() + " files)");
  }

  // ---- add

  private static void add(List<String> pos, Map<String, String> o) throws Fail, IOException {
    Files.createDirectories(store());
    Path tmp = Files.createTempDirectory(store(), ".add-");
    try {
      String name;
      if (o.containsKey("from")) {
        if (!pos.isEmpty()) throw new Fail("usage: apksim skin add --from DIR|ZIP|URL (the name is in its skin.json)", 2);
        fetch(o.get("from"), tmp);
        Skin s;
        try {
          s = Skin.read(tmp);
        } catch (IOException e) {
          throw new Fail(o.get("from") + ": " + e.getMessage());
        }
        name = s.name;
        List<String> problems = s.check();
        if (!problems.isEmpty()) throw new Fail("refused " + o.get("from") + ": " + String.join("; ", problems));
        System.out.println("skin " + name + ": " + s.title + ", device " + s.device + ", screen " + s.x + "," + s.y + " " + s.width + "x" + s.height);
      } else {
        if (pos.size() != 1) throw new Fail("usage: apksim skin add NAME --frame frame.png [--screen auto|X,Y,W,H] --device D (or --from DIR|ZIP|URL)", 2);
        name = pos.get(0);
        build(name, o, tmp);
      }
      if (!Skin.NAME.matcher(name).matches()) throw new Fail("\"" + name + "\" is not a skin name (letters a-z, digits and - only)");
      Path dest = store().resolve(name);
      if (Files.exists(dest)) {
        if (!o.containsKey("force")) throw new Fail("skin " + name + " exists (" + dest + "); --force replaces it");
        deleteTree(dest);
      }
      Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
      System.out.println("added " + name + " to " + dest);
    } finally {
      if (Files.exists(tmp)) deleteTree(tmp);
    }
  }

  private static void build(String name, Map<String, String> o, Path tmp) throws Fail, IOException {
    if (!Skin.NAME.matcher(name).matches()) throw new Fail("\"" + name + "\" is not a skin name (letters a-z, digits and - only)");
    if (!o.containsKey("frame")) throw new Fail("usage: --frame frame.png is required", 2);
    if (!o.containsKey("screen")) throw new Fail("usage: --screen X,Y,W,H or --screen auto is required", 2);
    Skin s = new Skin();
    s.name = name;
    s.title = o.getOrDefault("title", name);
    s.dir = tmp;
    Path src = Path.of(o.get("frame"));
    BufferedImage fr = readImage(src, "frame");
    s.frame = "frame.png";
    copyPng(src, fr, tmp.resolve("frame.png"));
    if (o.containsKey("frame-land")) {
      Path l = Path.of(o.get("frame-land"));
      copyPng(l, readImage(l, "frame-land"), tmp.resolve("frame-land.png"));
      s.frameLand = "frame-land.png";
    }
    if (o.containsKey("mask")) {
      Path m = Path.of(o.get("mask"));
      copyPng(m, readImage(m, "mask"), tmp.resolve("mask.png"));
      s.mask = "mask.png";
    }
    String scr = o.get("screen");
    boolean auto = scr.equals("auto");
    int radius = 0;
    if (auto) {
      Frames.Hole h = Frames.findHole(fr);
      if (h == null) {
        throw new Fail("--screen auto: no hole found in " + src + (fr.getColorModel().hasAlpha()
            ? " (no fully transparent region inside the frame: the screen must be transparent, and closed by the frame)"
            : " (the image has no alpha channel, so it has no transparent screen)"));
      }
      s.x = h.x();
      s.y = h.y();
      s.width = h.width();
      s.height = h.height();
      radius = h.cornerRadius();
      System.out.println("found the screen at " + s.x + "," + s.y + " " + s.width + "x" + s.height + " with corner radius " + radius
          + " (check it with: apksim skin preview " + name + ")");
    } else {
      String[] p = scr.split(",");
      if (p.length != 4) throw new Fail("--screen " + scr + ": expected X,Y,W,H or auto");
      try {
        s.x = Integer.parseInt(p[0].trim());
        s.y = Integer.parseInt(p[1].trim());
        s.width = Integer.parseInt(p[2].trim());
        s.height = Integer.parseInt(p[3].trim());
      } catch (NumberFormatException e) {
        throw new Fail("--screen " + scr + ": expected four whole numbers X,Y,W,H or auto");
      }
    }
    s.cornerRadius = o.containsKey("corner-radius") ? intOpt(o, "corner-radius") : radius;
    if (!o.containsKey("device")) {
      throw new Fail("--device is required: the device whose pixels this " + s.width + "x" + s.height + " px screen shows. Candidates:\n"
          + suggest(s.width, s.height)
          + "  apksim skin add " + name + " ... --device <one of these>", 2);
    }
    s.device = o.get("device");
    s.kind = o.get("kind");
    if (o.containsKey("cutout")) s.cutout = cutout(o.get("cutout"));
    Map<String, Object> source = new LinkedHashMap<>();
    if (o.containsKey("source-url")) source.put("url", o.get("source-url"));
    if (o.containsKey("license")) source.put("license", o.get("license"));
    if (o.containsKey("author")) source.put("author", o.get("author"));
    s.source = source.isEmpty() ? null : source;
    List<String> problems = s.check();
    if (!problems.isEmpty()) throw new Fail("refused " + name + ": " + String.join("; ", problems));
    Files.writeString(tmp.resolve("skin.json"), s.toJson());
    double open = Frames.openShare(fr, s);
    if (open < 0.9) System.out.println(String.format("warning: the frame covers %.0f%% of the screen rectangle: check the position with skin preview", (1 - open) * 100));
    DeviceSpec d = s.spec();
    System.out.println("skin " + name + ": device " + s.device + " (" + d.widthDp() + "x" + d.heightDp() + " dp, " + d.widthPx() + "x" + d.heightPx() + " px), screen "
        + s.x + "," + s.y + " " + s.width + "x" + s.height + ", corner radius " + s.cornerRadius);
  }

  /** Device values for a screen of this many pixels, at the two densities phones come in. */
  private static String suggest(int w, int h) {
    StringBuilder b = new StringBuilder();
    for (float dens : new float[] {2.625f, 2f}) {
      int wd = Math.round(w / dens), hd = Math.round(h / dens);
      if (wd >= 160 && hd >= 160) b.append("  ").append(wd).append('x').append(hd).append('@').append(dens == 2f ? "2" : "2.625").append('\n');
    }
    return b.toString();
  }

  private static int intOpt(Map<String, String> o, String k) throws Fail {
    try {
      return Integer.parseInt(o.get(k));
    } catch (NumberFormatException e) {
      throw new Fail("--" + k + " " + o.get(k) + ": expected a whole number of pixels");
    }
  }

  private static Map<String, Object> cutout(String v) throws Fail {
    String[] t = v.split(":", 2);
    String[] n = t.length == 2 ? t[1].split(",") : new String[0];
    if (!t[0].equals("hole") || n.length != 3) throw new Fail("--cutout " + v + ": expected hole:X,Y,RADIUS");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "hole");
    try {
      m.put("x", Integer.parseInt(n[0].trim()));
      m.put("y", Integer.parseInt(n[1].trim()));
      m.put("radius", Integer.parseInt(n[2].trim()));
    } catch (NumberFormatException e) {
      throw new Fail("--cutout " + v + ": expected whole numbers");
    }
    return m;
  }

  private static BufferedImage readImage(Path f, String what) throws Fail {
    if (!Files.isRegularFile(f)) throw new Fail(what + " image " + f + " does not exist");
    try {
      BufferedImage img = ImageIO.read(f.toFile());
      if (img == null) throw new Fail(what + " image " + f + " is not a PNG, JPEG or GIF");
      return img;
    } catch (IOException e) {
      throw new Fail(what + " image " + f + " cannot be read: " + e.getMessage());
    }
  }

  private static void copyPng(Path src, BufferedImage img, Path dest) throws IOException {
    byte[] head = new byte[8];
    try (InputStream in = Files.newInputStream(src)) {
      if (in.readNBytes(head, 0, 8) == 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
        Files.copy(src, dest);
        return;
      }
    }
    ImageIO.write(img, "png", dest.toFile());
  }

  // ---- add --from

  /** Puts a skin folder's, a zip's or a URL's files flat into dir. */
  private static void fetch(String from, Path dir) throws Fail, IOException {
    Path zip = null;
    boolean temp = false;
    try {
      if (from.startsWith("https://") || from.startsWith("http://")) {
        zip = Files.createTempFile("apksim-skin", ".zip");
        temp = true;
        download(from, zip);
      } else {
        zip = Path.of(from);
      }
      if (Files.isDirectory(zip)) {
        copyFlat(zip, dir);
      } else if (Files.isRegularFile(zip)) {
        unzip(zip, dir);
      } else {
        throw new Fail(from + " is not a folder, a zip or a URL");
      }
    } finally {
      if (temp && zip != null) Files.deleteIfExists(zip);
    }
  }

  private static void download(String url, Path dest) throws Fail, IOException {
    try {
      HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
      HttpResponse<InputStream> r = c.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "apksim-skin").build(),
          HttpResponse.BodyHandlers.ofInputStream());
      if (r.statusCode() != 200) throw new Fail("downloading " + url + ": HTTP " + r.statusCode());
      try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(dest)) {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
          total += n;
          if (total > MAX_UNPACKED) throw new Fail("downloading " + url + ": more than 64 MB");
          out.write(buf, 0, n);
        }
      }
    } catch (InterruptedException e) {
      throw new Fail("downloading " + url + ": interrupted");
    } catch (IllegalArgumentException e) {
      throw new Fail("bad URL " + url);
    }
  }

  private static void copyFlat(Path src, Path dir) throws IOException, Fail {
    Path root = Files.isRegularFile(src.resolve("skin.json")) ? src : onlySub(src);
    long total = 0;
    try (Stream<Path> st = Files.list(root)) {
      for (Path f : st.filter(Files::isRegularFile).toList()) {
        total += Files.size(f);
        if (total > MAX_UNPACKED) throw new Fail(src + " is more than 64 MB");
        Files.copy(f, dir.resolve(f.getFileName().toString()));
      }
    }
  }

  /** A folder holding exactly one folder that has a skin.json (a zip made of the folder itself). */
  private static Path onlySub(Path src) throws IOException, Fail {
    try (Stream<Path> st = Files.list(src)) {
      List<Path> subs = st.filter(p -> Files.isRegularFile(p.resolve("skin.json"))).toList();
      if (subs.size() != 1) throw new Fail(src + " has no skin.json");
      return subs.get(0);
    }
  }

  private static void unzip(Path zip, Path dir) throws IOException, Fail {
    Path stage = Files.createTempDirectory(dir.getParent(), ".unzip-");
    try {
      long total = 0;
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
        ZipEntry e;
        boolean any = false;
        while ((e = in.getNextEntry()) != null) {
          any = true;
          String n = e.getName();
          if (n.startsWith("/") || n.contains("..") || n.contains("\\")) throw new Fail(zip + " has an unsafe path: " + n);
          if (e.isDirectory() || n.startsWith("__MACOSX/") || n.endsWith(".DS_Store")) continue;
          Path d = stage.resolve(n).normalize();
          if (!d.startsWith(stage)) throw new Fail(zip + " has an unsafe path: " + n);
          Files.createDirectories(d.getParent());
          try (OutputStream out = Files.newOutputStream(d)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) {
              total += k;
              if (total > MAX_UNPACKED) throw new Fail(zip + " unpacks to more than 64 MB");
              out.write(buf, 0, k);
            }
          }
        }
        if (!any) throw new Fail(zip + " is not a zip file, or is empty");
      }
      copyFlat(stage, dir);
    } finally {
      deleteTree(stage);
    }
  }

  private static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p)) return;
    try (Stream<Path> st = Files.walk(p)) {
      for (Path f : st.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
    }
  }
}
