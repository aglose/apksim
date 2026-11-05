package dev.apksim.net;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/**
 * The certificate a replayed TLS session presents for a host the recording has no certificates for (a hand-written
 * entry): it names the host, so hostname checks pass, but its key is a throwaway one, so certificate pins do not.
 */
final class PlaceholderCertificate extends X509Certificate {
  private static PublicKey key;
  private final String host;
  private final X500Principal subject;

  PlaceholderCertificate(String host) {
    this.host = host;
    this.subject = new X500Principal("CN=" + host.replace(",", "") + ", O=apksim network replay");
  }

  private static synchronized PublicKey key() {
    if (key == null) {
      try {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        key = g.generateKeyPair().getPublic();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return key;
  }

  @Override
  public Collection<List<?>> getSubjectAlternativeNames() {
    boolean ip = host.matches("[0-9.]+") || host.contains(":");
    return List.of(List.of(ip ? 7 : 2, host));
  }

  @Override public void checkValidity() {}
  @Override public void checkValidity(Date date) {}
  @Override public int getVersion() { return 3; }
  @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
  @Override public java.security.Principal getIssuerDN() { return subject; }
  @Override public X500Principal getIssuerX500Principal() { return subject; }
  @Override public java.security.Principal getSubjectDN() { return subject; }
  @Override public X500Principal getSubjectX500Principal() { return subject; }
  @Override public Date getNotBefore() { return new Date(0); }
  @Override public Date getNotAfter() { return new Date(4102444800000L); }
  @Override public byte[] getTBSCertificate() throws CertificateEncodingException { throw new CertificateEncodingException("placeholder"); }
  @Override public byte[] getSignature() { return new byte[0]; }
  @Override public String getSigAlgName() { return "NONE"; }
  @Override public String getSigAlgOID() { return "0.0"; }
  @Override public byte[] getSigAlgParams() { return null; }
  @Override public boolean[] getIssuerUniqueID() { return null; }
  @Override public boolean[] getSubjectUniqueID() { return null; }
  @Override public boolean[] getKeyUsage() { return null; }
  @Override public int getBasicConstraints() { return -1; }
  @Override public java.util.Set<String> getCriticalExtensionOIDs() { return null; }
  @Override public java.util.Set<String> getNonCriticalExtensionOIDs() { return null; }
  @Override public byte[] getExtensionValue(String oid) { return null; }
  @Override public boolean hasUnsupportedCriticalExtension() { return false; }
  @Override public byte[] getEncoded() { return ("apksim placeholder certificate for " + host).getBytes(StandardCharsets.UTF_8); }
  @Override public void verify(PublicKey k) {}
  @Override public void verify(PublicKey k, String provider) {}
  @Override public PublicKey getPublicKey() { return key(); }
  @Override public String toString() { return "PlaceholderCertificate[" + subject + "]"; }
}
