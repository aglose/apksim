package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One AES/GCM cipher with a fixed key and IV for every value an app encrypts, as one app does at startup: Android's
 * provider lets it be set up again with the same key and IV (unsafe, but that is what the app gets on a device).
 */
@RunWith(AndroidJUnit4::class)
class CipherTest {
  @Test
  fun aesGcmCanBeSetUpAgainWithTheSameKeyAndIv() {
    val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
    val spec = GCMParameterSpec(128, ByteArray(12) { (it * 3).toByte() })
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val sealed = (1..3).map {
      cipher.init(Cipher.ENCRYPT_MODE, key, spec)
      cipher.doFinal("value".toByteArray())
    }
    // The same key, IV and data: the same ciphertext each time, and it decrypts.
    assertArrayEquals(sealed[0], sealed[2])
    cipher.init(Cipher.DECRYPT_MODE, key, spec)
    assertArrayEquals("value".toByteArray(), cipher.doFinal(sealed[1]))
  }
}
