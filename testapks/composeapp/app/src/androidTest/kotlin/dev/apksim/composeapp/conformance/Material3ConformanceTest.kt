package dev.apksim.composeapp.conformance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.compose.material3 as documented, checked on an API 36 emulator first (docs/conformance.md). */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class Material3ConformanceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** A snackbar's action click resumes showSnackbar with ActionPerformed. */
  @Test
  fun aSnackbarActionIsReported() {
    val host = SnackbarHostState()
    var result: SnackbarResult? = null
    compose.setContent {
      val scope = rememberCoroutineScope()
      Column {
        Button({ scope.launch { result = host.showSnackbar("saved", actionLabel = "Undo") } }) { Text("show") }
        SnackbarHost(host)
      }
    }
    compose.onNodeWithText("show").performClick()
    compose.onNodeWithText("Undo").performClick()
    compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
  }

  /** A short snackbar without an action dismisses itself, as the clock passes its duration. */
  @Test
  fun aShortSnackbarTimesOut() {
    val host = SnackbarHostState()
    var result: SnackbarResult? = null
    compose.setContent {
      val scope = rememberCoroutineScope()
      Column {
        Button({ scope.launch { result = host.showSnackbar("short", duration = SnackbarDuration.Short) } }) { Text("show") }
        SnackbarHost(host)
      }
    }
    compose.onNodeWithText("show").performClick()
    compose.onNodeWithText("short").assertExists()
    compose.mainClock.advanceTimeBy(10_000)
    compose.runOnIdle { assertEquals(SnackbarResult.Dismissed, result) }
    compose.onNodeWithText("short").assertDoesNotExist()
  }

  /** A DropdownMenu opens in a popup; picking an item reports it and closes the menu. */
  @Test
  fun aDropdownItemIsPickedAndTheMenuCloses() {
    var expanded by mutableStateOf(false)
    var picked = ""
    compose.setContent {
      Box {
        TextButton({ expanded = true }) { Text("menu") }
        DropdownMenu(expanded, { expanded = false }) {
          listOf("one", "two").forEach { DropdownMenuItem({ Text(it) }, { picked = it; expanded = false }) }
        }
      }
    }
    compose.onNodeWithText("menu").performClick()
    compose.onNodeWithText("two").performClick()
    compose.onNodeWithText("two").assertDoesNotExist()
    compose.runOnIdle { assertEquals("two", picked) }
  }

  /** Back dismisses a DropdownMenu through onDismissRequest. */
  @Test
  fun backDismissesADropdownMenu() {
    var expanded by mutableStateOf(true)
    compose.setContent {
      Box { DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("only") }, {}) } }
    }
    compose.onNodeWithText("only").assertExists()
    Espresso.pressBack()
    compose.onNodeWithText("only").assertDoesNotExist()
    compose.runOnIdle { assertFalse(expanded) }
  }

  /** A slider dragged to its end reports its maximum; setProgress through semantics sets a value. */
  @Test
  fun aSliderFollowsDragAndSemantics() {
    var value by mutableFloatStateOf(0f)
    compose.setContent { Slider(value, { value = it }, Modifier.fillMaxWidth().testTag("slider")) }
    compose.onNodeWithTag("slider").performTouchInput { swipeRight(startX = centerX, endX = right) }
    compose.runOnIdle { assertEquals(1f, value, 0.01f) }
    compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
    compose.runOnIdle { assertEquals(0.25f, value, 0.01f) }
  }

  /** Switch and Checkbox toggle on a click and say so in semantics. */
  @Test
  fun switchAndCheckboxToggle() {
    var on by mutableStateOf(false)
    var checked by mutableStateOf(false)
    compose.setContent {
      Column {
        Switch(on, { on = it }, Modifier.testTag("switch"))
        Checkbox(checked, { checked = it }, Modifier.testTag("box"))
      }
    }
    compose.onNodeWithTag("switch").assertIsOff().performClick().assertIsOn()
    compose.onNodeWithTag("box").assertIsOff().performClick().assertIsOn()
  }

  /** An AlertDialog is a dialog window; its confirm button closes it. */
  @Test
  fun anAlertDialogConfirms() {
    var open by mutableStateOf(true)
    var confirmed = 0
    compose.setContent {
      if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton({ confirmed++; open = false }) { Text("Confirm") } },
        text = { Text("conformance alert") },
      )
    }
    compose.onNode(isDialog()).assertExists()
    compose.onNodeWithText("Confirm").performClick()
    compose.onNode(isDialog()).assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, confirmed) }
  }

  /** A ModalBottomSheet opens expanded (or partly, on a tall screen) and hides through its state. */
  @Test
  fun aBottomSheetShowsAndHides() {
    lateinit var sheet: SheetState
    var open by mutableStateOf(true)
    compose.setContent {
      sheet = rememberModalBottomSheetState()
      val scope = rememberCoroutineScope()
      if (open) ModalBottomSheet({ open = false }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().height(200.dp)) {
          Text("sheet content")
          Button({ scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) open = false } }) { Text("Close") }
        }
      }
    }
    compose.onNodeWithText("sheet content").assertExists()
    compose.runOnIdle { assertTrue(sheet.isVisible) }
    compose.onNodeWithText("Close").performClick()
    compose.onNodeWithText("sheet content").assertDoesNotExist()
    compose.runOnIdle { assertFalse(open) }
  }

  /** A TabRow selects the clicked tab. */
  @Test
  fun aTabRowSelectsTheClickedTab() {
    var selected by mutableIntStateOf(0)
    compose.setContent {
      TabRow(selected) { listOf("A", "B", "C").forEachIndexed { i, t -> Tab(i == selected, { selected = i }, text = { Text(t) }) } }
    }
    compose.onNodeWithText("C").performClick()
    compose.runOnIdle { assertEquals(2, selected) }
  }
  /** A TooltipBox shown through its state shows its tooltip in a popup, and dismissing it hides it. */
  @Test
  fun aTooltipShowsAndHides() {
    lateinit var tooltip: androidx.compose.material3.TooltipState
    lateinit var scope: kotlinx.coroutines.CoroutineScope
    compose.setContent {
      tooltip = androidx.compose.material3.rememberTooltipState(isPersistent = true)
      scope = rememberCoroutineScope()
      androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("tooltip text") } },
        state = tooltip,
      ) { Text("anchor") }
    }
    compose.runOnIdle { scope.launch { tooltip.show() } }
    compose.onNodeWithText("tooltip text").assertExists()
    compose.runOnIdle { tooltip.dismiss() }
    compose.onNodeWithText("tooltip text").assertDoesNotExist()
  }

  /** Pulling a PullToRefreshBox down far enough calls onRefresh. */
  @Test
  fun aPullDownRefreshes() {
    var refreshes = 0
    compose.setContent {
      androidx.compose.material3.pulltorefresh.PullToRefreshBox(false, { refreshes++ }, Modifier.fillMaxSize().testTag("ptr")) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
          items(30) { Text("row $it", Modifier.fillMaxWidth().height(60.dp)) }
        }
      }
    }
    compose.onNodeWithTag("ptr").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 500) }
    compose.runOnIdle { assertEquals(1, refreshes) }
  }

  /** A SwipeToDismissBox swiped from start to end settles dismissed in that direction. */
  @Test
  fun aSwipeDismisses() {
    lateinit var state: androidx.compose.material3.SwipeToDismissBoxState
    compose.setContent {
      state = androidx.compose.material3.rememberSwipeToDismissBoxState()
      androidx.compose.material3.SwipeToDismissBox(state, backgroundContent = { Text("behind") },
        modifier = Modifier.fillMaxWidth().height(80.dp).testTag("row")) { Text("swipe me", Modifier.fillMaxSize()) }
    }
    compose.onNodeWithTag("row").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
    compose.runOnIdle {
      assertEquals(androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd, state.currentValue)
    }
  }

  /** A ModalNavigationDrawer opened through its state closes on a scrim tap. */
  @Test
  fun aDrawerClosesOnAScrimTap() {
    lateinit var drawer: androidx.compose.material3.DrawerState
    lateinit var scope: kotlinx.coroutines.CoroutineScope
    compose.setContent {
      drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
      scope = rememberCoroutineScope()
      androidx.compose.material3.ModalNavigationDrawer(
        drawerContent = { androidx.compose.material3.ModalDrawerSheet { Text("drawer item") } },
        drawerState = drawer,
      ) { Box(Modifier.fillMaxSize().testTag("content")) { Text("content") } }
    }
    compose.runOnIdle { scope.launch { drawer.open() } }
    compose.onNodeWithText("drawer item").assertIsDisplayed()
    compose.onNodeWithTag("content").performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(20f, 0f)) }
    compose.runOnIdle { assertEquals(androidx.compose.material3.DrawerValue.Closed, drawer.currentValue) }
  }

  /** A DropdownMenu inside a Dialog opens over it and takes the click. */
  @Test
  fun aMenuInsideADialogTakesClicks() {
    var expanded by mutableStateOf(false)
    var picked = ""
    compose.setContent {
      androidx.compose.ui.window.Dialog({}) {
        Box(Modifier.size(200.dp)) {
          TextButton({ expanded = true }) { Text("open menu") }
          DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("in dialog") }, { picked = "yes"; expanded = false }) }
        }
      }
    }
    compose.onNodeWithText("open menu").performClick()
    compose.onNodeWithText("in dialog").performClick()
    compose.runOnIdle { assertEquals("yes", picked) }
  }
}
