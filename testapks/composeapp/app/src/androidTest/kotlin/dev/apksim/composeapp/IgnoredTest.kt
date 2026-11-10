package dev.apksim.composeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests JUnit reports ignored or skipped without running them to the end. Under `instrument.py --isolate` the @Ignore'd
 * ones get no process (apksim lists them as ignored when asked, `-e apksimListIgnored true`), while the one that skips
 * through an assumption still runs in its own process and is reported skipped. Each fails if it ever runs its body.
 */
@RunWith(AndroidJUnit4::class)
class IgnoredTest {
  @Test
  fun runs() {}

  @Ignore("ignored on purpose: never run")
  @Test
  fun ignoredOnItsMethod() {
    throw AssertionError("an @Ignore'd test ran")
  }

  @Test
  fun skippedByAnAssumption() {
    assumeTrue("skipped on purpose", false)
    throw AssertionError("the assumption did not stop the test")
  }
}

/** A whole class @Ignore'd: reported ignored as one entry, never run. */
@Ignore("ignored on purpose: never run")
@RunWith(AndroidJUnit4::class)
class IgnoredClassTest {
  @Test
  fun neverRuns() {
    throw AssertionError("a test of an @Ignore'd class ran")
  }
}
