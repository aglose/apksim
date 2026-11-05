package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The default keystore type is Android's, BKS: a keystore an app ships in that format loads with it. */
@RunWith(AndroidJUnit4::class)
class KeyStoreTest {
  @Test
  fun theDefaultKeyStoreTypeIsBksAndLoads() {
    assertEquals("BKS", KeyStore.getDefaultType())
    val password = "secret".toCharArray()
    val written = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, password)
      setEntry("k", KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(16) { 7 }, "AES")), KeyStore.PasswordProtection(password))
    }
    val bytes = ByteArrayOutputStream().also { written.store(it, password) }.toByteArray()
    val read = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(ByteArrayInputStream(bytes), password) }
    assertNotNull(read.getKey("k", password))
  }
}
