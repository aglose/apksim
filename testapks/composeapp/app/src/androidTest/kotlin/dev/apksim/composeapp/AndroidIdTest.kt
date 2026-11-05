package dev.apksim.composeapp

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A device always has an ANDROID_ID; an app that reads it as a device id (into a non-null String) must get one. */
@RunWith(AndroidJUnit4::class)
class AndroidIdTest {
  @Test
  fun androidIdIsSetAndStable() {
    val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    val id = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID)
    assertNotNull("ANDROID_ID", id)
    assertTrue("16 hex digits, as on a device: $id", id!!.matches(Regex("[0-9a-f]{16}")))
    assertEquals(id, Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID))
  }

  /** As on a device, it is there before the app starts: the app's Application (ComposeApp) reads it in onCreate. */
  @Test
  fun androidIdIsSetBeforeTheApplicationStarts() {
    val cr = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    assertEquals(Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID), ComposeApp.androidIdAtCreate)
    assertNotNull("ANDROID_ID in Application.onCreate", ComposeApp.androidIdAtCreate)
  }
}
