package dev.apksim.composeapp.conformance

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.compose.ui as documented, checked on an API 36 emulator first (docs/conformance.md). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComposeUiConformanceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /**
   * A node's position in the window is its offset at the display's density, and its size is its own; boundsInWindow
   * is clipped to its ancestors (here the root, as big as the box before its offset).
   */
  @Test
  fun positionInWindowFollowsOffsetAndDensity() {
    var bounds = Rect.Zero
    var clipped = Rect.Zero
    var density = 0f
    compose.setContent {
      density = LocalDensity.current.density
      Box(Modifier.offset(10.dp, 20.dp).size(30.dp, 40.dp).onGloballyPositioned {
        bounds = Rect(it.positionInWindow(), it.size.toSize())
        clipped = it.boundsInWindow()
      })
    }
    compose.waitForIdle()
    val root = compose.runOnIdle {
      val r = IntArray(2)
      compose.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(r)
      r
    }
    assertEquals(root[0] + 10 * density, bounds.left, 0.5f)
    assertEquals(root[1] + 20 * density, bounds.top, 0.5f)
    assertEquals(30 * density, bounds.width, 0.5f)
    assertEquals(40 * density, bounds.height, 0.5f)
    assertEquals(20 * density, clipped.width, 0.5f)
    assertEquals(20 * density, clipped.height, 0.5f)
  }

  /** LocalConfiguration and LocalDensity agree with the activity's resources. */
  @Test
  fun compositionLocalsMatchResources() {
    var widthDp = 0
    var density = 0f
    compose.setContent {
      widthDp = LocalConfiguration.current.screenWidthDp
      density = LocalDensity.current.density
    }
    compose.runOnIdle {
      val res = compose.activity.resources
      assertEquals(res.configuration.screenWidthDp, widthDp)
      assertEquals(res.displayMetrics.density, density, 0.001f)
    }
  }

  /** FocusRequester gives a focusable node the focus, and its key events reach onKeyEvent. */
  @Test
  fun aRequestedFocusTakesKeyEvents() {
    val requester = FocusRequester()
    var focused = false
    val keys = mutableListOf<Key>()
    compose.setContent {
      Box(
        Modifier.size(50.dp).testTag("box").focusRequester(requester).onFocusChanged { focused = it.isFocused }
          .onKeyEvent { if (it.type == KeyEventType.KeyUp) keys += it.key; false }.focusable()
      )
    }
    compose.runOnIdle { requester.requestFocus() }
    compose.runOnIdle { assertTrue(focused) }
    compose.onNodeWithTag("box").performKeyInput { pressKey(Key.A) }
    compose.runOnIdle { assertEquals(listOf(Key.A), keys) }
  }

  /** detectTapGestures tells a long press from a tap. */
  @Test
  fun aLongPressIsNotATap() {
    val events = mutableListOf<String>()
    compose.setContent {
      Box(Modifier.size(80.dp).testTag("target").pointerInput(Unit) {
        detectTapGestures(onTap = { events += "tap" }, onLongPress = { events += "long" })
      })
    }
    compose.onNodeWithTag("target").performTouchInput { longClick() }
    compose.onNodeWithTag("target").performClick()
    compose.runOnIdle { assertEquals(listOf("long", "tap"), events) }
  }

  /** derivedStateOf recomposes its readers only when its result changes. */
  @Test
  fun derivedStateRecomposesOnlyOnChange() {
    var count by mutableIntStateOf(0)
    var compositions = 0
    compose.setContent {
      val big by remember { derivedStateOf { count >= 3 } }
      compositions++
      Text(if (big) "big" else "small")
    }
    repeat(5) { compose.runOnIdle { count++ } }
    compose.onNodeWithText("big").assertExists()
    compose.runOnIdle { assertEquals(2, compositions) }
  }

  /** An AndroidView's View is laid out inside the composition and takes Espresso's clicks. */
  @Test
  fun anAndroidViewTakesClicks() {
    var clicks = 0
    compose.setContent {
      Column {
        Text("above")
        AndroidView(factory = { c -> TextView(c).apply { text = "interop view"; setOnClickListener { clicks++ } } })
      }
    }
    onView(withText("interop view")).perform(click())
    compose.runOnIdle { assertEquals(1, clicks) }
  }

  /** WindowInsets.systemBars in Compose are the root view's system bar insets. */
  @Test
  fun systemBarInsetsMatchTheView() {
    var top = -1
    var bottom = -1
    compose.setContent {
      val d = LocalDensity.current
      top = WindowInsets.systemBars.getTop(d)
      bottom = WindowInsets.systemBars.getBottom(d)
    }
    compose.runOnIdle {
      val view = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        .getInsets(WindowInsetsCompat.Type.systemBars())
      assertEquals(view.top, top)
      assertEquals(view.bottom, bottom)
    }
  }

  /** BackHandler takes back while enabled, then lets it through to the activity. */
  @Test
  fun backHandlerTakesBackWhileEnabled() {
    var enabled by mutableStateOf(true)
    var handled = 0
    compose.setContent { BackHandler(enabled) { handled++; enabled = false } }
    compose.waitForIdle()
    Espresso.pressBack()
    compose.runOnIdle {
      assertEquals(1, handled)
      assertTrue(!compose.activity.isFinishing)
    }
  }

  /** LocalLifecycleOwner is the activity's, resumed, and a LaunchedEffect starts once. */
  @Test
  fun lifecycleOwnerIsTheActivityResumed() {
    var state: Lifecycle.State? = null
    var launches = 0
    compose.setContent {
      val owner = LocalLifecycleOwner.current
      LaunchedEffect(Unit) { launches++; state = owner.lifecycle.currentState }
      Box(Modifier.size(10.dp).background(Color.Red))
    }
    compose.runOnIdle {
      assertEquals(Lifecycle.State.RESUMED, state)
      assertEquals(1, launches)
    }
  }
  /** With the clock paused, an animation is where its spec says after advancing the clock part of the way. */
  @Test
  fun anAnimationFollowsThePausedClock() {
    compose.mainClock.autoAdvance = false
    var target by mutableStateOf(0f)
    var value = -1f
    compose.setContent {
      val v by androidx.compose.animation.core.animateFloatAsState(
        target, androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.LinearEasing), label = "a")
      value = v
    }
    compose.mainClock.advanceTimeByFrame()
    compose.runOnIdle { target = 100f }
    compose.mainClock.advanceTimeByFrame()  // the animation starts on this frame
    compose.mainClock.advanceTimeBy(200)
    assertEquals(50f, value, 6f)
    compose.mainClock.advanceTimeBy(400)
    assertEquals(100f, value, 0.01f)
  }

  /** rememberSaveable state comes back after its saved state is restored (StateRestorationTester). */
  @Test
  fun saveableStateIsRestored() {
    val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
    tester.setContent {
      var taps by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
      Text("taps $taps", Modifier.testTag("t").pointerInput(Unit) { detectTapGestures { taps++ } })
    }
    compose.onNodeWithTag("t").performClick()
    compose.onNodeWithTag("t").performClick()
    tester.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("taps 2").assertExists()
  }

  /** An IME action on a text field runs its keyboard action. */
  @Test
  fun anImeActionRunsTheKeyboardAction() {
    var done = 0
    compose.setContent {
      var text by remember { mutableStateOf("") }
      androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.testTag("f").size(200.dp, 40.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done++ }))
    }
    compose.onNodeWithTag("f").performClick()
    compose.onNodeWithTag("f").performImeAction()
    compose.runOnIdle { assertEquals(1, done) }
  }

  /** The window's container size is the decor view's size. */
  @Test
  fun windowInfoContainerSizeIsTheWindows() {
    var size = androidx.compose.ui.unit.IntSize.Zero
    var focused = false
    compose.setContent {
      val info = androidx.compose.ui.platform.LocalWindowInfo.current
      size = info.containerSize
      focused = info.isWindowFocused
    }
    compose.runOnIdle {
      val decor = compose.activity.window.decorView
      assertEquals(decor.width, size.width)
      assertEquals(decor.height, size.height)
      assertTrue(focused)
    }
  }
}
