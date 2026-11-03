package dev.apksim;

import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;

/**
 * The device's fingerprint sensor, as the iOS Simulator's Face ID / Touch ID menu runs it: enrolled or not, and a
 * prompt on screen answered with a matching or non-matching finger, or cancelled. Nothing is enrolled at launch,
 * as on a fresh emulator (`--biometrics enrolled` or `biometrics enroll` changes that).
 */
final class Biometrics {
  static volatile boolean enrolled = Boolean.getBoolean("apksim.biometricsEnrolled");

  private record Request(String title, BiometricPrompt.CryptoObject crypto, Executor executor, BiometricPrompt.AuthenticationCallback callback) {}

  private static Request pending;
  private static int ticket = -1;

  private Biometrics() {}

  static synchronized void request(String title, BiometricPrompt.CryptoObject crypto, CancellationSignal cancel, Executor executor,
      BiometricPrompt.AuthenticationCallback callback) {
    if (!enrolled) {
      executor.execute(() -> callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, "No fingerprints enrolled"));
      return;
    }
    Request r = new Request(title, crypto, executor, callback);
    pending = r;
    if (cancel != null) cancel.setOnCancelListener(() -> finish(r, "cancel-by-app"));
    System.err.println("[apksim] biometric prompt: " + title + " (answer with `biometrics match`, `nomatch` or `cancel`)");
    ticket = SimWindow.ask("Fingerprint: " + title, "Touch the sensor, as the iOS Simulator's Touch ID menu does.",
        new String[] {"Matching Finger", "Non-matching Finger", "Cancel"});
  }

  /** Polled each loop turn: the window's answer to the prompt, if any. */
  static synchronized void tick() {
    if (pending == null || ticket < 0) return;
    int a = SimWindow.askAnswer(ticket);
    if (a < 0) return;
    ticket = -1;
    finish(pending, a == 0 ? "match" : a == 1 ? "nomatch" : "cancel");
  }

  /** biometrics [enroll|unenroll|match|nomatch|cancel] */
  static synchronized String run(String[] a) {
    String op = a.length > 1 ? a[1] : "";
    switch (op) {
      case "enroll", "unenroll" -> {
        enrolled = op.equals("enroll");
        return "ok";
      }
      case "match", "nomatch", "cancel" -> {
        if (pending == null) return "no biometric prompt is showing";
        if (ticket >= 0) SimWindow.dismissAsk();
        ticket = -1;
        finish(pending, op);
        return "ok";
      }
      default -> {
        return (enrolled ? "enrolled" : "not enrolled") + (pending == null ? "" : "; prompt showing: " + pending.title());
      }
    }
  }

  private static synchronized void finish(Request r, String how) {
    if (pending != r) return;
    boolean keepShowing = how.equals("nomatch");  // a wrong finger leaves the prompt up, as on a device
    if (!keepShowing) pending = null;
    BiometricPrompt.AuthenticationCallback cb = r.callback();
    switch (how) {
      case "match" -> {
        BiometricPrompt.AuthenticationResult result = new BiometricPrompt.AuthenticationResult(r.crypto(),
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC);
        r.executor().execute(() -> cb.onAuthenticationSucceeded(result));
      }
      case "nomatch" -> {
        r.executor().execute(cb::onAuthenticationFailed);
        if (ticket < 0 && !SimWindow.isHeadless()) {
          ticket = SimWindow.ask("Fingerprint: " + r.title(), "Not recognized. Try again.",
              new String[] {"Matching Finger", "Non-matching Finger", "Cancel"});
        }
      }
      case "cancel" -> r.executor().execute(() -> cb.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "Cancelled"));
      default -> {
        // Cancelled by the app: it asked, so it is not told again.
      }
    }
  }

  static int canAuthenticate() {
    return enrolled ? BiometricManager.BIOMETRIC_SUCCESS : BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
  }
}
