package dev.apksim;

import java.security.Provider;
import java.security.Security;

/**
 * KeyStore.getDefaultType() from app code (MethodTranslator): "BKS", Android's (the platform's Bouncy Castle), not the
 * JDK's "pkcs12". One app opens the BKS client keystore it ships with KeyStore.getInstance(KeyStore.getDefaultType());
 * here the JDK read it as PKCS12 and threw ("toDerInputStream rejects tag type 0") in every test that started its
 * HTTP client. The JDK's own default stays as it is: it loads the trust store with it. Bouncy Castle (on the class
 * path already) is added once, after the JDK's providers, so it answers only what they do not, such as BKS.
 */
public final class KeyStores {
  private static volatile boolean installed;

  private KeyStores() {}

  /** "BKS" when a provider can read it (Bouncy Castle registered), else the JDK's own default, which then still works. */
  public static String getDefaultType() {
    installBouncyCastle();
    return Security.getProvider("BC") != null ? "BKS" : java.security.KeyStore.getDefaultType();
  }

  static void installBouncyCastle() {
    if (installed) return;
    synchronized (KeyStores.class) {
      if (installed) return;
      try {
        if (Security.getProvider("BC") == null) {
          Provider bc = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
              .getDeclaredConstructor().newInstance();
          Security.addProvider(bc);
        }
      } catch (ReflectiveOperationException | LinkageError e) {
        System.err.println("[apksim] Bouncy Castle for BKS: " + e);
      } finally {
        // Only once the provider is registered (or could not be): a concurrent first caller waits on the lock
        // instead of answering "BKS" before anything reads it.
        installed = true;
      }
    }
  }
}
