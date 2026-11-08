package dev.apksim.serve;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A beam ticket (docs/beam.md, phase 2): what lets a visitor's agent download one app's APK from this server itself,
 * without the server's token. Minted for the page (which has the token) as it starts a session on its Mac, for one app
 * and its SHA-256, good for {@link #lifetimeSeconds} (5 minutes; `APKSIM_BEAM_TICKET_SECONDS` for checks), signed with
 * a key this server makes at its start, so tickets die with it. `v1.<app>.<sha256>.<expiry>.<HMAC-SHA256>`.
 */
final class BeamTicket {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] key = new byte[32];
  final long lifetimeSeconds;

  BeamTicket() {
    RANDOM.nextBytes(key);
    long s = 300;
    try {
      String e = System.getenv("APKSIM_BEAM_TICKET_SECONDS");
      if (e != null) s = Math.max(1, Math.min(3600, Long.parseLong(e)));
    } catch (NumberFormatException e) {
      // the default
    }
    lifetimeSeconds = s;
  }

  /** A ticket for this app's build. */
  String mint(String app, String sha256) {
    String payload = "v1." + app + "." + sha256 + "." + (System.currentTimeMillis() / 1000 + lifetimeSeconds);
    return payload + "." + sign(payload);
  }

  /** What a valid, unexpired ticket names ({app, sha256}), else null. */
  String[] check(String ticket) {
    if (ticket == null || ticket.length() > 300) return null;
    String[] p = ticket.split("\\.");
    if (p.length != 5 || !p[0].equals("v1") || !p[1].matches("[A-Za-z0-9]{1,40}") || !p[2].matches("[0-9a-f]{64}") || !p[3].matches("\\d{1,12}")) {
      return null;
    }
    String payload = String.join(".", p[0], p[1], p[2], p[3]);
    if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.US_ASCII), p[4].getBytes(StandardCharsets.US_ASCII))) return null;
    if (Long.parseLong(p[3]) < System.currentTimeMillis() / 1000) return null;
    return new String[] {p[1], p[2]};
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
