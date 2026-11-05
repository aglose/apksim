package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app's content providers are created before its Application.onCreate, as on a device (AndroidX Startup relies on it). */
@RunWith(AndroidJUnit4::class)
class ContentProviderStartupTest {
  @Test
  fun theAppsProvidersAreCreatedBeforeItsOnCreate() {
    assertTrue("StartupProvider.onCreate ran before ComposeApp.onCreate", ComposeApp.providerBeforeCreate)
  }
}
