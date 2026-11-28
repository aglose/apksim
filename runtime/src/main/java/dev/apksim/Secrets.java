package dev.apksim;

import dev.apksim.serve.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Passwords typed into a flight recording, kept apart from it so that a replay can type them (docs/replay.md "Passwords
 * in a recording"). The recording itself never holds one. Off until its owner runs `apksim rec secrets on`.
 *
 * <p>The recorder runs inside the app's sandbox, which every other app shares, so it only ever has a public key
 * (`~/.apksim-secrets/public.key`: in the home folder, which an app may read and not write, so none can put a key of its
 * own there). It seals a recording's passwords to it into `STATE/secrets/ID.sealed`, which any app may read and none can
 * open. The private key is in the owner's login Keychain, which the sandbox cannot reach (or, with
 * APKSIM_SECRETS_KEY_FILE, in a file of mode 600, for Linux and CI), and only `apksim replay`'s plain JVM
 * ({@link ReplayPlan}), outside the sandbox, asks for it.
 *
 * <p>A sealed file is bound to its recording's random id, the app's package and its signer (the cipher's associated
 * data, which the replay makes from the APK it is about to run, not from the bundle): one copied under another
 * recording's name does not open for another app. JDK classes only: it runs in both JVMs.
 */
public final class Secrets {
  private Secrets() {}

  static final String ID = "[0-9a-f]{32}";
  static final String NAME = "s[1-9][0-9]{0,5}";
  static final int MAX_VALUE = 4096;
  private static final int MAX_FILE = 1 << 20;
  private static final String SERVICE = "apksim recording secrets";
  private static final byte[] MAGIC = "apksim-sealed/1\n".getBytes(StandardCharsets.US_ASCII);

  static String newId() {
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    return java.util.HexFormat.of().formatHex(b);
  }

  // ---------------------------------------------------------------- sealing (X25519, HKDF-SHA256, AES-256-GCM)

  /** What a sealed file is bound to. `certs`: the signer's certificate digests (no signature scheme). */
  static byte[] binding(String id, String pkg, String certs) {
    return ("apksim-secrets/1\n" + id + "\n" + pkg + "\n" + certs).getBytes(StandardCharsets.UTF_8);
  }

  // The JDK's own providers by name: in the app's process Conscrypt and Bouncy Castle are installed too.
  static byte[] seal(PublicKey to, byte[] plain, byte[] binding) throws GeneralSecurityException {
    KeyPair once = KeyPairGenerator.getInstance("X25519", "SunEC").generateKeyPair();
    byte[] pub = once.getPublic().getEncoded();
    Cipher c = cipher(Cipher.ENCRYPT_MODE, once.getPrivate(), to, pub, to.getEncoded(), binding);
    byte[] body = c.doFinal(plain);
    return ByteBuffer.allocate(MAGIC.length + 2 + pub.length + body.length).put(MAGIC).putShort((short) pub.length).put(pub).put(body).array();
  }

  static byte[] open(PrivateKey key, PublicKey own, byte[] sealed, byte[] binding) throws GeneralSecurityException {
    try {
      ByteBuffer b = ByteBuffer.wrap(sealed);
      byte[] magic = new byte[MAGIC.length];
      b.get(magic);
      if (!Arrays.equals(magic, MAGIC)) throw new GeneralSecurityException("not a sealed file");
      int n = b.getShort();
      if (n < 0 || n > 256) throw new GeneralSecurityException("not a sealed file");
      byte[] pub = new byte[n];
      b.get(pub);
      byte[] body = new byte[b.remaining()];
      b.get(body);
      PublicKey from = KeyFactory.getInstance("X25519", "SunEC").generatePublic(new X509EncodedKeySpec(pub));
      return cipher(Cipher.DECRYPT_MODE, key, from, pub, own.getEncoded(), binding).doFinal(body);
    } catch (java.nio.BufferUnderflowException e) {
      throw new GeneralSecurityException("a sealed file cut short");
    }
  }

  /** The key is used once (a key pair of its own per file), so the nonce can come from the derivation too. */
  private static Cipher cipher(int mode, PrivateKey mine, PublicKey theirs, byte[] oncePub, byte[] ownerPub, byte[] binding)
      throws GeneralSecurityException {
    KeyAgreement ka = KeyAgreement.getInstance("X25519", "SunEC");
    ka.init(mine);
    ka.doPhase(theirs, true);
    byte[] prk = hmac(MAGIC, ka.generateSecret());
    byte[] key = hmac(prk, oncePub, ownerPub, new byte[] {1});
    byte[] iv = Arrays.copyOf(hmac(prk, oncePub, ownerPub, new byte[] {2}), 12);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
    c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    c.updateAAD(binding);
    return c;
  }

  private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
    Mac m = Mac.getInstance("HmacSHA256", "SunJCE");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    for (byte[] p : parts) m.update(p);
    return m.doFinal();
  }

  static byte[] json(Map<String, String> values) {
    return dev.apksim.rec.JsonLine.write(new LinkedHashMap<String, Object>(values)).getBytes(StandardCharsets.UTF_8);
  }

  /** Name -> value, nothing else: anyone with the public key can seal a file, so what opens is not trusted either. */
  static Map<String, String> parse(byte[] json) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    if (Json.parse(new String(json, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
      m.forEach((k, v) -> {
        if (String.valueOf(k).matches(NAME) && v instanceof String s && usable(s)) out.put(String.valueOf(k), s);
      });
    }
    return out;
  }

  /** A value a replay may type: not empty, of a sane length, no control characters (an Enter would press a button). */
  static boolean usable(String s) {
    return !s.isEmpty() && s.length() <= MAX_VALUE && s.codePoints().noneMatch(Character::isISOControl);
  }

  // ---------------------------------------------------------------- in the app's process

  /** The public key to seal to (`-Dapksim.rec.seal`, which the launcher adds while this is on), or null. */
  static PublicKey sealingKey() {
    String p = System.getProperty("apksim.rec.seal");
    // An instance that must not touch its owner's sign-ins (serve, beam, replay, crawls) keeps none either.
    if (p == null || p.isEmpty() || Boolean.getBoolean("apksim.noAutofill")) return null;
    try {
      return publicKey(Path.of(p));
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      System.err.println("[apksim] rec: passwords are not kept: " + p + ": " + e.getMessage());
      return null;
    }
  }

  private static PublicKey publicKey(Path p) throws IOException, GeneralSecurityException {
    return KeyFactory.getInstance("X25519", "SunEC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(p).strip())));
  }

  static Path sealed(Path state, String id) {
    return state.resolve("secrets").resolve(id + ".sealed");
  }

  static void write(Path state, String id, byte[] sealed) throws IOException {
    Path to = sealed(state, id);
    Files.createDirectories(to.getParent());
    Path tmp = to.resolveSibling("." + id + ".tmp");
    try {
      Files.write(tmp, sealed);
      Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static Map<String, String> given = Map.of();
  private static Map<String, String> digests = Map.of();

  /**
   * The values for this replay (`--replay-secrets FD`), read from the pipe the launcher left open before any app code
   * runs (Main), with the digests of the bundle's files the plan read.
   */
  static void readLauncher() {
    String fd = System.getProperty("apksim.replay.secrets");
    if (fd == null || !fd.matches("[0-9]+")) return;
    try (InputStream in = Files.newInputStream(Path.of("/dev/fd/" + fd))) {
      byte[] b = in.readNBytes(MAX_FILE);
      if (b.length == 0) throw new IOException("nothing came");
      given = parse(b);
      Map<String, String> d = new LinkedHashMap<>();
      if (Json.parse(new String(b, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
        for (String f : List.of("manifest.json", "input.jsonl")) d.put(f, String.valueOf(m.get(f)));
      }
      digests = d;
    } catch (IOException | RuntimeException e) {
      given = Map.of();
      System.err.println("[apksim] replay: x is typed for the passwords, which could not be read: " + e.getClass().getSimpleName());
    }
  }

  /** The values, if these files are still what the plan read; `files`: their bytes, the ones the replay goes by. */
  static Map<String, String> take(Map<String, byte[]> files) {
    Map<String, String> v = given;
    given = Map.of();
    if (v.isEmpty()) return v;
    for (Map.Entry<String, String> d : digests.entrySet()) {
      byte[] b = files.get(d.getKey());
      String sha;
      try {
        sha = b == null ? "" : java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
      } catch (GeneralSecurityException e) {
        sha = null;
      }
      if (!d.getValue().equals(sha)) {
        System.err.println("[apksim] replay: x is typed for the passwords: " + d.getKey() + " changed after it was unpacked");
        return Map.of();
      }
    }
    return v;
  }

  // ---------------------------------------------------------------- outside the sandbox

  /** The kept passwords of a recording, opened; empty when there are none here. Throws with why they cannot be opened. */
  static Map<String, String> kept(Path state, String id, String pkg, String certs) throws IOException {
    Path f = sealed(state, id);
    if (!id.matches(ID) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return Map.of();
    if (Files.size(f) > MAX_FILE) throw new IOException(f + " is too big");
    try {
      byte[] own = Base64.getDecoder().decode(privateText());
      int at = ByteBuffer.wrap(own).getShort();
      PrivateKey key = KeyFactory.getInstance("X25519", "SunEC").generatePrivate(new PKCS8EncodedKeySpec(Arrays.copyOfRange(own, 2, 2 + at)));
      PublicKey pub = KeyFactory.getInstance("X25519", "SunEC").generatePublic(new X509EncodedKeySpec(Arrays.copyOfRange(own, 2 + at, own.length)));
      return parse(open(key, pub, Files.readAllBytes(f), binding(id, pkg, certs)));
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IOException("they were not kept for this app and signer, or with another key");
    }
  }

  /** `--secrets FILE`: `s1=VALUE` lines. */
  static Map<String, String> file(Path f) throws IOException {
    // Taken as written (no escapes, spaces kept), and never shown: a line with no name may be a password itself.
    Map<String, String> out = new LinkedHashMap<>();
    int n = 0;
    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
      n++;
      if (line.isBlank() || line.startsWith("#")) continue;
      int eq = line.indexOf('=');
      if (eq < 0 || !line.substring(0, eq).matches(NAME)) throw new IOException(f + ", line " + n + ": not a secret's name (s1, s2...) and = and its value");
      if (!usable(line.substring(eq + 1))) throw new IOException(f + ", line " + n + ": the value is empty, too long or has a control character");
      out.put(line.substring(0, eq), line.substring(eq + 1));
    }
    return out;
  }

  private static Path keyFile() {
    String f = System.getenv("APKSIM_SECRETS_KEY_FILE");
    return f == null || f.isEmpty() ? null : Path.of(f);
  }

  private static boolean mac() {
    return System.getProperty("os.name", "").startsWith("Mac");
  }

  /** There is no key (and not: it could not be read, when a new one would take its place). */
  private static final class NoKey extends IOException {
    NoKey(String why) {
      super(why);
    }
  }

  /** The private and public key together (base64), from the Keychain or the key file. Never logged. */
  private static String privateText() throws IOException {
    Path f = keyFile();
    if (f != null) {
      if (!Files.exists(f)) throw new NoKey("no key at " + f);
      return Files.readString(f).strip();
    }
    if (!mac()) throw new IOException("no Keychain here: set APKSIM_SECRETS_KEY_FILE, or pass --secrets FILE");
    String[] out = security(null, "find-generic-password", "-a", "apksim", "-s", SERVICE, "-w");
    if (out[0].equals("44")) throw new NoKey("the key is not in the Keychain");  // errSecItemNotFound
    if (!out[0].equals("0")) throw new IOException("the Keychain did not give the key (locked? security unlock-keychain)");
    return out[1].strip();
  }

  /** /usr/bin/security, with what must not be in its arguments (which `ps` shows) on stdin. [0] status, [1] output. */
  private static String[] security(String stdin, String... args) throws IOException {
    List<String> cmd = new ArrayList<>(List.of("/usr/bin/security"));
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var o = p.getOutputStream()) {
      if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("the Keychain did not answer");
      }
    } catch (InterruptedException e) {
      throw new IOException("interrupted");
    }
    return new String[] {String.valueOf(p.exitValue()), text};
  }

  static final String USAGE = "usage: apksim rec secrets [on | off | list | forget ID|all]";

  public static void main(String[] a) throws Exception {
    try {
      System.out.println(command(a, Path.of(System.getProperty("apksim.state")), Path.of(System.getProperty("apksim.secrets.dir"))));
    } catch (IOException e) {
      System.err.println("apksim rec secrets: " + e.getMessage());
      System.exit(2);
    }
  }

  private static String command(String[] a, Path state, Path dir) throws IOException, GeneralSecurityException {
    Path pub = dir.resolve("public.key");
    switch (a.length == 0 ? "status" : a[0]) {
      case "status" -> {
        return Files.exists(pub) ? "on: passwords typed while recording are kept, sealed, for `apksim replay` on this machine ("
            + ids(state).size() + " recordings)" : "off: a replay types x for a password (apksim rec secrets on)";
      }
      case "on" -> {
        // A key made before (`off` leaves it, so what it sealed still opens) is used again.
        String text = null;
        try {
          text = privateText();
        } catch (NoKey e) {
          // made below
        }
        if (text != null && Files.exists(pub)) return "already on";
        byte[] pubBytes;
        if (text != null) {
          byte[] own = Base64.getDecoder().decode(text);
          pubBytes = Arrays.copyOfRange(own, 2 + ByteBuffer.wrap(own).getShort(), own.length);
        } else {
          KeyPair k = KeyPairGenerator.getInstance("X25519", "SunEC").generateKeyPair();
          byte[] priv = k.getPrivate().getEncoded();
          pubBytes = k.getPublic().getEncoded();
          storePrivate(Base64.getEncoder().encodeToString(ByteBuffer.allocate(2 + priv.length + pubBytes.length)
              .putShort((short) priv.length).put(priv).put(pubBytes).array()));
        }
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(pub, Base64.getEncoder().encodeToString(pubBytes) + "\n");
        return "on: the private key is in " + (keyFile() != null ? keyFile() : "your login Keychain (\"" + SERVICE + "\")")
            + ". Instances started from now on keep passwords typed while recording, sealed, in " + state.resolve("secrets")
            + "; the recordings themselves hold none.";
      }
      case "off" -> {
        Files.deleteIfExists(pub);
        return "off. What was kept still replays (apksim rec secrets forget all removes it)";
      }
      case "list" -> {
        List<String> l = ids(state);
        return l.isEmpty() ? "none" : String.join("\n", l);
      }
      case "forget" -> {
        if (a.length != 2 || !(a[1].equals("all") || a[1].matches(ID))) throw new IOException(USAGE);
        int n = 0;
        for (String id : ids(state)) if ((a[1].equals("all") || a[1].equals(id)) && Files.deleteIfExists(sealed(state, id))) n++;
        if (a[1].equals("all")) {
          Files.deleteIfExists(pub);
          if (keyFile() != null) Files.deleteIfExists(keyFile());
          else if (mac()) security(null, "delete-generic-password", "-a", "apksim", "-s", SERVICE);
        }
        return "forgot " + n + (a[1].equals("all") ? " and the key (off)" : "");
      }
      default -> throw new IOException(USAGE);
    }
  }

  private static void storePrivate(String text) throws IOException {
    Path f = keyFile();
    if (f != null) {
      Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(f, text + "\n");
      return;
    }
    // `security -i` takes its commands from stdin, so the key is in no process's arguments.
    security("add-generic-password -a apksim -s \"" + SERVICE + "\" -w " + text + "\n", "-i");
    if (!text.equals(privateText())) throw new IOException("the key did not reach the Keychain");
  }

  private static List<String> ids(Path state) throws IOException {
    List<String> out = new ArrayList<>();
    Path dir = state.resolve("secrets");
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(dir, "*.sealed")) {
      for (Path p : d) {
        String n = p.getFileName().toString().replace(".sealed", "");
        if (n.matches(ID)) out.add(n);
      }
    }
    java.util.Collections.sort(out);
    return out;
  }
}
