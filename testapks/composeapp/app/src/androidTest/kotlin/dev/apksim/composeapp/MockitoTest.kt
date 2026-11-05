package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Mockito works with the mock maker an Android test APK ships: mockito-android's, which is for ART, becomes Mockito's
 * JVM subclass maker in a test run (`--mock-maker auto`, the default).
 */
@RunWith(AndroidJUnit4::class)
class MockitoTest {
  /** Open, as a class Mockito's subclass maker can mock must be. */
  open class Greeter {
    open fun greet(name: String): String = "Hello, $name"
  }

  @Test
  fun aMockIsStubbedAndVerified() {
    val greeter = mock(Greeter::class.java)
    `when`(greeter.greet("test")).thenReturn("stubbed")
    assertEquals("stubbed", greeter.greet("test"))
    verify(greeter).greet("test")
  }

  @Test
  fun anInterfaceIsMocked() {
    @Suppress("UNCHECKED_CAST")
    val supplier = mock(java.util.function.Supplier::class.java) as java.util.function.Supplier<String>
    `when`(supplier.get()).thenReturn("value")
    assertEquals("value", supplier.get())
  }
}
