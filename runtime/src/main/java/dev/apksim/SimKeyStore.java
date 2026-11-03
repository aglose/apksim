package dev.apksim;

import android.security.keystore.KeyGenParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The Android Keystore, which Robolectric does not have: a JCA provider named "AndroidKeyStore" with its KeyStore,
 * AES/HMAC KeyGenerators and EC/RSA KeyPairGenerators taking a KeyGenParameterSpec. Keys are ordinary JCE keys, so
 * the app's Cipher, Mac and Signature work with them, and they persist in the app's data folder like device keys
 * survive restarts. A test keystore: the keys sit in that file in the clear, and no use restriction (user
 * authentication, purposes) is enforced.
 */
final class SimKeyStore {
  private static final Map<String, Key> KEYS = new TreeMap<>();
  private static final Map<String, PublicKey> PUBLIC = new TreeMap<>();
  private static Path file;

  private SimKeyStore() {}

  /** Before the app runs; the keys are read on first use, from the app's data folder. */
  static synchronized void install() {
    if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(new KeyProvider());
  }

  private static synchronized void ensureLoaded() {
    if (file != null) return;
    file = org.robolectric.RuntimeEnvironment.getApplication().getDataDir().toPath().resolve("apksim-keystore.properties");
    load();
  }

  // ---------------------------------------------------------------- storage

  private static void load() {
    if (!Files.exists(file)) return;
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      p.load(in);
      for (String alias : p.stringPropertyNames()) {
        String[] v = p.getProperty(alias).split(":", 3);  // kind:algorithm:base64 (private keys also carry public)
        byte[] bytes = Base64.getDecoder().decode(v[2].split(",")[0]);
        switch (v[0]) {
          case "secret" -> KEYS.put(alias, new SecretKeySpec(bytes, v[1]));
          case "private" -> {
            KeyFactory f = KeyFactory.getInstance(v[1]);
            KEYS.put(alias, f.generatePrivate(new PKCS8EncodedKeySpec(bytes)));
            PUBLIC.put(alias, f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(v[2].split(",")[1]))));
          }
          default -> {
            // Unknown entry from a newer format: skipped.
          }
        }
      }
    } catch (IOException | java.security.GeneralSecurityException | RuntimeException e) {
      System.err.println("[apksim] keystore file unreadable, starting empty: " + e);
    }
  }

  private static synchronized void save() {
    Properties p = new Properties();
    KEYS.forEach((alias, k) -> {
      String b64 = Base64.getEncoder().encodeToString(k.getEncoded());
      if (k instanceof SecretKey) {
        p.setProperty(alias, "secret:" + k.getAlgorithm() + ":" + b64);
      } else {
        p.setProperty(alias, "private:" + k.getAlgorithm() + ":" + b64 + "," + Base64.getEncoder().encodeToString(PUBLIC.get(alias).getEncoded()));
      }
    });
    try (OutputStream out = Files.newOutputStream(file)) {
      p.store(out, "apksim test keystore: keys in the clear");
    } catch (IOException e) {
      System.err.println("[apksim] cannot save the keystore: " + e);
    }
  }

  private static synchronized void put(String alias, Key key, PublicKey pub) {
    ensureLoaded();
    KEYS.put(alias, key);
    if (pub != null) PUBLIC.put(alias, pub);
    save();
  }

  // ---------------------------------------------------------------- JCA

  private static final class KeyProvider extends Provider {
    KeyProvider() {
      super("AndroidKeyStore", "1.0", "apksim test keystore");
      put("KeyStore.AndroidKeyStore", Store.class.getName());
      for (String a : new String[] {"AES", "HmacSHA1", "HmacSHA256", "HmacSHA384", "HmacSHA512"}) {
        put("KeyGenerator." + a, SecretGenerator.class.getName());
      }
      put("KeyPairGenerator.EC", PairGenerator.class.getName());
      put("KeyPairGenerator.RSA", PairGenerator.class.getName());
    }

    @Override
    public synchronized Service getService(String type, String algorithm) {
      // Each generator needs to know its algorithm; JCA gives the class no argument, so bind it here.
      Service s = super.getService(type, algorithm);
      if (s == null || !(type.equals("KeyGenerator") || type.equals("KeyPairGenerator"))) return s;
      return new Service(this, type, algorithm, s.getClassName(), null, null) {
        @Override
        public Object newInstance(Object param) {
          return type.equals("KeyGenerator") ? new SecretGenerator(algorithm) : new PairGenerator(algorithm);
        }
      };
    }
  }

  /** KeyStore.getInstance("AndroidKeyStore"): load(null), then entries by alias. */
  public static final class Store extends KeyStoreSpi {
    @Override
    public Key engineGetKey(String alias, char[] password) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        return KEYS.get(alias);
      }
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
      Certificate c = engineGetCertificate(alias);
      return c == null ? null : new Certificate[] {c};
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        PublicKey pub = PUBLIC.get(alias);
        return pub == null ? null : new KeyCertificate(pub);
      }
    }

    @Override
    public Date engineGetCreationDate(String alias) {
      return engineContainsAlias(alias) ? new Date() : null;
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
      put(alias, key, chain != null && chain.length > 0 ? chain[0].getPublicKey() : null);
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
      throw new UnsupportedOperationException("encoded key entries");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        PUBLIC.put(alias, cert.getPublicKey());
      }
    }

    @Override
    public void engineDeleteEntry(String alias) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        KEYS.remove(alias);
        PUBLIC.remove(alias);
        save();
      }
    }

    @Override
    public Enumeration<String> engineAliases() {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        java.util.Set<String> all = new java.util.TreeSet<>(KEYS.keySet());
        all.addAll(PUBLIC.keySet());
        return Collections.enumeration(all);
      }
    }

    @Override
    public boolean engineContainsAlias(String alias) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        return KEYS.containsKey(alias) || PUBLIC.containsKey(alias);
      }
    }

    @Override
    public int engineSize() {
      return Collections.list(engineAliases()).size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        return KEYS.containsKey(alias);
      }
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
      return !engineIsKeyEntry(alias) && engineContainsAlias(alias);
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
      synchronized (SimKeyStore.class) {
        ensureLoaded();
        for (Map.Entry<String, PublicKey> e : PUBLIC.entrySet()) if (e.getValue().equals(cert.getPublicKey())) return e.getKey();
        return null;
      }
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) {
      save();
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) {
      // Always loaded: the device's keystore is not a file the app opens.
    }
  }

  /** KeyGenerator.getInstance(alg, "AndroidKeyStore").init(KeyGenParameterSpec): a key stored under its alias. */
  public static final class SecretGenerator extends KeyGeneratorSpi {
    private final String algorithm;
    private KeyGenParameterSpec spec;
    private SecureRandom random = new SecureRandom();

    public SecretGenerator() {
      this("AES");
    }

    SecretGenerator(String algorithm) {
      this.algorithm = algorithm;
    }

    @Override
    protected void engineInit(SecureRandom r) {
      throw new IllegalStateException("AndroidKeyStore keys need a KeyGenParameterSpec");
    }

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom r) throws InvalidAlgorithmParameterException {
      if (!(params instanceof KeyGenParameterSpec s)) throw new InvalidAlgorithmParameterException("need a KeyGenParameterSpec");
      spec = s;
      if (r != null) random = r;
    }

    @Override
    protected void engineInit(int keySize, SecureRandom r) {
      throw new IllegalStateException("AndroidKeyStore keys need a KeyGenParameterSpec");
    }

    @Override
    protected SecretKey engineGenerateKey() {
      int bits = spec.getKeySize() > 0 ? spec.getKeySize() : algorithm.equals("AES") ? 256 : 256;
      byte[] bytes = new byte[bits / 8];
      random.nextBytes(bytes);
      SecretKey key = new SecretKeySpec(bytes, algorithm);
      put(spec.getKeystoreAlias(), key, null);
      return key;
    }
  }

  /** KeyPairGenerator.getInstance("EC"|"RSA", "AndroidKeyStore"): a pair whose private key is stored. */
  public static final class PairGenerator extends KeyPairGeneratorSpi {
    private final String algorithm;
    private KeyGenParameterSpec spec;

    public PairGenerator() {
      this("EC");
    }

    PairGenerator(String algorithm) {
      this.algorithm = algorithm;
    }

    @Override
    public void initialize(int keySize, SecureRandom random) {
      throw new IllegalStateException("AndroidKeyStore keys need a KeyGenParameterSpec");
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
      if (!(params instanceof KeyGenParameterSpec s)) throw new InvalidAlgorithmParameterException("need a KeyGenParameterSpec");
      spec = s;
    }

    @Override
    public KeyPair generateKeyPair() {
      try {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance(algorithm);
        AlgorithmParameterSpec inner = spec.getAlgorithmParameterSpec();
        if (inner != null) {
          g.initialize(inner);
        } else if (algorithm.equals("EC")) {
          g.initialize(new ECGenParameterSpec("secp256r1"));
        } else {
          g.initialize(new RSAKeyGenParameterSpec(spec.getKeySize() > 0 ? spec.getKeySize() : 2048, BigInteger.valueOf(65537)));
        }
        KeyPair pair = g.generateKeyPair();
        put(spec.getKeystoreAlias(), pair.getPrivate(), pair.getPublic());
        return pair;
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** What getCertificate(alias) gives back: enough to get the public key, as apps do. */
  private static final class KeyCertificate extends Certificate {
    private final PublicKey key;

    KeyCertificate(PublicKey key) {
      super("X.509");
      this.key = key;
    }

    @Override
    public byte[] getEncoded() {
      return key.getEncoded();
    }

    @Override
    public void verify(PublicKey k) {}

    @Override
    public void verify(PublicKey k, String sigProvider) {}

    @Override
    public String toString() {
      return "apksim keystore certificate for " + key.getAlgorithm() + " key";
    }

    @Override
    public PublicKey getPublicKey() {
      return key;
    }
  }
}
