package dev.apksim;

import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/**
 * BiometricPrompt without a biometric service: every authenticate() variant ends in authenticateInternal, which hands
 * the request to {@link Biometrics} to be answered as the iOS Simulator's Face ID menu answers it (matching,
 * non-matching, cancel). androidx.biometric uses this platform prompt on API 28+.
 */
@Implements(BiometricPrompt.class)
public class ShadowSimBiometricPrompt {
  @RealObject private BiometricPrompt real;

  @Implementation
  protected void authenticateInternal(BiometricPrompt.CryptoObject crypto, CancellationSignal cancel, Executor executor,
      BiometricPrompt.AuthenticationCallback callback, int userId) {
    Biometrics.request(String.valueOf(real.getTitle()), crypto, cancel, executor, callback);
  }

  @Implementation
  protected long authenticateInternal(long operationId, CancellationSignal cancel, Executor executor,
      BiometricPrompt.AuthenticationCallback callback, int userId) {
    Biometrics.request(String.valueOf(real.getTitle()), null, cancel, executor, callback);
    return 0;
  }
}
