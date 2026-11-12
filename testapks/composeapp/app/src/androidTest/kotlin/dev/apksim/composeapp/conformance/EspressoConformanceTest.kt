package dev.apksim.composeapp.conformance

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.AmbiguousViewMatcherException
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onIdle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.idling.CountingIdlingResource
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Espresso (core, intents, idling resources) as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class EspressoConformanceTest {
  /** Views made in code: a field, a status line, a button, and a long list or spinner when asked. */
  class Screen : Fragment() {
    lateinit var status: TextView
    var picked: Uri? = null
    val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { picked = it; status.text = "picked $it" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
      val c = inflater.context
      // Below the system bars (a device's test runs have them, apksim's none), with no action bar over it.
      val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
      status = TextView(c).apply { id = STATUS; text = "ready" }
      column.addView(status)
      column.addView(EditText(c).apply { id = FIELD; hint = "type here" })
      column.addView(Button(c).apply { id = BUTTON; text = "Go"; setOnClickListener { status.text = "clicked" } })
      when (arguments?.getString("with")) {
        "list" -> column.addView(ListView(c).apply {
          id = LIST
          adapter = ArrayAdapter(c, android.R.layout.simple_list_item_1, (0 until 200).map { "item $it" })
          setOnItemClickListener { _, _, position, _ -> status.text = "item $position clicked" }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        "spinner" -> column.addView(Spinner(c, Spinner.MODE_DROPDOWN).apply {
          id = SPINNER
          adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, listOf("red", "green", "blue"))
        })
        "scroll" -> column.addView(ScrollView(c).apply {
          addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            repeat(40) { i -> addView(TextView(c).apply { text = "filler $i"; minHeight = 120 }) }
            addView(Button(c).apply { id = FAR; text = "Far"; setOnClickListener { status.text = "far clicked" } })
          })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        "twins" -> repeat(2) { column.addView(TextView(c).apply { text = "twin" }) }
      }
      return column
    }
  }

  companion object {
    val STATUS = View.generateViewId()
    val FIELD = View.generateViewId()
    val BUTTON = View.generateViewId()
    val LIST = View.generateViewId()
    val SPINNER = View.generateViewId()
    val FAR = View.generateViewId()
  }

  private var scenario: FragmentScenario<Screen>? = null

  private fun launch(with: String? = null) =
    launchFragmentInContainer<Screen>(Bundle().apply { putString("with", with) }, android.R.style.Theme_Material_Light_NoActionBar)
      .also { scenario = it }

  /** Each test's activity ends with it, so a later test (ProcessLifecycleOwner's) sees none left resumed. */
  @After
  fun close() {
    scenario?.close()
  }

  /** typeText types into the field, closeSoftKeyboard closes what it opened, and the click lands. */
  @Test
  fun typeTextAndClick() {
    launch()
    onView(withId(FIELD)).perform(typeText("hello"), closeSoftKeyboard())
    onView(withId(FIELD)).check(matches(withText("hello")))
    onView(withId(BUTTON)).perform(click())
    onView(withId(STATUS)).check(matches(withText("clicked")))
  }

  /** Espresso waits for a registered idling resource to go idle before it checks. */
  @Test
  fun anIdlingResourceHoldsEspresso() {
    val scenario = launch()
    val busy = CountingIdlingResource("conformance")
    IdlingRegistry.getInstance().register(busy)
    try {
      busy.increment()
      scenario.onFragment { f ->
        Thread {
          Thread.sleep(500)
          Handler(Looper.getMainLooper()).post { f.status.text = "loaded"; busy.decrement() }
        }.start()
      }
      onView(withId(STATUS)).check(matches(withText("loaded")))
    } finally {
      IdlingRegistry.getInstance().unregister(busy)
    }
  }

  /** onIdle does not wait for a message posted to run later (only for what is due). */
  @Test
  fun onIdleDoesNotWaitForADelayedPost() {
    launch()
    var ran = false
    Handler(Looper.getMainLooper()).postDelayed({ ran = true }, 2_000)
    onIdle()
    assertFalse("onIdle waited for a post due in 2 s", ran)
  }

  /** onData scrolls an AdapterView to a row that is not on screen and clicks it. */
  @Test
  fun onDataClicksARowOffScreen() {
    launch("list")
    onData(allOf(instanceOf(String::class.java), `is`("item 150"))).inAdapterView(withId(LIST)).perform(click())
    onView(withId(STATUS)).check(matches(withText("item 150 clicked")))
  }

  /** A Spinner's dropdown is a platform popup, found with inRoot(isPlatformPopup()). */
  @Test
  fun aSpinnerDropdownIsAPlatformPopup() {
    val scenario = launch("spinner")
    onView(withId(SPINNER)).perform(click())
    onData(allOf(instanceOf(String::class.java), `is`("blue"))).inRoot(isPlatformPopup()).perform(click())
    scenario.onFragment { f -> assertEquals("blue", f.view!!.findViewById<Spinner>(SPINNER).selectedItem) }
  }

  /** An AlertDialog's buttons are found in the dialog's root, and pressBack closes it. */
  @Test
  fun aDialogsButtonsAreInItsRoot() {
    val scenario = launch()
    scenario.onFragment { f ->
      AlertDialog.Builder(f.requireContext()).setMessage("Sure?")
        .setPositiveButton("Yes") { _, _ -> f.status.text = "yes" }.show()
    }
    onView(withText("Sure?")).inRoot(isDialog()).check(matches(isDisplayed()))
    onView(withText("Yes")).inRoot(isDialog()).perform(click())
    onView(withId(STATUS)).check(matches(withText("yes")))
    scenario.onFragment { f -> AlertDialog.Builder(f.requireContext()).setMessage("Again?").show() }
    onView(withText("Again?")).inRoot(isDialog()).check(matches(isDisplayed()))
    pressBack()
    onView(withText("Again?")).check(doesNotExist())
  }

  /** scrollTo brings a view inside a ScrollView on screen before the click. */
  @Test
  fun scrollToReachesAFarButton() {
    launch("scroll")
    onView(withId(FAR)).perform(scrollTo(), click())
    onView(withId(STATUS)).check(matches(withText("far clicked")))
  }

  /** A matcher with no match throws NoMatchingViewException; one with two, AmbiguousViewMatcherException. */
  @Test
  fun missingAndAmbiguousMatches() {
    launch("twins")
    try {
      onView(withText("nowhere")).check(matches(isDisplayed()))
      fail("matched nothing without an exception")
    } catch (e: NoMatchingViewException) {
      // as documented
    }
    try {
      onView(withText("twin")).check(matches(isDisplayed()))
      fail("matched two without an exception")
    } catch (e: AmbiguousViewMatcherException) {
      // as documented
    }
  }

  /** espresso-intents: a stubbed start is recorded and not launched; its result reaches the caller. */
  @Test
  fun intentsStubAndRecordStarts() {
    val scenario = launch()
    Intents.init()
    try {
      val uri = Uri.parse("https://example.com/page")
      intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
      scenario.onFragment { f -> f.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
      intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(uri)))
      val content = Uri.parse("content://dev.apksim.conformance/picked")
      intending(hasAction(Intent.ACTION_GET_CONTENT))
        .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(content)))
      scenario.onFragment { f -> f.pick.launch("image/*") }
      onView(withId(STATUS)).check(matches(withText("picked $content")))
    } finally {
      Intents.release()
    }
  }

  /** replaceText sets a field's text without typing, as documented for fields typeText cannot reach. */
  @Test
  fun replaceTextSetsTheField() {
    launch()
    onView(withId(FIELD)).perform(replaceText("set"))
    onView(withId(FIELD)).check(matches(withText("set")))
  }
}
