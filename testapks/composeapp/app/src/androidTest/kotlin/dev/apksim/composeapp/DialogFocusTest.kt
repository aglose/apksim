package dev.apksim.composeapp

import android.app.AlertDialog
import android.app.Dialog
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A dialog takes the window focus from its activity, as on a device, so Espresso finds the dialog's views without
 * being told which window to look in; when it goes, the activity has the focus again.
 */
@RunWith(AndroidJUnit4::class)
class DialogFocusTest {
  @Test
  fun aDialogTakesTheFocusAndGivesItBack() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var dialog: Dialog
      scenario.onActivity { dialog = AlertDialog.Builder(it).setMessage("In a dialog").show() }
      onView(withText("In a dialog")).check(matches(isDisplayed()))
      scenario.onActivity { assertFalse("the activity lost the focus", it.window.decorView.hasWindowFocus()) }
      scenario.onActivity { dialog.dismiss() }
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      scenario.onActivity { assertTrue("the activity has the focus back", it.window.decorView.hasWindowFocus()) }
    }
  }
}
