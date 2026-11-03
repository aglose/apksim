package dev.apksim;

import android.hardware.biometrics.BiometricManager;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBiometricManager;

/**
 * A fingerprint sensor with nothing enrolled, as on a fresh emulator. Robolectric reports biometrics as ready,
 * so apps offered biometric sign-in that could never complete here. Enrolling (`biometrics enroll`, the Mac menu)
 * makes it ready, and {@link ShadowSimBiometricPrompt} then answers prompts.
 */
@Implements(BiometricManager.class)
public class ShadowNoBiometrics extends ShadowBiometricManager {
  @Implementation
  @Override
  protected int canAuthenticate() {
    return Biometrics.canAuthenticate();
  }

  @Implementation
  @Override
  protected int canAuthenticate(int authenticators) {
    return Biometrics.canAuthenticate();
  }

  @Implementation
  @Override
  protected int canAuthenticate(int userId, int authenticators) {
    return Biometrics.canAuthenticate();
  }
}
