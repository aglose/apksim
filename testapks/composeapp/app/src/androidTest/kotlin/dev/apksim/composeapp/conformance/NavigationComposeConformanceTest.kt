package dev.apksim.composeapp.conformance

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation Compose as documented, checked on an API 36 emulator first (docs/conformance.md). */
@RunWith(AndroidJUnit4::class)
class NavigationComposeConformanceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** Navigation Compose: navigate, then back pops to the start, whose rememberSaveable state was kept. */
  @Test
  fun composeNavigationKeepsSaveableStateAcrossBack() {
    compose.setContent {
      val nav = rememberNavController()
      NavHost(nav, startDestination = "home") {
        composable("home") {
          var taps by rememberSaveable { mutableIntStateOf(0) }
          Column {
            Button(onClick = { taps++ }) { Text("taps $taps") }
            Button(onClick = { nav.navigate("detail") }) { Text("open detail") }
          }
        }
        composable("detail") { Text("detail screen") }
      }
    }
    compose.onNodeWithText("taps 0").performClick()
    compose.onNodeWithText("taps 1").assertIsDisplayed()
    compose.onNodeWithText("open detail").performClick()
    compose.onNodeWithText("detail screen").assertIsDisplayed()
    Espresso.pressBack()
    compose.onNodeWithText("taps 1").assertIsDisplayed()
    assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
  }

  /** Predictive back: a back gesture that is cancelled leaves the destination; one that completes pops it. */
  @Test
  fun predictiveBackCancelledThenCompleted() {
    compose.setContent {
      val nav = rememberNavController()
      NavHost(nav, startDestination = "home") {
        composable("home") { Button(onClick = { nav.navigate("detail") }) { Text("open detail") } }
        composable("detail") { Text("detail screen") }
      }
    }
    compose.onNodeWithText("open detail").performClick()
    compose.onNodeWithText("detail screen").assertIsDisplayed()
    val dispatcher = compose.activity.onBackPressedDispatcher
    compose.runOnUiThread {
      dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackCancelled()
    }
    compose.waitForIdle()
    compose.onNodeWithText("detail screen").assertIsDisplayed()
    compose.runOnUiThread {
      dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 500f, 0.9f, BackEventCompat.EDGE_LEFT))
      dispatcher.onBackPressed()
    }
    compose.waitForIdle()
    compose.onNodeWithText("open detail").assertIsDisplayed()
  }
}
