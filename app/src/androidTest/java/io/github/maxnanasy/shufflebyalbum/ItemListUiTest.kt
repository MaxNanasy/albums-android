package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withText
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Item List")
class ItemListUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Remove then undo restores original row position and duplicate-undo is prevented")
    fun removeThenUndoRestoresOriginalRowPositionAndDuplicateUndoIsPrevented() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:a", title = "A"),
                ShuffleItem(type = "album", uri = "spotify:album:b", title = "B"),
            ),
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/newone") {
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"name":"New One"}""")
                }
                route("/v1/albums/a") {
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"name":"A"}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.removeButton("A").perform(click())
        check(harness.savedItemTitles() == listOf("B"))
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)))
        check(harness.removedItemTitles() == listOf("A"))
        Ui.RemovedItems.count().check(matches(withText("1 item")))

        Ui.SavedItems.uriInput().perform(replaceText("spotify:album:newone"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Added “New One”").check(matches(isDisplayed()))
        check(harness.savedItemTitles() == listOf("B", "New One"))

        clickRecyclerActionByTitle(R.id.removedItemsRecycler, "A", R.id.removeButton)
        Ui.Toasts.instance("Restored “A”").check(matches(isDisplayed()))
        check(harness.savedItemTitles().toSet() == setOf("A", "B", "New One"))
        check(harness.savedItemTitles().size == 3)
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))

        Ui.SavedItems.removeButton("A").perform(click())
        check(harness.removedItemTitles() == listOf("A"))

        Ui.SavedItems.uriInput().perform(replaceText("spotify:album:a"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Added “A”").check(matches(isDisplayed()))
        check(harness.savedItemTitles() == listOf("B", "New One", "A"))
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))
    }

    @Test
    @DisplayName("Removed Items restores items to the bottom and import albums clears restored uris")
    fun removedItemsRestoresItemsToTheBottomAndImportAlbumsClearsRestoredUris() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:a", title = "A"),
                ShuffleItem(type = "album", uri = "spotify:album:b", title = "B"),
                ShuffleItem(type = "album", uri = "spotify:album:c", title = "C"),
            ),
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/importme/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "items":[{"item":{"album":{"uri":"spotify:album:c","name":"C"}}}],
                              "next":null
                            }
                            """.trimIndent(),
                        )
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.removeButton("A").perform(click())
        Ui.SavedItems.removeButton("C").perform(click())
        check(harness.savedItemTitles() == listOf("B"))
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)))
        Ui.RemovedItems.count().check(matches(withText("2 items")))
        check(harness.removedItemTitles().toSet() == setOf("A", "C"))
        check(harness.removedItemTitles().size == 2)

        clickRecyclerActionByTitle(R.id.removedItemsRecycler, "A", R.id.removeButton)
        Ui.Toasts.instance("Restored “A”").check(matches(isDisplayed()))
        check(harness.savedItemTitles().toSet() == setOf("A", "B"))
        check(harness.savedItemTitles().size == 2)
        Ui.RemovedItems.count().check(matches(withText("1 item")))

        Ui.SavedItems.uriInput().perform(replaceText("spotify:playlist:importme"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())
        Ui.Toasts.instance("Imported 1 album(s) from playlist (1 unique album(s) found)").check(matches(isDisplayed()))
        check(harness.savedItemTitles().toSet() == setOf("A", "B", "C"))
        check(harness.savedItemTitles().size == 3)
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))
    }

    @Test
    @DisplayName("Removed Items persists across reload and purge all requires confirmation")
    fun removedItemsPersistsAcrossReloadAndPurgeAllRequiresConfirmation() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:a", title = "A"),
                ShuffleItem(type = "album", uri = "spotify:album:b", title = "B"),
            ),
        )

        launchMainActivity()
        Ui.SavedItems.removeButton("A").perform(click())
        check(harness.removedItemTitles() == listOf("A"))

        launchMainActivity()
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)))
        Ui.RemovedItems.count().check(matches(withText("1 item")))
        check(harness.removedItemTitles() == listOf("A"))

        Ui.RemovedItems.purgeButton().perform(scrollTo(), click())
        Ui.RemovedItems.purgeDialogMessage().check(matches(withText("Permanently remove 1 item?")))
        Ui.RemovedItems.cancelPurgeButton().perform(click())
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)))
        check(harness.removedItemTitles() == listOf("A"))

        Ui.RemovedItems.purgeButton().perform(scrollTo(), click())
        Ui.RemovedItems.confirmPurgeButton().perform(click())
        Ui.Toasts.instance("Purged Removed Items").check(matches(isDisplayed()))
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))

        launchMainActivity()
        Ui.RemovedItems.section().check(matches(withEffectiveVisibility(GONE)))
    }
}
