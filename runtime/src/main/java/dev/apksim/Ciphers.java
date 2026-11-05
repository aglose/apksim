package dev.apksim;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cipher.init from app code (MethodTranslator). The JDK's AES/GCM refuses to encrypt again with the key and IV it
 * last encrypted with ("Cannot reuse iv for GCM encryption"); Android's provider lets an app do it. One app keeps a
 * single AES/GCM cipher with a fixed key and IV for everything it writes encrypted (several values at startup), works
 * on its devices, and here threw from the second write on, failing every test that started it. So when the JDK refuses
 * for that reason only, the cipher is first set up with another IV, which is what the JDK compares against, and then
 * as asked. Reusing a GCM key and IV is unsafe, and Android may refuse it too for newer apps; this only matches what
 * the app gets on a device.
 */
public final class Ciphers {
  private Ciphers() {}

  public static void init(Cipher cipher, int mode, Key key, AlgorithmParameterSpec params)
      throws InvalidKeyException, InvalidAlgorithmParameterException {
    try {
      cipher.init(mode, key, params);
    } catch (InvalidAlgorithmParameterException e) {
      if (!reusedGcmIv(e, mode, params)) throw e;
      cipher.init(mode, key, other((GCMParameterSpec) params));
      cipher.init(mode, key, params);
    }
  }

  public static void init(Cipher cipher, int mode, Key key, AlgorithmParameterSpec params, SecureRandom random)
      throws InvalidKeyException, InvalidAlgorithmParameterException {
    try {
      cipher.init(mode, key, params, random);
    } catch (InvalidAlgorithmParameterException e) {
      if (!reusedGcmIv(e, mode, params)) throw e;
      cipher.init(mode, key, other((GCMParameterSpec) params), random);
      cipher.init(mode, key, params, random);
    }
  }

  private static boolean reusedGcmIv(InvalidAlgorithmParameterException e, int mode, AlgorithmParameterSpec params) {
    return (mode == Cipher.ENCRYPT_MODE || mode == Cipher.WRAP_MODE) && params instanceof GCMParameterSpec
        && e.getMessage() != null && e.getMessage().contains("reuse iv");
  }

  /** A spec like {@code spec} with a different IV of the same length. */
  private static GCMParameterSpec other(GCMParameterSpec spec) {
    byte[] iv = spec.getIV();
    for (int i = 0; i < iv.length; i++) iv[i] = (byte) ~iv[i];
    return new GCMParameterSpec(spec.getTLen(), iv);
  }
}
