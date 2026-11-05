package dev.apksim.composeapp

import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The app's network replayed from a recording (docs/network.md): run with `-e networkReplay true` and
 * `--network replay app/src/androidTest/network/greeting.har`; skipped otherwise. With `-e unmatchedOnPurpose true`
 * as well, z_anUnmatchedRequestLeftUnclearedFailsItsTest makes a request the recording lacks and must fail.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NetworkReplayTest {
  private val args get() = InstrumentationRegistry.getArguments()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
  }

  @Before
  fun onlyWhenReplaying() = assumeTrue(args.getString("networkReplay") == "true")

  @Test
  fun okHttpGetsTheRecordedAnswer() {
    val r = GreetingClient.fetch()
    assertEquals(200, r.code)
    assertEquals("Hello, apksim!", r.greeting)
    assertEquals("recorded", r.replay)
  }

  @Test
  fun httpUrlConnectionGetsTheRecordedAnswer() {
    val r = GreetingClient.fetchWithUrlConnection()
    assertEquals(200, r.code)
    assertEquals("Plain hello", r.greeting)
  }

  @Test
  fun offlineFailsLookupsAndTakesTheNetworkAway() {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    assertNotNull(cm.activeNetwork)
    var lost = 0
    var available = 0
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onLost(network: Network) { lost++ }
      override fun onAvailable(network: Network) { available++ }
    }
    cm.registerDefaultNetworkCallback(callback)
    val availableAtStart = available  // as on a device, registering may report the current network at once
    try {
      shell("apksim network offline")
      assertEquals("onLost calls", 1, lost)
      assertNull(cm.activeNetwork)
      val e = runCatching { GreetingClient.fetch() }.exceptionOrNull()
      assertTrue("offline gave $e", e is UnknownHostException || e is IOException)
      assertTrue(runCatching { GreetingClient.fetchWithUrlConnection() }.exceptionOrNull() is UnknownHostException)
      shell("apksim network online")
      assertEquals("onAvailable calls after coming back", availableAtStart + 1, available)
      assertNotNull(cm.activeNetwork)
      assertEquals(200, GreetingClient.fetch().code)
    } finally {
      cm.unregisterNetworkCallback(callback)
    }
  }

  /** Run with `--network-profile offline` as well: the Application started with no network. */
  @Test
  fun startsOfflineWhenTheProfileSaysSo() {
    assumeTrue(args.getString("networkProfile") == "offline")
    assertEquals("none", ComposeApp.networkAtStart)
    assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
  }

  /** Conditions a test sets are undone before the next one: this runs after the offline test (name order). */
  @Test
  fun p_aTestStartsOnline() {
    assertNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
    assertEquals(200, GreetingClient.fetch().code)
  }

  @Test
  fun anUnmatchedRequestIsAnswered599AndListed() {
    val r = GreetingClient.fetch("/v1/missing")
    assertEquals(599, r.code)
    assertEquals("unmatched", r.replay)
    val listed = shell("apksim network unmatched clear")
    assertTrue(listed, listed.contains("POST https://api.apksim.test/v1/missing"))
  }

  @Test
  fun z_anUnmatchedRequestLeftUnclearedFailsItsTest() {
    assumeTrue(args.getString("unmatchedOnPurpose") == "true")
    assertEquals(599, GreetingClient.fetch("/v1/elsewhere").code)
  }
}
