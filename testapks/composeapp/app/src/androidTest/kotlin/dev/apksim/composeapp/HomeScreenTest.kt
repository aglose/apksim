package dev.apksim.composeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Home tab's search, saved filter and menu, and a place's page with its form, dialog and snackbar. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun searchNarrowsTheList() {
    compose.onNodeWithText("100 places").assertIsDisplayed()
    compose.onNodeWithText("Search places").performTextInput("42")
    compose.onNodeWithText("1 place").assertIsDisplayed()
    compose.onNodeWithText("Item 42").assertIsDisplayed()
    compose.onNodeWithContentDescription("Clear search").performClick()
    compose.onNodeWithText("100 places").assertIsDisplayed()
  }

  @Test
  fun aTapSavesAPlaceAndTheFilterShowsOnlySavedOnes() {
    compose.onNodeWithText("Item 2").performClick()
    compose.onNodeWithText("Saved 1").assertIsDisplayed().performClick()
    compose.onNodeWithText("1 place").assertIsDisplayed()
    compose.onNodeWithContentDescription("More options").performClick()
    compose.onNodeWithText("Clear saved places").performClick()
    compose.onNodeWithText("Saved 0").assertIsDisplayed()
    compose.onNodeWithText("0 places").assertIsDisplayed()
  }

  @Test
  fun aPlaceOpensItsPageAndBackReturns() {
    compose.onNodeWithContentDescription("Open Item 3").performClick()
    compose.onNodeWithText("Reykjavik").assertIsDisplayed()
    compose.onNodeWithText("More").performClick()
    compose.onNodeWithText("Travellers: 3").assertIsDisplayed()
    compose.onNodeWithText("Plane").performClick()
    compose.onNodeWithContentDescription("Book").performClick()
    compose.onNodeWithText("Book Item 3?").assertIsDisplayed()
    compose.onNodeWithText("3 by plane to Reykjavik, 0 of 3 things packed.").assertIsDisplayed()
    compose.onNodeWithText("Book").performClick()
    compose.onNodeWithText("Booked Item 3 for 3").assertIsDisplayed()
    Espresso.pressBack()
    compose.onNodeWithText("FAB clicks: 0").assertIsDisplayed()
  }

  @Test
  fun theMenuResetsTheCounterAndShowsAbout() {
    repeat(2) { compose.onNodeWithContentDescription("Add").performClick() }
    compose.onNodeWithText("FAB clicks: 2").assertIsDisplayed()
    compose.onNodeWithContentDescription("More options").performClick()
    compose.onNodeWithText("Reset counter").performClick()
    compose.onNodeWithText("FAB clicks: 0").assertIsDisplayed()
    compose.onNodeWithContentDescription("More options").performClick()
    compose.onNodeWithText("About").performClick()
    compose.onNodeWithText("About ComposeApp").assertIsDisplayed()
    compose.onNodeWithText("Close").performClick()
  }
}
