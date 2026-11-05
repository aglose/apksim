package dev.apksim.composeapp

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The machine's network is the device's connected network: the active network has INTERNET and is VALIDATED, as an
 * app checks before it makes a request.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityTest {
  @Test
  fun theActiveNetworkIsValidated() {
    val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    assertNotNull("capabilities of the active network", caps)
    assertTrue(caps.toString(), caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    assertTrue(caps.toString(), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
  }
}
