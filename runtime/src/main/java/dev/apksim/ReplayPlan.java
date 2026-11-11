package dev.apksim;

import dev.apksim.serve.ApkSigners;
import dev.apksim.serve.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * `apksim replay [OPTIONS] BUNDLE.apkrec` before the app starts (docs/replay.md), on a plain JVM with only apksim.jar:
 * reads the bundle, finds the APK it was recorded with (or takes `--apk` for another build of it from the same
 * signer), unpacks the checkpoint's data folder and `.device` folder into a data slot of its own (`rp<12 hex>-0`, as
 * apksim beam unpacks a handed-off slot), puts the rest of the bundle in `STATE/replay/rp<12 hex>/`, and prints the
 * launch, one argument a line, which the launcher then runs: the device, appearance, locale, font scale, time zone and
 * animator scale of the checkpoint, `--network replay` of the bundle's HAR, and `--replay-dir`, which {@link Replay}
 * reads inside the app's process. Options it does not know are passed on to that launch as they are (`--headless`,
 * `--control auto`, `--network-trust CERT`...), after its own, so they win.
 *
 * <p>Exit 2 with one line when the bundle cannot be replayed (no checkpoint, the APK not found or another build or
 * signer).
 */
public final class ReplayPlan {
  private ReplayPlan() {}

  /** Why a bundle cannot be replayed: one line, exit 2. */
  static final class Refused extends Exception {
    Refused(String why) {
      super(why);
    }
  }

  public static void main(String[] args) throws Exception {
    try {
      for (String a : plan(args, Path.of(System.getProperty("apksim.state")))) System.out.println(a);
    } catch (Refused e) {
      System.err.println("apksim replay: " + e.getMessage());
      System.exit(2);
    }
  }

  static final String USAGE = "usage: apksim replay [--apk APK] [--live-network] [--out DIR] [--compare all|end|none]"
      + " [--threshold N] [APKSIM OPTIONS...] BUNDLE.apkrec";

  static List<String> plan(String[] args, Path state) throws Exception {
    String apkArg = null;
    String outArg = null;
    String compare = "all";
    String threshold = null;
    boolean live = false;
    List<String> pass = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--apk" -> apkArg = need(args, ++i);
        case "--live-network" -> live = true;
        case "--out" -> outArg = need(args, ++i);
        case "--compare" -> {
          compare = need(args, ++i);
          if (!compare.matches("all|end|none")) throw new Refused("--compare takes all, end or none");
        }
        case "--threshold" -> {
          threshold = need(args, ++i);
          if (!threshold.matches("\\d+(\\.\\d+)?")) throw new Refused("--threshold takes a number of pixels (or a fraction under 1)");
        }
        case "-h", "--help" -> throw new Refused(USAGE);
        default -> pass.add(args[i]);
      }
    }
    if (pass.isEmpty()) throw new Refused(USAGE);
    Path bundle = Path.of(pass.remove(pass.size() - 1)).toAbsolutePath().normalize();
    if (!Files.isRegularFile(bundle)) throw new Refused("no bundle at " + bundle);
    sweep(state);

    try (ZipFile z = new ZipFile(bundle.toFile())) {
      Map<String, Object> m = map(Json.parse(text(z, "manifest.json")));
      if (!String.valueOf(m.get("format")).startsWith("apksim-flight-recording/")) throw new Refused(bundle + " is not a flight recording");
      Map<String, Object> cp = map(m.get("checkpoint"));
      if (cp == null || z.getEntry("checkpoint/state.bin") == null) {
        throw new Refused("the recording has no checkpoint, so it cannot be replayed, only read (docs/flight-recorder.md)");
      }
      String pkg = String.valueOf(m.get("package"));
      if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new Refused("bad package in the manifest: " + pkg);

      Path apk = apk(m, bundle, apkArg);

      // The bundle's parts, where the instance reads them; the data into a slot of its own.
      String id = "rp" + HexFormat.of().formatHex(random(6));
      Path dir = state.resolve("replay").resolve(id);
      Files.createDirectories(dir);
      for (ZipEntry e : java.util.Collections.list(z.entries())) {
        String name = e.getName();
        if (e.isDirectory() || name.equals("checkpoint/data.zip") || name.contains("..") || name.startsWith("/")) continue;
        Path to = dir.resolve(name);
        Files.createDirectories(to.getParent());
        try (InputStream in = z.getInputStream(e)) {
          Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
      }
      Path slot = state.resolve("cache").resolve("data").resolve(pkg).resolve(id + "-0");
      ZipEntry data = z.getEntry("checkpoint/data.zip");
      if (data != null) {
        Path tmp = dir.resolve("data.zip");
        try (InputStream in = z.getInputStream(data)) {
          Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(slot.getParent());
        try {
          dev.apksim.serve.SlotPack.unpack(tmp, slot);
        } catch (IOException e) {
          throw new Refused("the checkpoint's data cannot be unpacked: " + e.getMessage());
        } finally {
          Files.deleteIfExists(tmp);
        }
      }

      Path out = outArg != null ? Path.of(outArg).toAbsolutePath().normalize()
          : state.resolve("replays").resolve(stem(bundle) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
      Files.createDirectories(out);
      boolean har = !live && Files.isRegularFile(dir.resolve("network.har"));

      Properties p = new Properties();
      p.setProperty("bundle", bundle.toString());
      p.setProperty("apk", apk.toString());
      p.setProperty("out", out.toString());
      p.setProperty("compare", compare);
      if (threshold != null) p.setProperty("threshold", threshold);
      p.setProperty("network", har ? "har" : live ? "live" : "live (the recording has no net channel)");
      p.setProperty("data", data != null ? "restored" : "none in the bundle");
      try (OutputStream o = Files.newOutputStream(dir.resolve("plan.properties"))) {
        p.store(o, "apksim replay");
      }
      Files.writeString(dir.resolve("started"), String.valueOf(System.currentTimeMillis()));

      List<String> launch = new ArrayList<>();
      launch.add("--replay-dir");
      launch.add(dir.toString());
      launch.add("--replay-out");
      launch.add(out.toString());
      launch.add("--data-slots");
      launch.add(id);
      launch.add("--no-autofill");  // a replay never signs anyone in with the owner's saved sign-ins
      launch.addAll(deviceOptions(map(cp.get("device"))));
      if (har) {
        // The recorded answers, as slow as they came, without replay's marker header: as the app got them.
        launch.add("--network");
        launch.add("replay");
        launch.add(dir.resolve("network.har").toString());
        launch.add("--network-replay-timing");
        launch.add("recorded");
        launch.add("--network-replay-mark");
        launch.add("off");
      }
      launch.addAll(pass);
      launch.add(apk.toString());
      return launch;
    }
  }

  /**
   * The APK to replay against: `--apk` (another build must come from the same signer), else the one the manifest names
   * (`apkPath`), else one of its name beside the bundle or in the working folder, which must be the recorded build.
   */
  private static Path apk(Map<String, Object> m, Path bundle, String apkArg) throws Exception {
    String sha = (String) m.get("apkSha256");
    String name = m.get("apk") != null ? String.valueOf(m.get("apk")) : null;
    String recordedSigners = (String) m.get("apkSigners");
    if (apkArg != null) {
      Path apk = Path.of(apkArg).toAbsolutePath().normalize();
      if (!Files.isRegularFile(apk)) throw new Refused("no APK at " + apk);
      if (sha != null && sha.equals(sha256(apk))) return apk;
      // Another build: the checkpoint's data is the app's, so it goes only to a build Android would update in place.
      String signers = signers(apk);
      if (recordedSigners == null) {
        Path original = find(m, bundle, name, sha);
        recordedSigners = original != null ? signers(original) : null;
      }
      if (recordedSigners == null) {
        System.err.println("apksim replay: the recording does not say who signed its build and the build is not here;"
            + " the signer of " + apk.getFileName() + " is not checked");
      } else if (!recordedSigners.equals(signers)) {
        throw new Refused(apk.getFileName() + " is signed by another key than the recorded build: its data would not"
            + " carry over on a device either (" + (signers == null ? "unsigned" : signers) + " against " + recordedSigners + ")");
      }
      System.err.println("apksim replay: " + apk.getFileName() + " is another build than the recorded one (same signer)");
      return apk;
    }
    Path found = find(m, bundle, name, sha);
    if (found != null) return found;
    throw new Refused("the recorded build (" + (name != null ? name : "its APK") + (sha != null ? ", sha256 " + sha.substring(0, 12) : "")
        + ") is not at the path it was recorded from, beside the bundle or here; pass --apk APK");
  }

  /** The recorded build itself, by the manifest's path and file name, checked against its hash. */
  private static Path find(Map<String, Object> m, Path bundle, String name, String sha) throws IOException {
    List<Path> tries = new ArrayList<>();
    if (m.get("apkPath") != null) tries.add(Path.of(String.valueOf(m.get("apkPath"))));
    if (name != null && !name.contains("/")) {
      tries.add(bundle.resolveSibling(name));
      tries.add(Path.of(name).toAbsolutePath());
    }
    String other = null;
    for (Path p : tries) {
      if (!Files.isRegularFile(p)) continue;
      if (sha == null || sha.equals(sha256(p))) return p.toAbsolutePath().normalize();
      other = p.toString();
    }
    if (other != null) {
      System.err.println("apksim replay: " + other + " is another build than the recorded one (pass it as --apk to replay against it)");
    }
    return null;
  }

  private static String signers(Path apk) {
    try {
      ApkSigners.Signers s = ApkSigners.of(apk);
      return s.scheme() + ":" + String.join(",", s.certs());
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The launch options that give the app the checkpoint's device: the preset (or a size), appearance, locale, font
   * scale, time zone and animator scale. The rotation is applied inside the process ({@link Replay}), before the stack
   * is rebuilt. Older bundles have only the size, density and qualifiers.
   */
  static List<String> deviceOptions(Map<String, Object> d) {
    List<String> o = new ArrayList<>();
    if (d == null) return o;
    float density = num(d.get("density"), 2f);
    int rotation = (int) num(d.get("rotation"), 0);
    int wDp = d.get("widthDp") != null ? (int) num(d.get("widthDp"), 0) : Math.round(num(d.get("width"), 822) / density);
    int hDp = d.get("heightDp") != null ? (int) num(d.get("heightDp"), 0) : Math.round(num(d.get("height"), 1782) / density);
    // The unrotated size: a rotated phone is still the phone preset.
    if (rotation % 2 == 1) {
      int t = wDp;
      wDp = hDp;
      hDp = t;
    }
    String spec = wDp + "x" + hDp + "@" + trim(density);
    String name = d.get("name") != null ? String.valueOf(d.get("name")) : null;
    try {
      Device preset = Device.named(name != null ? name : "phone");
      if (preset.widthDp() == wDp && preset.heightDp() == hDp && preset.density() == density) spec = preset.name();
      else if (name != null && name.matches("\\d+x\\d+(@[\\d.]+)?|\\d+x\\d+px@[\\d.]+dpi")) spec = name;
    } catch (IllegalArgumentException e) {
      // a size
    }
    if (name == null) {
      for (String n : new String[] {"phone", "foldable", "tablet", "desktop", "tv"}) {
        Device p = Device.named(n);
        if (p.widthDp() == wDp && p.heightDp() == hDp && p.density() == density) spec = n;
      }
    }
    o.add("--device");
    o.add(spec);
    String q = d.get("qualifiers") != null ? String.valueOf(d.get("qualifiers")) : "";
    if (q.contains("-night-") || q.endsWith("-night")) {
      o.add("--appearance");
      o.add("dark");
    } else if (q.contains("notnight")) {
      o.add("--appearance");
      o.add("light");
    }
    String[] parts = q.split("-");
    if (parts.length > 0 && parts[0].matches("[a-z]{2,3}")) {
      String locale = parts[0] + (parts.length > 1 && parts[1].matches("r[A-Z]{2}") ? "-" + parts[1].substring(1) : "");
      o.add("--locale");
      o.add(locale);
    }
    if (d.get("fontScale") != null) {
      o.add("--font-scale");
      o.add(trim(num(d.get("fontScale"), 1f)));
    }
    if (d.get("timezone") != null) {
      o.add("--timezone");
      o.add(String.valueOf(d.get("timezone")));
    }
    if (d.get("features") instanceof List<?> fs) {
      for (Object f : fs) {
        o.add("--feature");
        o.add(String.valueOf(f));
      }
    }
    if (d.get("animScale") != null && num(d.get("animScale"), 1f) != 1f) {
      o.add("--anim-scale");
      o.add(trim(num(d.get("animScale"), 1f)));
    }
    return o;
  }

  /**
   * Replays that have ended: their unpacked bundle (`STATE/replay/rpX`, at least 10 minutes old) and their data slot,
   * unless an instance still holds the slot's lock (a replay a person took over with --control).
   */
  private static void sweep(Path state) {
    Path root = state.resolve("replay");
    if (!Files.isDirectory(root)) return;
    try (var dirs = Files.list(root)) {
      for (Path dir : (Iterable<Path>) dirs::iterator) {
        String id = dir.getFileName().toString();
        if (!id.matches("rp[0-9a-f]{12}")) continue;
        try {
          if (System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis() < 10 * 60_000L) continue;
        } catch (IOException e) {
          continue;
        }
        boolean inUse = false;
        List<Path> slots = new ArrayList<>();
        Path data = state.resolve("cache").resolve("data");
        if (Files.isDirectory(data)) {
          try (var pkgs = Files.list(data)) {
            for (Path pkg : (Iterable<Path>) pkgs::iterator) {
              Path slot = pkg.resolve(id + "-0");
              if (!Files.isDirectory(slot)) continue;
              slots.add(slot);
              Path lock = slot.resolve(".lock");
              if (Files.exists(lock)) {
                try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock l = ch.tryLock()) {
                  if (l == null) inUse = true;
                } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
                  inUse = true;
                }
              }
            }
          }
        }
        if (inUse) continue;
        for (Path s : slots) {
          Storage.deleteTree(s);
          Storage.deleteTree(s.resolveSibling(s.getFileName() + ".device"));
        }
        Storage.deleteTree(dir);
      }
    } catch (IOException e) {
      // next time
    }
  }

  private static String stem(Path bundle) {
    String n = bundle.getFileName().toString();
    return n.endsWith(".apkrec") ? n.substring(0, n.length() - 7) : n;
  }

  private static byte[] random(int n) throws java.security.NoSuchAlgorithmException {
    byte[] b = new byte[n];
    new java.security.SecureRandom().nextBytes(b);
    return b;
  }

  private static String need(String[] a, int i) throws Refused {
    if (i >= a.length) throw new Refused("option " + a[i - 1] + " needs a value; " + USAGE);
    return a[i];
  }

  private static String text(ZipFile z, String name) throws IOException, Refused {
    ZipEntry e = z.getEntry(name);
    if (e == null) throw new Refused("no " + name + " in the bundle");
    try (InputStream in = z.getInputStream(e)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }

  static float num(Object o, float dflt) {
    return o instanceof Number n ? n.floatValue() : dflt;
  }

  private static String trim(float f) {
    return f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f);
  }

  static String sha256(Path p) throws IOException {
    try (InputStream in = Files.newInputStream(p)) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
      return HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
