package dev.apksim.composeapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OAuth SDKs (AppAuth, Auth0) look for a browser before they start. The device has one: the probes they send resolve
 * to it with its filter, its signatures can be read, and it offers no Custom Tabs service (SDKs then use a plain VIEW).
 * Each test starts from a reset device, so both run the registration again.
 */
@RunWith(AndroidJUnit4::class)
class BrowserDiscoveryTest {
  private val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager

  private fun probe(data: Uri, browsable: Boolean) =
    Intent(Intent.ACTION_VIEW, data).apply { if (browsable) addCategory(Intent.CATEGORY_BROWSABLE) }

  private fun check() {
    // The stand-in browser is apksim's: a device has its own (Chrome offers a Custom Tabs service, several may match).
    assumeTrue(Build.FINGERPRINT == "robolectric")
    val probes = listOf(
      Uri.parse("http://www.example.com"),
      Uri.parse("https://www.example.com"),
      Uri.fromParts("http", "", null), // AppAuth's BrowserSelector
    )
    for (data in probes) for (browsable in listOf(false, true)) {
      @Suppress("DEPRECATION")
      val found = pm.queryIntentActivities(probe(data, browsable), PackageManager.GET_RESOLVED_FILTER)
      assertEquals("$data browsable=$browsable", 1, found.size)
      assertNotNull("filter for $data", found[0].filter)
      assertTrue(found[0].filter.hasDataScheme(data.scheme))
      @Suppress("DEPRECATION")
      val info = pm.getPackageInfo(found[0].activityInfo.packageName, PackageManager.GET_SIGNATURES)
      assertNotNull(info.signatures)
    }
    val tabs = pm.queryIntentServices(Intent("android.support.customtabs.action.CustomTabsService"), 0)
    assertTrue(tabs.isEmpty())
  }

  @Test fun probesResolveToABrowser() = check()

  @Test fun againAfterTheDeviceWasReset() = check()
}
