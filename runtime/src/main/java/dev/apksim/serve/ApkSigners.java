package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Who signed an APK, as Android decides it for an update that keeps the app's data (issue 181): the SHA-256 of each
 * signer's certificate, from the newest scheme the APK has (v3, else v2: the APK Signing Block before the ZIP central
 * directory; else v1: the JAR signature), and only once that scheme's signatures verify against the APK's content,
 * as a device's package manager verifies them at install. A certificate alone is public: copied into another APK, it
 * says nothing about who made it.
 *
 * v2/v3: each signer's signature over its signed data, with its own public key (which must be its first certificate's),
 * and the content digests in that signed data recomputed over the APK (its entries, central directory and end record,
 * in 1 MB chunks) for the strongest algorithm signed. v3 signers are those whose SDK range includes {@link #SDK} (the API
 * level apksim runs). Key rotation (v3's proof-of-rotation) is not followed: a rotated key counts as another signer.
 * v1: the JDK's verified JAR reading (every entry's digest against the signed manifest, each signature block against
 * its signer's certificate), and every entry outside META-INF must be signed.
 */
public final class ApkSigners {
  // Public for `apksim replay` (dev.apksim.ReplayPlan), which applies the same signer rule: internal to apksim, not an
  // API anyone else builds on.
  private static final int V2 = 0x7109871a, V3 = 0xf05368c0;
  /** The API level a v3 signer must cover (apksim runs every app as API 36). */
  static final int SDK = 36;
  /** Reads at most this much of the signing block (real ones are a few KB; a big one is padding). */
  private static final long MAX_BLOCK = 16L << 20;
  private static final int CHUNK = 1 << 20;

  private ApkSigners() {}

  /** The signers' certificate digests (sorted, hex), and the scheme they came from ("v3", "v2", "v1"). */
  public record Signers(String scheme, List<String> certs) {}

  /** The verified signers of this APK, or throws why there are none (unsigned, or a signature that does not verify). */
  public static Signers of(Path apk) throws IOException {
    try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
      Layout l = layout(ch);
      if (l.block != null) {
        for (int id : new int[] {V3, V2}) {
          ByteBuffer v = pair(l.block.duplicate().order(ByteOrder.LITTLE_ENDIAN), id);
          if (v != null) return new Signers(id == V3 ? "v3" : "v2", verifyScheme(ch, l, v, id == V3));
        }
      }
    } catch (RuntimeException | java.security.GeneralSecurityException e) {  // a malformed block: lengths that do not fit
      throw new IOException("its signature cannot be read: " + e);
    }
    return new Signers("v1", v1Certs(apk));
  }

  /** Where the APK's sections are: its signing block's pairs (null when it has none) and the offsets around them. */
  private record Layout(ByteBuffer block, long blockStart, long cd, long eocd, long size) {}

  private static Layout layout(FileChannel ch) throws IOException {
    long size = ch.size();
    // End of central directory: 22 bytes plus a comment of up to 64 KB, found from the end.
    int tail = (int) Math.min(size, 22 + 65535);
    ByteBuffer t = ByteBuffer.allocate(tail).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(t, size - tail);
    long eocd = -1;
    for (int i = tail - 22; i >= 0; i--) {
      if (t.getInt(i) == 0x06054b50 && (t.getShort(i + 20) & 0xffff) == tail - 22 - i) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) throw new IOException("not a ZIP file");
    long cd = t.getInt((int) eocd + 16) & 0xffffffffL;
    eocd += size - tail;
    if (cd < 32 || cd > eocd) return new Layout(null, cd, cd, eocd, size);
    ByteBuffer foot = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(foot, cd - 24);
    if (foot.getLong(8) != 0x20676953204b5041L || foot.getLong(16) != 0x3234206b636f6c42L) return new Layout(null, cd, cd, eocd, size);  // "APK Sig Block 42"
    long blockSize = foot.getLong(0);
    if (blockSize < 24 || blockSize > MAX_BLOCK || blockSize + 8 > cd) return new Layout(null, cd, cd, eocd, size);
    long start = cd - blockSize - 8;
    ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(head, start);
    if (head.getLong(0) != blockSize) throw new IOException("its signing block's two sizes differ");
    ByteBuffer b = ByteBuffer.allocate((int) (blockSize - 24)).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(b, start + 8);  // the pairs: after the leading size, up to the footer's size and magic
    return new Layout(b.flip(), start, cd, eocd, size);
  }

  /** The value of the pair with this id, or null. */
  private static ByteBuffer pair(ByteBuffer b, int id) {
    while (b.remaining() >= 12) {
      long len = b.getLong();
      if (len < 4 || len > b.remaining()) return null;
      int pid = b.getInt();
      ByteBuffer v = slice(b, (int) len - 4);
      if (pid == id) return v;
    }
    return null;
  }

  private static ByteBuffer slice(ByteBuffer b, int n) {
    if (n < 0 || n > b.remaining()) throw new IllegalArgumentException("a length past its block");
    ByteBuffer s = b.slice(b.position(), n).order(ByteOrder.LITTLE_ENDIAN);
    b.position(b.position() + n);
    return s;
  }

  private static ByteBuffer prefixed(ByteBuffer b) {
    return slice(b, b.getInt());
  }

  private static byte[] bytes(ByteBuffer b) {
    byte[] out = new byte[b.remaining()];
    b.duplicate().get(out);
    return out;
  }

  /** A v2/v3 signature algorithm this reads: its JCA name, PSS or not, and its content digest. */
  private record Algorithm(int id, String jca, boolean pss, String digest, int strength) {}

  private static Algorithm algorithm(int id) {
    return switch (id) {
      case 0x0101 -> new Algorithm(id, "RSASSA-PSS", true, "SHA-256", 1);
      case 0x0102 -> new Algorithm(id, "RSASSA-PSS", true, "SHA-512", 2);
      case 0x0103 -> new Algorithm(id, "SHA256withRSA", false, "SHA-256", 1);
      case 0x0104 -> new Algorithm(id, "SHA512withRSA", false, "SHA-512", 2);
      case 0x0201 -> new Algorithm(id, "SHA256withECDSA", false, "SHA-256", 1);
      case 0x0202 -> new Algorithm(id, "SHA512withECDSA", false, "SHA-512", 2);
      case 0x0301 -> new Algorithm(id, "SHA256withDSA", false, "SHA-256", 1);
      default -> null;  // verity (0x0421...) and anything newer: another algorithm of the signer's is used
    };
  }

  /** Every signer of a v2 or v3 block verified (v3: those covering {@link #SDK}); their certificates' digests. */
  private static List<String> verifyScheme(FileChannel ch, Layout l, ByteBuffer v, boolean v3)
      throws IOException, java.security.GeneralSecurityException {
    TreeSet<String> out = new TreeSet<>();
    java.util.Map<String, byte[]> computed = new java.util.HashMap<>();
    ByteBuffer signers = prefixed(v);
    int seen = 0;
    while (signers.hasRemaining()) {
      ByteBuffer signer = prefixed(signers);
      ByteBuffer signed = prefixed(signer);
      if (v3) {
        int min = signer.getInt(), max = signer.getInt();
        if (SDK < min || SDK > max) continue;  // a signer for other platform versions (a v3.0 rotation split)
      }
      ByteBuffer sigs = prefixed(signer);
      byte[] publicKey = bytes(prefixed(signer));
      seen++;
      // The strongest algorithm this reads among the signer's signatures, then that signature over its signed data.
      Algorithm best = null;
      byte[] bestSig = null;
      while (sigs.hasRemaining()) {
        ByteBuffer s = prefixed(sigs);
        Algorithm a = algorithm(s.getInt());
        byte[] sig = bytes(prefixed(s));
        if (a != null && (best == null || a.strength() > best.strength())) {
          best = a;
          bestSig = sig;
        }
      }
      if (best == null) throw new IOException("no signature algorithm it can check");
      String keyAlg = best.jca().endsWith("ECDSA") ? "EC" : best.jca().endsWith("DSA") ? "DSA" : "RSA";
      PublicKey key = KeyFactory.getInstance(keyAlg).generatePublic(new X509EncodedKeySpec(publicKey));
      Signature verifier = Signature.getInstance(best.jca());
      if (best.pss()) {
        String d = best.digest();
        verifier.setParameter(new PSSParameterSpec(d, "MGF1", new MGF1ParameterSpec(d), d.equals("SHA-256") ? 32 : 64, 1));
      }
      verifier.initVerify(key);
      verifier.update(signed.duplicate());
      if (!verifier.verify(bestSig)) throw new IOException("its signature does not verify");
      // The signed data: digests, certificates (the first is the signer's, and must hold the key just used), ...
      ByteBuffer digests = prefixed(signed);
      ByteBuffer certs = prefixed(signed);
      if (!certs.hasRemaining()) throw new IOException("a signer without a certificate");
      byte[] der = bytes(prefixed(certs));
      X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
      if (!Arrays.equals(cert.getPublicKey().getEncoded(), publicKey)) throw new IOException("its certificate is not its signing key's");
      byte[] want = null;
      while (digests.hasRemaining()) {
        ByteBuffer d = prefixed(digests);
        int id = d.getInt();
        byte[] value = bytes(prefixed(d));
        if (id == best.id()) want = value;
      }
      if (want == null) throw new IOException("no content digest for its signature's algorithm");
      byte[] got = computed.get(best.digest());
      if (got == null) {
        got = contentDigest(ch, l, best.digest());
        computed.put(best.digest(), got);
      }
      if (!MessageDigest.isEqual(want, got)) throw new IOException("its contents are not what was signed");
      out.add(sha256(der));
    }
    if (seen == 0) throw new IOException("no signer for API " + SDK);
    return new ArrayList<>(out);
  }

  /**
   * The v2/v3 content digest: the entries (up to the signing block), the central directory and the end record (its
   * central directory offset read as the signing block's start), each in 1 MB chunks digested as 0xa5, length, bytes;
   * then 0x5a, the chunk count and every chunk's digest.
   */
  private static byte[] contentDigest(FileChannel ch, Layout l, String alg) throws IOException, java.security.GeneralSecurityException {
    MessageDigest top = MessageDigest.getInstance(alg), chunk = MessageDigest.getInstance(alg);
    ByteBuffer eocd = ByteBuffer.allocate((int) (l.size() - l.eocd())).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(eocd, l.eocd());
    eocd.putInt(16, (int) l.blockStart());
    eocd.flip();
    long[][] sections = {{0, l.blockStart()}, {l.cd(), l.eocd()}};
    long count = 0;
    for (long[] s : sections) count += (s[1] - s[0] + CHUNK - 1) / CHUNK;
    count += (eocd.remaining() + CHUNK - 1) / CHUNK;
    if (count > Integer.MAX_VALUE) throw new IOException("too large");
    ByteBuffer head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
    head.put((byte) 0x5a).putInt((int) count);
    top.update(head.array());
    ByteBuffer buf = ByteBuffer.allocate(CHUNK);
    ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
    for (long[] s : sections) {
      for (long at = s[0]; at < s[1]; at += CHUNK) {
        int n = (int) Math.min(CHUNK, s[1] - at);
        buf.clear().limit(n);
        while (buf.hasRemaining()) {
          if (ch.read(buf, at + buf.position()) < 0) throw new IOException("the APK ended early");
        }
        buf.flip();
        prefix.clear();
        prefix.put((byte) 0xa5).putInt(n);
        chunk.update(prefix.array());
        chunk.update(buf);
        top.update(chunk.digest());
      }
    }
    while (eocd.hasRemaining()) {
      int n = Math.min(CHUNK, eocd.remaining());
      prefix.clear();
      prefix.put((byte) 0xa5).putInt(n);
      chunk.update(prefix.array());
      chunk.update(slice(eocd, n));
      top.update(chunk.digest());
    }
    return top.digest();
  }

  /**
   * v1: read through the JDK's verifying JarFile, which checks each entry's digest against the signed manifest and
   * each signature block against its signer; every entry but META-INF's must be signed, and by the same signers. The
   * certificate of each signer is the first of its certificate path (the signer's own, not a CA's).
   */
  private static List<String> v1Certs(Path apk) throws IOException {
    TreeSet<String> all = null;
    byte[] buf = new byte[64 * 1024];
    try (JarFile jar = new JarFile(apk.toFile(), true)) {
      if (jar.getManifest() == null) throw new IOException("it is not signed");
      for (JarEntry e : java.util.Collections.list(jar.entries())) {
        if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
        try (InputStream in = jar.getInputStream(e)) {
          while (in.read(buf) >= 0) {
            // read to the end: the digest is checked there (a mismatch throws SecurityException)
          }
        }
        java.security.CodeSigner[] signers = e.getCodeSigners();
        if (signers == null || signers.length == 0) throw new IOException(e.getName() + " is not signed");
        TreeSet<String> these = new TreeSet<>();
        for (java.security.CodeSigner s : signers) {
          List<? extends Certificate> path = s.getSignerCertPath().getCertificates();
          if (!path.isEmpty()) these.add(sha256(path.get(0).getEncoded()));
        }
        if (all == null) all = these;
        else if (!all.equals(these)) throw new IOException("its entries are signed by different keys");
      }
    } catch (SecurityException e) {
      throw new IOException("its signature does not verify: " + e.getMessage());
    } catch (java.security.cert.CertificateEncodingException e) {
      throw new IOException("a certificate cannot be read: " + e.getMessage());
    }
    if (all == null || all.isEmpty()) throw new IOException("it is not signed");
    return new ArrayList<>(all);
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
