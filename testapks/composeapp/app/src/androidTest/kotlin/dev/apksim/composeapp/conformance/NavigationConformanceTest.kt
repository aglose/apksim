package dev.apksim.composeapp.conformance

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.fragment
import androidx.navigation.navOptions
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** androidx.navigation (fragments and Compose) as documented, checked on an API 36 emulator first. */
@RunWith(AndroidJUnit4::class)
class NavigationConformanceTest {
  private fun NavController.conformanceGraph() = createGraph(startDestination = "a") {
    fragment<FragmentConformanceTest.TextFragment>("a") { argument("text") { defaultValue = "screen a" } }
    fragment<FragmentConformanceTest.TextFragment>("b") {
      argument("text") { defaultValue = "screen b" }
      deepLink("conformance://b")
    }
  }

  private fun withNavHost(block: (FragmentScenario<NavHostFragment>) -> Unit) {
    launchFragmentInContainer { NavHostFragment() }.use { scenario ->
      scenario.onFragment {
        // As app:defaultNavHost="true" does: the host intercepts back.
        it.parentFragmentManager.beginTransaction().setPrimaryNavigationFragment(it).commitNow()
        it.navController.graph = it.navController.conformanceGraph()
      }
      block(scenario)
    }
  }

  /** NavHostFragment: navigate shows the destination; back pops to the start. */
  @Test
  fun fragmentNavigationAndBack() {
    withNavHost { scenario ->
      onView(withText("screen a")).check(matches(isDisplayed()))
      scenario.onFragment { it.navController.navigate("b") }
      onView(withText("screen b")).check(matches(isDisplayed()))
      Espresso.pressBack()
      onView(withText("screen a")).check(matches(isDisplayed()))
      scenario.onFragment { assertEquals("a", it.navController.currentDestination?.route) }
    }
  }

  /** popUpTo inclusive leaves only the new destination on the back stack. */
  @Test
  fun popUpToInclusiveReplacesTheStart() {
    withNavHost { scenario ->
      scenario.onFragment {
        it.navController.navigate("b", navOptions { popUpTo("a") { inclusive = true } })
        assertEquals(null, it.navController.previousBackStackEntry)
      }
      onView(withText("screen b")).check(matches(isDisplayed()))
    }
  }

  /** handleDeepLink with a matching URI goes to its destination. */
  @Test
  fun deepLinkReachesItsDestination() {
    withNavHost { scenario ->
      scenario.onFragment {
        assertTrue(it.navController.handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse("conformance://b"))))
      }
      onView(withText("screen b")).check(matches(isDisplayed()))
    }
  }

  /** The back stack outlives recreation: the activity comes back on the destination it was on. */
  @Test
  fun fragmentBackStackSurvivesRecreate() {
    withNavHost { scenario ->
      scenario.onFragment { it.navController.navigate("b") }
      scenario.recreate()
      scenario.onFragment {
        // A graph built in code is set again after recreation; setGraph then restores the saved back stack.
        it.navController.graph = it.navController.conformanceGraph()
        assertEquals("b", it.navController.currentDestination?.route)
      }
      onView(withText("screen b")).check(matches(isDisplayed()))
    }
  }

  /** A result passed back through the previous entry's SavedStateHandle is there once the destination pops. */
  @Test
  fun resultThroughSavedStateHandle() {
    withNavHost { scenario ->
      scenario.onFragment {
        val nav = it.navController
        nav.navigate("b")
        nav.previousBackStackEntry!!.savedStateHandle["result"] = "from b"
        assertTrue(nav.popBackStack())
        assertEquals("from b", nav.currentBackStackEntry!!.savedStateHandle.get<String>("result"))
      }
      onView(withText("screen a")).check(matches(isDisplayed()))
    }
  }

  /** navigateUp pops to the parent destination. */
  @Test
  fun navigateUpPops() {
    withNavHost { scenario ->
      scenario.onFragment {
        it.navController.navigate("b")
        assertTrue(it.navController.navigateUp())
        assertEquals("a", it.navController.currentDestination?.route)
      }
      onView(withText("screen a")).check(matches(isDisplayed()))
    }
  }
}
