package dev.apksim.serve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Who signed an APK, as Android compares it for an update that keeps the app's data (issue 181): the SHA-256 of each
 * signer's certificate, from the newest scheme the APK has (v3, else v2: the APK Signing Block before the ZIP central
 * directory; else v1: META-INF's PKCS #7 blocks). Signatures are not verified (a build is found by its hash, and an
 * altered one is another hash); only certificates are read, to tell whether two builds come from the same key. Key
 * rotation (v3's proof-of-rotation) is not followed: a rotated key counts as another signer.
 */
final class ApkSigners {
  private static final int V2 = 0x7109871a, V3 = 0xf05368c0;
  /** Reads at most this much of the signing block (real ones are a few KB; a big one is padding). */
  private static final long MAX_BLOCK = 16L << 20;

  private ApkSigners() {}

  /** The signers' certificate digests (sorted, hex), and the scheme they came from ("v3", "v2", "v1"), or throws why not. */
  record Signers(String scheme, List<String> certs) {}

  static Signers of(Path apk) throws IOException {
    try (FileChannel ch = FileChannel.open(apk, StandardOpenOption.READ)) {
      ByteBuffer block = signingBlock(ch);
      if (block != null) {
        for (int id : new int[] {V3, V2}) {
          ByteBuffer v = pair(block.duplicate().order(ByteOrder.LITTLE_ENDIAN), id);
          if (v != null) {
            List<String> certs = schemeCerts(v);
            if (!certs.isEmpty()) return new Signers(id == V3 ? "v3" : "v2", certs);
          }
        }
      }
    } catch (RuntimeException e) {  // a malformed block: lengths that do not fit
      throw new IOException("its signing block cannot be read: " + e);
    }
    List<String> v1 = v1Certs(apk);
    if (!v1.isEmpty()) return new Signers("v1", v1);
    throw new IOException("it is not signed");
  }

  /** The APK Signing Block's pairs (after its size, before its footer), or null when there is none. */
  private static ByteBuffer signingBlock(FileChannel ch) throws IOException {
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
    if (cd < 32 || cd > size) return null;
    ByteBuffer foot = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(foot, cd - 24);
    if (foot.getLong(8) != 0x20676953204b5041L || foot.getLong(16) != 0x3234206b636f6c42L) return null;  // "APK Sig Block 42"
    long blockSize = foot.getLong(0);
    if (blockSize < 24 || blockSize > MAX_BLOCK || blockSize + 8 > cd) return null;
    ByteBuffer b = ByteBuffer.allocate((int) (blockSize - 24)).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(b, cd - blockSize);  // after the leading size (8 bytes before this), up to the footer's size and magic
    return b.flip();
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

  /** v2 and v3 alike: signers, each with signed data whose second field is its certificates (the first is the leaf). */
  private static List<String> schemeCerts(ByteBuffer v) throws IOException {
    TreeSet<String> out = new TreeSet<>();
    ByteBuffer signers = prefixed(v);
    while (signers.hasRemaining()) {
      ByteBuffer signer = prefixed(signers);
      ByteBuffer signed = prefixed(signer);
      prefixed(signed);  // digests
      ByteBuffer certs = prefixed(signed);
      if (certs.hasRemaining()) {
        ByteBuffer first = prefixed(certs);
        byte[] der = new byte[first.remaining()];
        first.get(der);
        out.add(sha256(der));
      }
    }
    return new ArrayList<>(out);
  }

  /** v1: the certificate of each META-INF/*.RSA|DSA|EC signature block (the signer's: a block's first certificate). */
  private static List<String> v1Certs(Path apk) throws IOException {
    TreeSet<String> out = new TreeSet<>();
    try (ZipFile z = new ZipFile(apk.toFile())) {
      for (ZipEntry e : java.util.Collections.list(z.entries())) {
        String n = e.getName();
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0 || !n.matches("META-INF/[^/]+\\.(RSA|DSA|EC)") || e.getSize() > 1 << 20) continue;
        try (InputStream in = z.getInputStream(e)) {
          // The X.509 factory reads a PKCS #7 SignedData's certificates.
          var certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
          for (Certificate c : certs) {
            out.add(sha256(c.getEncoded()));
            break;
          }
        } catch (java.security.GeneralSecurityException ex) {
          throw new IOException(n + " cannot be read: " + ex.getMessage());
        }
      }
    }
    return new ArrayList<>(out);
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
