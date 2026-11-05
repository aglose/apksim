package dev.apksim.composeapp

import android.app.AlertDialog
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.base.DefaultFailureHandler
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.Statement

/**
 * Tests that fail on purpose, for checking apksim's failure bundles (`--bundles DIR`, scripts/bundle-check.py). They run
 * only with `-e failOnPurpose true` (the timeout that ends the process only with `-e failOnPurpose watchdog`) and are
 * skipped otherwise, on a device and in normal runs. Each logs a marker, so a check can tell that a bundle's log holds
 * only its own test's lines.
 */
private fun failOnPurpose(kind: String = "true") =
  assumeTrue(InstrumentationRegistry.getArguments().getString("failOnPurpose") == kind)

private fun marker(name: String) = Log.i("BundleTest", "marker $name")

@RunWith(AndroidJUnit4::class)
class FailureBundleTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  /** Passes: no bundle (without --bundles-on all). */
  @Test
  fun a_passes() {
    failOnPurpose()
    marker("a_passes")
    compose.onNodeWithText("FAB clicks: 0").assertIsDisplayed()
  }

  /** A Compose assertion failing in the test body: the screen still shows "FAB clicks: 0". */
  @Test
  fun b_composeAssertionFails() {
    failOnPurpose()
    marker("b_composeAssertionFails")
    compose.onNodeWithText("FAB clicks: 99").assertIsDisplayed()
  }

  /** A failure while a dialog is open over the activity: two windows, the dialog's title in the tree. */
  @Test
  fun c_failsWithDialogOpen() {
    failOnPurpose()
    marker("c_failsWithDialogOpen")
    compose.runOnUiThread {
      AlertDialog.Builder(compose.activity).setTitle("Bundle dialog").setMessage("Open while the test fails").show()
    }
    compose.waitForIdle()
    fail("failing with a dialog open")
  }
}

/** Plain Espresso in an ActivityScenario: NoMatchingViewException from the test body. */
@RunWith(AndroidJUnit4::class)
class FailureBundleEspressoTest {
  @Test
  fun noMatchingView() {
    failOnPurpose()
    marker("noMatchingView")
    ActivityScenario.launch(MainActivity::class.java).use {
      onView(withText("No view has this text")).check(matches(isDisplayed()))
    }
  }
}

/**
 * A test's own Espresso failure handler still decides (here: it swallows the failure, so the test passes), with or
 * without --bundles, which wraps the handler it finds at the start of the test body. Runs everywhere.
 */
@RunWith(AndroidJUnit4::class)
class FailureBundleCustomHandlerTest {
  @Before
  fun swallowFailures() {
    Espresso.setFailureHandler { _, _ -> }
  }

  @After
  fun restoreHandler() {
    Espresso.setFailureHandler(DefaultFailureHandler(InstrumentationRegistry.getInstrumentation().targetContext))
  }

  @Test
  fun ownHandlerDecides() {
    ActivityScenario.launch(MainActivity::class.java).use {
      onView(withText("No view has this text")).check(matches(isDisplayed()))
    }
  }
}

/**
 * A body that throws what @Test(expected) expects passes. In a failures-only run apksim must not even start a bundle
 * for it: this @After runs before the test's end could delete one, and fails if its class folder exists in the
 * bundle folder (`-e bundleDir DIR`, as apksim sees it). With `-e bundlesAll true` (a --bundles-on all run) the check
 * is off: there the test ends with a bundle that says passed, which bundle-check.py reads.
 */
@RunWith(AndroidJUnit4::class)
class FailureBundleExpectedTest {
  @After
  fun noBundleStarted() {
    val args = InstrumentationRegistry.getArguments()
    val dir = args.getString("bundleDir") ?: return
    if (args.getString("bundlesAll") == "true") return
    val folder = File(dir, FailureBundleExpectedTest::class.java.name)
    if (folder.exists()) fail("a bundle was started for a test that threw what it expected: $folder")
  }

  @Test(expected = IllegalStateException::class)
  fun expectedThrows() {
    failOnPurpose()
    marker("expectedThrows")
    throw IllegalStateException("expected, on purpose")
  }
}

/** The test passes and its @After fails: only the listener sees it, after teardown. */
@RunWith(AndroidJUnit4::class)
class FailureBundleAfterTest {
  @After
  fun tearDown() {
    if (InstrumentationRegistry.getArguments().getString("failOnPurpose") == "true") fail("@After fails on purpose")
  }

  @Test
  fun afterFails() {
    marker("afterFails")
  }
}

/** A class with its own runner (not SimRunner): its failures reach only the listener. Names with commas. */
@RunWith(Parameterized::class)
class FailureBundleParameterizedTest(private val a: Int, private val b: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{index}: {0}, {1}")
    fun cases() = listOf(arrayOf(1, 1), arrayOf(1, 2))
  }

  @Test
  fun sameNumbers() {
    failOnPurpose()
    marker("sameNumbers $a $b")
    assertEquals(a, b)
  }
}

/** Ignores interrupts past its timeout, so the watchdog ends the process: run alone, with -e failOnPurpose watchdog. */
@RunWith(AndroidJUnit4::class)
class FailureBundleWatchdogTest {
  @Test
  fun ignoresInterrupts() {
    failOnPurpose("watchdog")
    marker("ignoresInterrupts")
    val stuck = object : Statement() {
      override fun evaluate() {
        while (true) {
          Thread.interrupted()
          LockSupport.parkNanos(50_000_000)
        }
      }
    }
    Timeout.millis(300).apply(stuck, Description.EMPTY).evaluate()
  }
}
