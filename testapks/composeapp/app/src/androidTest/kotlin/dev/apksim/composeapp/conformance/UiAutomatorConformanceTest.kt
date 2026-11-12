package dev.apksim.composeapp.conformance

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.test.uiautomator as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class UiAutomatorConformanceTest {
  class Screen : Fragment() {
    lateinit var status: TextView
    var taps = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
      val c = inflater.context
      // Below the system bars (a device's test runs have them, apksim's none), with no action bar over it.
      val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
      status = TextView(c).apply { text = "taps 0"; contentDescription = "status" }
      column.addView(status)
      column.addView(Button(c).apply { text = "Tap me"; isAllCaps = false; setOnClickListener { status.text = "taps ${++taps}" } })
      column.addView(EditText(c).apply { hint = "uia field"; contentDescription = "uia field" })
      column.addView(ScrollView(c).apply {
        contentDescription = "rows"
        addView(LinearLayout(c).apply {
          orientation = LinearLayout.VERTICAL
          repeat(80) { i -> addView(TextView(c).apply { text = "row $i"; minHeight = 120 }) }
        })
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
      return column
    }
  }

  private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  private var scenario: FragmentScenario<Screen>? = null

  private fun launch() = launchFragmentInContainer<Screen>(themeResId = android.R.style.Theme_Material_Light_NoActionBar)
    .also { scenario = it }

  /** What UiAutomator sees, for a failure's message. */
  private fun seen(): String = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString("UTF-8")
    .lines().mapNotNull { Regex("(text|content-desc)=\"([^\"]+)\"").findAll(it).map { m -> m.value }.joinToString(" ").ifEmpty { null } }
    .joinToString("; ")

  @After
  fun naturalOrientation() {
    device.setOrientationNatural()
    device.unfreezeRotation()
    scenario?.close()  // so a later test (ProcessLifecycleOwner's) sees no activity left resumed
  }

  /** findObject(By.text) and click; wait(Until.hasObject) sees the change. */
  @Test
  fun aClickByTextLands() {
    launch()
    device.findObject(By.text("Tap me")).click()
    if (!device.wait(Until.hasObject(By.text("taps 1")), 5_000)) fail("no 'taps 1': ${seen()}")
  }

  /** setText puts text into a field found by its description. */
  @Test
  fun setTextFillsAField() {
    launch()
    val field = device.findObject(By.desc("uia field"))
    field.text = "typed"
    assertTrue(device.wait(Until.hasObject(By.text("typed")), 5_000))
  }

  /** A RecyclerView of 100 rows: its accessibility delegate reports the visible positions and the item count. */
  class Rows : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
      androidx.recyclerview.widget.RecyclerView(inflater.context).apply {
        contentDescription = "recycler"
        fitsSystemWindows = true
        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
          override fun getItemCount() = 100
          override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
            object : androidx.recyclerview.widget.RecyclerView.ViewHolder(TextView(parent.context).apply { minHeight = 120 }) {}
          override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = "item $position"
          }
        }
      }
  }

  /** scrollUntil scrolls a RecyclerView until a row far down is bound and on screen. */
  @Test
  fun scrollUntilFindsARecyclerViewRowFarDown() {
    val rows = launchFragmentInContainer<Rows>(themeResId = android.R.style.Theme_Material_Light_NoActionBar)
    try {
      val list = device.findObject(By.desc("recycler"))
      val row = list.scrollUntil(Direction.DOWN, Until.findObject(By.text("item 90")))
      if (row == null) fail("item 90 never came on screen: ${seen()}")
    } finally {
      rows.close()
    }
  }

  /** scrollUntil scrolls a container until a row far down is on screen. */
  @Test
  fun scrollUntilFindsARowFarDown() {
    launch()
    val rows = device.findObject(By.desc("rows"))
    val row = rows.scrollUntil(Direction.DOWN, Until.findObject(By.text("row 70")))
    if (row == null) fail("row 70 never came on screen: ${seen()}")
  }

  /** Until.gone waits for a view removed a moment later. */
  @Test
  fun untilGoneWaitsForARemoval() {
    val scenario = launch()
    scenario.onFragment { f -> Handler(Looper.getMainLooper()).postDelayed({ f.status.visibility = View.GONE }, 3_000) }
    // UiAutomator's own idle wait before a query lasts 500 ms of quiet: the removal comes well after it.
    assertNotNull(device.findObject(By.desc("status")))
    if (!device.wait(Until.gone(By.desc("status")), 10_000)) fail("still there: ${seen()}")
  }

  /** pressBack closes a dialog, which UiAutomator finds by its text. */
  @Test
  fun pressBackClosesADialog() {
    val scenario = launch()
    scenario.onFragment { f -> AlertDialog.Builder(f.requireContext()).setMessage("UiAutomator dialog").show() }
    assertTrue(device.wait(Until.hasObject(By.text("UiAutomator dialog")), 5_000))
    device.pressBack()
    assertTrue(device.wait(Until.gone(By.text("UiAutomator dialog")), 5_000))
  }

  /** The display size UiDevice reports is the app's display metrics' size. */
  @Test
  fun theDisplaySizeIsTheAppsDisplay() {
    val scenario = launch()
    var width = 0
    var height = 0
    scenario.onFragment { f ->
      val bounds = f.requireActivity().windowManager.currentWindowMetrics.bounds
      width = bounds.width(); height = bounds.height()
    }
    assertEquals(width, device.displayWidth)
    assertEquals(height, device.displayHeight)
  }

  /** setOrientationLeft gives the activity a landscape configuration; setOrientationNatural brings portrait back. */
  @Test
  fun setOrientationLeftTurnsTheActivity() {
    val scenario = launch()
    device.setOrientationLeft()
    device.waitForIdle()
    var orientation = 0
    scenario.onFragment { f -> orientation = f.resources.configuration.orientation }
    assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation)
    device.setOrientationNatural()
    device.waitForIdle()
    scenario.onFragment { f -> orientation = f.resources.configuration.orientation }
    assertEquals(Configuration.ORIENTATION_PORTRAIT, orientation)
  }

  /** dumpWindowHierarchy writes the window's nodes, texts included. */
  @Test
  fun theHierarchyDumpHasTheTexts() {
    launch()
    device.waitForIdle()
    val out = ByteArrayOutputStream()
    device.dumpWindowHierarchy(out)
    val xml = out.toString("UTF-8")
    assertTrue(seen(), xml.contains("text=\"Tap me\""))
    assertTrue(xml.contains("content-desc=\"rows\""))
  }

  /** executeShellCommand runs the device's shell and returns what it printed. */
  @Test
  fun executeShellCommandReturnsItsOutput() {
    assertEquals(Build.VERSION.SDK_INT.toString(), device.executeShellCommand("getprop ro.build.version.sdk").trim())
  }
}
