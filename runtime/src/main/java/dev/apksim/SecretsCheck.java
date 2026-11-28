package dev.apksim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;

/** scripts/check-secrets.sh: sealing a recording's passwords (Secrets), on a plain JVM. */
public final class SecretsCheck {
  private static int bad;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) bad++;
  }

  private static boolean opens(KeyPair k, byte[] sealed, byte[] binding) {
    try {
      Secrets.open(k.getPrivate(), k.getPublic(), sealed, binding);
      return true;
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  public static void main(String[] a) throws Exception {
    KeyPair k = KeyPairGenerator.getInstance("X25519").generateKeyPair(), other = KeyPairGenerator.getInstance("X25519").generateKeyPair();
    String id = Secrets.newId();
    byte[] bind = Secrets.binding(id, "com.example.app", "aa,bb");
    byte[] plain = Secrets.json(Map.of("s1", "päss \"w\\ord🔑"));
    byte[] sealed = Secrets.seal(k.getPublic(), plain, bind);
    check(Secrets.parse(Secrets.open(k.getPrivate(), k.getPublic(), sealed, bind)).equals(Map.of("s1", "päss \"w\\ord🔑")), "a sealed value opens as it was");
    check(!new String(sealed, StandardCharsets.ISO_8859_1).contains("ord"), "the sealed file does not show it");
    check(!java.util.Arrays.equals(sealed, Secrets.seal(k.getPublic(), plain, bind)), "sealing twice gives two files");
    check(!opens(other, sealed, bind), "another key does not open it");
    check(!opens(k, sealed, Secrets.binding(Secrets.newId(), "com.example.app", "aa,bb")), "nor under another recording's id");
    check(!opens(k, sealed, Secrets.binding(id, "com.example.other", "aa,bb")), "nor for another package");
    check(!opens(k, sealed, Secrets.binding(id, "com.example.app", "cc")), "nor for another signer");
    boolean any = false;
    for (int i = 0; i < sealed.length; i++) {
      byte[] t = sealed.clone();
      t[i] ^= 1;
      any |= opens(k, t, bind);
    }
    check(!any, "nor with any byte changed");
    for (int n = 0; n < sealed.length; n++) any |= opens(k, java.util.Arrays.copyOf(sealed, n), bind);
    check(!any, "nor cut short anywhere");
    check(Secrets.parse("{\"s1\":\"a\\nb\",\"x\":\"v\",\"s2\":3,\"s3\":\"ok\"}".getBytes()).equals(Map.of("s3", "ok")), "only named, typable values are taken");
    Path f = Files.createTempFile("secrets", ".properties");
    Files.writeString(f, "s1=hunter22\n");
    check(Secrets.file(f).equals(Map.of("s1", "hunter22")), "a --secrets file");
    Files.writeString(f, "password=hunter22\n");
    try {
      Secrets.file(f);
      check(false, "a file with another name is refused");
    } catch (java.io.IOException e) {
      check(!e.getMessage().contains("hunter22"), "a file with another name is refused, its value not shown");
    }
    Files.delete(f);
    check(Secrets.kept(Path.of("/nonexistent"), "../../etc", "p", "c").isEmpty(), "an id that is a path is not looked for");
    if (bad > 0) System.exit(1);
  }
}
