package dev.apksim.serve;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Checks the time to live of added apps ({@link Catalog#expireAt}, scripts/check-ttl.sh, CI): an id past its deadline is
 * unknown to `find` and its ref goes at the next sweep, the stored APK stays while a session or a warm-up holds it (a running
 * app opens its APK again by path) and goes at the first sweep after, an id with a time still to run and an ordinary one are
 * kept, and the bounds of a ttl are refused. Exits 1 if any check fails.
 */
public final class CatalogTtlCheck {
  private static int failures;

  private CatalogTtlCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  private static byte[] apk(String marker) throws Exception {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(b)) {
      z.putNextEntry(new ZipEntry("AndroidManifest.xml"));
      z.write(marker.getBytes());
      z.closeEntry();
    }
    return b.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    Path state = Files.createTempDirectory("ttl-check");
    Files.createDirectories(state.resolve("serve/uploads"));
    Files.createDirectories(state.resolve("serve/downloads"));
    Catalog c = new Catalog(List.of(), state, "serve", 1 << 20);
    byte[] a = apk("a"), b = apk("b"), o = apk("ordinary");
    Catalog.App expiring = c.upload(new ByteArrayInputStream(a), a.length, "a.apk", List.of());
    Catalog.App later = c.upload(new ByteArrayInputStream(b), b.length, "b.apk", List.of());
    Catalog.App ordinary = c.upload(new ByteArrayInputStream(o), o.length, "o.apk", List.of());
    c.expireAt(expiring.id(), 60);
    c.expireAt(later.id(), 3600);
    check(c.find(expiring.id()) != null && c.expiresAt(expiring.id()) > 0, "an id with a time to run is found, with its deadline");
    for (long bad : new long[] {0, 59, Catalog.TTL_MAX_S + 1}) {
      boolean refused = false;
      try {
        c.expireAt(ordinary.id(), bad);
      } catch (java.io.IOException e) {
        refused = true;
      }
      check(refused, "a ttl of " + bad + " s is refused");
    }
    // The first one's deadline passes.
    Files.writeString(state.resolve("serve/uploads/" + expiring.id() + ".expires"), Long.toString(System.currentTimeMillis() - 1000));
    String apkName = expiring.path().getFileName().toString();
    check(c.find(expiring.id()) == null, "past its deadline the id is unknown");
    c.sweep(24 * 3_600_000L, name -> name.equals(apkName));
    check(Files.exists(expiring.path()), "a sweep with a session on it keeps the stored APK");
    check(!Files.exists(state.resolve("serve/uploads/" + expiring.id() + ".ref")), "though the id is gone");
    c.sweep(24 * 3_600_000L, name -> false);
    check(!Files.exists(expiring.path()), "the first sweep after the session has ended deletes it");
    check(c.find(later.id()) != null && Files.exists(later.path()), "an id with time to run is kept");
    check(c.find(ordinary.id()) != null && Files.exists(ordinary.path()), "an ordinary id is kept");
    if (failures > 0) {
      System.out.println(failures + " ttl check(s) failed");
      System.exit(1);
    }
    System.out.println("ttl check passed");
  }
}
