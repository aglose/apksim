package dev.apksim.composeapp.conformance

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.fragment as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class FragmentConformanceTest {
  class TextFragment : Fragment() {
    var restored: String? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
      TextView(inflater.context).apply { text = requireArguments().getString("text") }
    override fun onCreate(state: Bundle?) {
      super.onCreate(state)
      restored = state?.getString("saved")
    }
    override fun onSaveInstanceState(outState: Bundle) {
      super.onSaveInstanceState(outState)
      outState.putString("saved", "state of " + requireArguments().getString("text"))
    }
  }

  class SimpleDialog : DialogFragment() {
    override fun onCreateDialog(state: Bundle?): Dialog =
      AlertDialog.Builder(requireContext()).setMessage("conformance dialog").setPositiveButton("OK", null).create()
  }

  private fun text(t: String) = bundleOf("text" to t)

  /** Moving the fragment to CREATED destroys its view: the view lifecycle ends, the fragment's does not. */
  @Test
  fun createdStateDestroysTheView() {
    launchFragmentInContainer<TextFragment>(text("one")).use { scenario ->
      onView(withText("one")).check(matches(isDisplayed()))
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onFragment { f ->
        assertNull(f.view)
        assertEquals(Lifecycle.State.CREATED, f.lifecycle.currentState)
      }
      onView(withText("one")).check(doesNotExist())
    }
  }

  /** recreate() builds a new fragment from its arguments and saved state. */
  @Test
  fun recreateRestoresArgumentsAndState() {
    launchFragmentInContainer<TextFragment>(text("two")).use { scenario ->
      scenario.recreate()
      scenario.onFragment { f -> assertEquals("state of two", f.restored) }
      onView(withText("two")).check(matches(isDisplayed()))
    }
  }

  /** A transaction added to the back stack is popped by back, before the activity sees it. */
  @Test
  fun backPopsTheBackStack() {
    launchFragmentInContainer<TextFragment>(text("first")).use { scenario ->
      scenario.onFragment { f ->
        f.parentFragmentManager.beginTransaction()
          .replace(android.R.id.content, TextFragment().apply { arguments = text("second") })
          .addToBackStack("second")
          .commit()
      }
      onView(withText("second")).check(matches(isDisplayed()))
      Espresso.pressBack()
      onView(withText("first")).check(matches(isDisplayed()))
      onView(withText("second")).check(doesNotExist())
      scenario.onFragment { f -> assertEquals(0, f.parentFragmentManager.backStackEntryCount) }
    }
  }

  /** setFragmentResult reaches the listener registered for its key on the same FragmentManager. */
  @Test
  fun fragmentResultReachesItsListener() {
    launchFragmentInContainer<TextFragment>(text("results")).use { scenario ->
      var got: String? = null
      scenario.onFragment { f ->
        f.setFragmentResultListener("key") { _, b -> got = b.getString("v") }
        f.setFragmentResult("key", bundleOf("v" to "answer"))
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals("answer", got)
    }
  }

  /** A result set while the listener is stopped waits until it is STARTED again. */
  @Test
  fun fragmentResultWaitsForStarted() {
    launchFragmentInContainer<TextFragment>(text("waits")).use { scenario ->
      var got: String? = null
      scenario.onFragment { f -> f.setFragmentResultListener("key") { _, b -> got = b.getString("v") } }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onFragment { f -> f.parentFragmentManager.setFragmentResult("key", bundleOf("v" to "later")) }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertNull(got)
      scenario.moveToState(Lifecycle.State.RESUMED)
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      assertEquals("later", got)
    }
  }

  /** A DialogFragment's dialog is a window of its own that Espresso finds, and back dismisses it. */
  @Test
  fun dialogFragmentShowsAndBackDismissesIt() {
    launchFragmentInContainer<TextFragment>(text("host")).use { scenario ->
      lateinit var dialog: SimpleDialog
      scenario.onFragment { f ->
        dialog = SimpleDialog()
        dialog.show(f.parentFragmentManager, "dialog")
      }
      onView(withText("conformance dialog")).inRoot(isDialog()).check(matches(isDisplayed()))
      Espresso.pressBack()
      onView(withText("conformance dialog")).check(doesNotExist())
      scenario.onFragment { f ->
        assertNull(f.parentFragmentManager.findFragmentByTag("dialog"))
        assertFalse(dialog.isAdded)
      }
    }
  }

  /** A child fragment follows its parent's lifecycle. */
  @Test
  fun childFragmentFollowsItsParent() {
    launchFragmentInContainer<TextFragment>(text("parent")).use { scenario ->
      lateinit var child: TextFragment
      scenario.onFragment { f ->
        child = TextFragment().apply { arguments = text("child") }
        f.childFragmentManager.beginTransaction().add(child, "child").commitNow()
        assertEquals(Lifecycle.State.RESUMED, child.lifecycle.currentState)
      }
      scenario.moveToState(Lifecycle.State.STARTED)
      scenario.onFragment { assertEquals(Lifecycle.State.STARTED, child.lifecycle.currentState) }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onFragment { assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState) }
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onFragment { f ->
        assertNotNull(f.childFragmentManager.findFragmentByTag("child"))
        assertTrue(child.isResumed)
      }
    }
  }

  /** A non-cancelable dialog ignores back. */
  @Test
  fun nonCancelableDialogIgnoresBack() {
    launchFragmentInContainer<TextFragment>(text("host")).use { scenario ->
      scenario.onFragment { f -> SimpleDialog().apply { isCancelable = false }.show(f.parentFragmentManager, "dialog") }
      onView(withText("conformance dialog")).inRoot(isDialog()).check(matches(isDisplayed()))
      Espresso.pressBack()
      onView(withText("conformance dialog")).inRoot(isDialog()).check(matches(isDisplayed()))
    }
  }

  /** A back callback tied to the fragment's view lifecycle gets back while the view exists, and not after. */
  @Test
  fun backCallbackFollowsTheViewLifecycle() {
    launchFragmentInContainer<TextFragment>(text("callback")).use { scenario ->
      var backs = 0
      scenario.onFragment { f ->
        f.requireActivity().onBackPressedDispatcher.addCallback(f.viewLifecycleOwner, object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() { backs++ }
        })
      }
      Espresso.pressBack()
      assertEquals(1, backs)
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onFragment { f -> assertFalse(f.requireActivity().onBackPressedDispatcher.hasEnabledCallbacks()) }
    }
  }
}
