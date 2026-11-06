package dev.apksim.composeapp

import android.app.AlertDialog
import android.util.Log
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
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
import org.junit.Assert.assertFalse
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

  /**
   * The same with a dialog-themed activity over MainActivity: both windows are captured, and each one's tree is read as
   * its own scenario closes (FloatingActivity's first, while MainActivity's is still in use).
   */
  @Test
  fun noMatchingViewOverFloatingActivity() {
    failOnPurpose()
    marker("noMatchingViewOverFloatingActivity")
    ActivityScenario.launch(MainActivity::class.java).use {
      ActivityScenario.launch(FloatingActivity::class.java).use {
        onView(withText("No view has this text")).check(matches(isDisplayed()))
      }
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
 * A failure the test handles (a poll that retries, as an `idleUntil` helper does) leaves the app as it was, with or
 * without --bundles. Espresso's failure handler captures a bundle at the first failed poll; reading the accessibility
 * tree then (nodes.txt) would change the screen the test goes on using: Compose's node provider adds a child view to
 * the AndroidComposeView (its AndroidViewsHandler) and builds its accessibility caches. Runs everywhere.
 */
@RunWith(AndroidJUnit4::class)
class FailureBundleHandledTest {
  private fun composeChildren(scenario: ActivityScenario<MainActivity>): Int {
    var n = -1
    scenario.onActivity { a ->
      val composeView = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
      n = (composeView.getChildAt(0) as ViewGroup).childCount  // the AndroidComposeView
    }
    return n
  }

  private fun pollUntil(attempts: Int, check: () -> Unit): Boolean {
    repeat(attempts) {
      try {
        check()
        return true
      } catch (e: NoMatchingViewException) {
        // not there yet: poll again
      }
    }
    return false
  }

  @Test
  fun handledFailureLeavesTheScreenAlone() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      val before = composeChildren(scenario)
      assertFalse(pollUntil(3) { onView(withText("No view has this text")).check(matches(isDisplayed())) })
      assertEquals("the AndroidComposeView's child views after a handled failure", before, composeChildren(scenario))
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

/** Parameterized: each parameter set runs as a SimRunner child (SimParameterized), captured in its body. Names with commas. */
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
