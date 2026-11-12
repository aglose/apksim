package dev.apksim.composeapp.conformance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import android.widget.TextView
import androidx.lifecycle.LifecycleEventObserver
import org.junit.Assert.assertNotNull
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * androidx.activity as its documentation describes it, written device-style (the test on the instrumentation
 * thread, UI work through onActivity) and checked on an API 36 emulator first (docs/conformance.md).
 */
@RunWith(AndroidJUnit4::class)
class ActivityConformanceTest {
  class Counter(val handle: SavedStateHandle) : ViewModel() {
    var cleared = false
    override fun onCleared() { cleared = true }
  }

  private val factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
      Counter(extras.createSavedStateHandle()) as T
  }

  /** OnBackPressedDispatcher: the most recently added enabled callback handles back, and only it. */
  @Test
  fun backGoesToTheLastAddedEnabledCallback() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      val calls = mutableListOf<String>()
      lateinit var second: OnBackPressedCallback
      scenario.onActivity { a ->
        a.onBackPressedDispatcher.addCallback(a, object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() { calls += "first" }
        })
        second = object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() { calls += "second" }
        }
        a.onBackPressedDispatcher.addCallback(a, second)
      }
      Espresso.pressBack()
      assertEquals(listOf("second"), calls)
      scenario.onActivity { second.isEnabled = false }
      Espresso.pressBack()
      assertEquals(listOf("second", "first"), calls)
      assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }
  }

  /** registerForActivityResult must be called before the LifecycleOwner is STARTED. */
  @Test
  fun registeringForAResultAfterStartThrows() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { a ->
        try {
          a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
          fail("registered while RESUMED")
        } catch (e: IllegalStateException) {
          assertTrue(e.message, e.message!!.contains("before they are STARTED"))
        }
      }
    }
  }

  /** A ViewModel outlives a configuration change; its SavedStateHandle's values come back too. */
  @Test
  fun recreateKeepsTheViewModelAndItsSavedState() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      lateinit var before: Counter
      lateinit var activityBefore: ComponentActivity
      scenario.onActivity { a ->
        activityBefore = a
        before = ViewModelProvider(a.viewModelStore, factory, a.defaultViewModelCreationExtras)[Counter::class.java]
        before.handle["count"] = 3
      }
      scenario.recreate()
      scenario.onActivity { a ->
        assertNotSame(activityBefore, a)
        val after = ViewModelProvider(a.viewModelStore, factory, a.defaultViewModelCreationExtras)[Counter::class.java]
        assertSame(before, after)
        assertEquals(3, after.handle.get<Int>("count"))
      }
    }
  }

  /** Finishing (not a configuration change) clears the ViewModelStore: onCleared runs. */
  @Test
  fun closingTheScenarioClearsViewModels() {
    lateinit var vm: Counter
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { a ->
        vm = ViewModelProvider(a.viewModelStore, factory, a.defaultViewModelCreationExtras)[Counter::class.java]
      }
    }
    assertTrue(vm.cleared)
  }

  /** The SavedStateRegistry's providers are asked again when the activity saves, and restored on recreate. */
  @Test
  fun savedStateRegistryProviderRoundTrips() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { a ->
        a.savedStateRegistry.registerSavedStateProvider("conformance") { Bundle().apply { putString("v", "kept") } }
      }
      scenario.recreate()
      scenario.onActivity { a ->
        assertEquals("kept", a.savedStateRegistry.consumeRestoredStateForKey("conformance")?.getString("v"))
      }
    }
  }

  /** FullyDrawnReporter: once the last reporter is removed, its listeners hear that the activity reported drawn. */
  @Test
  fun fullyDrawnReporterCallsItsListenersWhenTheLastReporterLeaves() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      val drawn = CountDownLatch(1)
      scenario.onActivity { a ->
        a.fullyDrawnReporter.addReporter()
        a.fullyDrawnReporter.addOnReportDrawnListener { drawn.countDown() }
        a.fullyDrawnReporter.removeReporter()
      }
      assertTrue(drawn.await(5, TimeUnit.SECONDS))
      scenario.onActivity { a -> assertTrue(a.fullyDrawnReporter.isFullyDrawnReported) }
    }
  }

  /** Lifecycle events reach an observer in order, ending with ON_DESTROY when the scenario closes. */
  @Test
  fun lifecycleEventsArriveInOrder() {
    val events = mutableListOf<Lifecycle.Event>()
    val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    scenario.onActivity { a ->
      a.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e -> events += e })
    }
    scenario.moveToState(Lifecycle.State.CREATED)
    scenario.moveToState(Lifecycle.State.RESUMED)
    scenario.close()
    assertEquals(
      listOf(
        Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME,
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME,
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY,
      ),
      events,
    )
  }

  /** The decor view has root window insets once laid out, edge to edge or not. */
  @Test
  fun rootWindowInsetsExistAfterLayout() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { a -> a.setContentView(TextView(a).apply { text = "insets" }) }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      scenario.onActivity { a -> assertNotNull(ViewCompat.getRootWindowInsets(a.window.decorView)) }
    }
  }

  /** API 28+: on a configuration change, state is saved after ON_STOP and before ON_DESTROY. */
  @Test
  fun recreateSavesStateAfterStop() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      val events = mutableListOf<String>()
      scenario.onActivity { a ->
        a.lifecycle.addObserver(LifecycleEventObserver { _, e -> events += e.name })
        a.savedStateRegistry.registerSavedStateProvider("order") { events += "save"; Bundle() }
      }
      events.clear()  // a new observer is first brought up to the current state (ON_CREATE, ON_START, ON_RESUME)
      scenario.recreate()
      assertEquals(listOf("ON_PAUSE", "ON_STOP", "save", "ON_DESTROY"), events)
    }
  }
}
