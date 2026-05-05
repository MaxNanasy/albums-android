package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.not
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Storage JSON Import/Export")
class StorageJsonUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Export/import JSON validation and valid import resets active session")
    fun exportImportJsonValidationAndValidImportResetsActiveSession() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:one", title = "One"),
                ShuffleItem(type = "album", uri = "spotify:album:two", title = "Two"),
            ),
        )
        harness.useIdentityShuffle()

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        waitUntil(label = "active session before export") {
            Ui.Playback.nextButton().check(matches(isEnabled()))
        }

        Ui.Storage.exportDataButton().perform(scrollTo(), click())
        waitUntil(label = "exported storage JSON") {
            check(textOf(R.id.storageJsonInput)?.contains("\"shuffle-by-album.items\"") == true)
        }

        Ui.Storage.json().perform(scrollTo(), replaceText(""), closeSoftKeyboard())
        Ui.Storage.importDataButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Paste a JSON object to import").check(matches(isDisplayed()))

        Ui.Storage.json().perform(scrollTo(), replaceText("{bad}"), closeSoftKeyboard())
        Ui.Storage.importDataButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Invalid JSON; please provide a valid JSON object").check(matches(isDisplayed()))

        Ui.Storage.json().perform(scrollTo(), replaceText("[]"), closeSoftKeyboard())
        Ui.Storage.importDataButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Import JSON must be an object of key/value pairs").check(matches(isDisplayed()))

        Ui.Storage.json().perform(scrollTo(), replaceText("{\"other\":[]}"), closeSoftKeyboard())
        Ui.Storage.importDataButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Import JSON must include a valid shuffle-by-album.items array").check(matches(isDisplayed()))

        Ui.Storage.json().perform(
            scrollTo(),
            replaceText("{\"shuffle-by-album.items\":[{\"type\":\"album\",\"uri\":\"spotify:album:no-title\"}]}"),
            closeSoftKeyboard(),
        )
        Ui.Storage.importDataButton().perform(scrollTo(), click())
        waitUntil(label = "valid storage import") {
            check(harness.savedItemTitles() == listOf("spotify:album:no-title"))
            Ui.Playback.status().check(matches(withText("Data imported; session reset")))
        }
        Ui.Playback.nextButton().check(matches(not(isEnabled())))
    }

    @Test
    @DisplayName("Export includes Removed Items and import restores it")
    fun exportIncludesRemovedItemsAndImportRestoresIt() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:one", title = "One"),
                ShuffleItem(type = "album", uri = "spotify:album:two", title = "Two"),
            ),
        )

        launchMainActivity()
        Ui.SavedItems.removeButton("One").perform(click())
        check(harness.removedItemTitles() == listOf("One"))

        Ui.Storage.exportDataButton().perform(scrollTo(), click())
        val exported = JSONObject(textOf(R.id.storageJsonInput).orEmpty())
        check(exported.getJSONArray("shuffle-by-album.items").length() == 1)
        check(exported.getJSONArray("shuffle-by-album.removedItems").length() == 1)

        Ui.Storage.json().perform(
            scrollTo(),
            replaceText(
                """
                {
                  "shuffle-by-album.items":[{"type":"album","uri":"spotify:album:two","title":"Two"}],
                  "shuffle-by-album.removedItems":[{"type":"album","uri":"spotify:album:restorable","title":"Restorable"}]
                }
                """.trimIndent(),
            ),
            closeSoftKeyboard(),
        )
        Ui.Storage.importDataButton().perform(scrollTo(), click())

        waitUntil(label = "removed items import restore") {
            check(harness.savedItemTitles() == listOf("Two"))
            Ui.RemovedItems.section().check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)))
            check(harness.removedItemTitles() == listOf("Restorable"))
        }

        clickRecyclerActionByTitle(R.id.removedItemsRecycler, "Restorable", R.id.removeButton)
        waitUntil(label = "restored removed import item") {
            check(harness.savedItemTitles() == listOf("Two", "Restorable"))
        }
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))
    }

    @Test
    @DisplayName("Export with invalid stored items JSON clears the textarea and shows an export error")
    fun exportWithInvalidStoredItemsJsonClearsTheTextareaAndShowsAnExportError() {
        harness.seedRawItemsJson("{bad-json")

        launchMainActivity()
        Ui.Storage.exportDataButton().perform(scrollTo(), click())
        waitUntil(label = "invalid export error") {
            check(textOf(R.id.storageJsonInput).orEmpty().isEmpty())
        }
        Ui.Toasts.instance("Unable to export saved items because stored data is invalid JSON").check(matches(isDisplayed()))
    }
}
