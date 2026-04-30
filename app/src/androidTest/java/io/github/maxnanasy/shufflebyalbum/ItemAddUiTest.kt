package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Item Add")
class ItemAddUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Adds an album from normal Spotify URL")
    fun addsAnAlbumFromNormalSpotifyUrl() {
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/album123") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"name":"Discovery"}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("https://open.spotify.com/album/album123"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())

        waitUntil(label = "album row") {
            Ui.SavedItems.row("Discovery").check(matches(isDisplayed()))
        }
    }

    @Test
    @DisplayName("Adds a playlist from Spotify playlist URL")
    fun addsAPlaylistFromSpotifyPlaylistUrl() {
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"name":"Road Trip Mix"}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("https://open.spotify.com/playlist/playlist123"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())

        waitUntil(label = "playlist row") {
            Ui.SavedItems.row("Road Trip Mix").check(matches(isDisplayed()))
        }
        Ui.Toasts.instance("Added “Road Trip Mix”").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Duplicate and invalid input show validation toasts")
    fun duplicateAndInvalidInputShowValidationToasts() {
        harness.seedSavedItems(
            listOf(ShuffleItem(type = "album", uri = "spotify:album:album123", title = "Discovery")),
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("not-valid"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Enter a valid Spotify album/playlist URI or URL").check(matches(isDisplayed()))

        Ui.SavedItems.uriInput().perform(replaceText("spotify:album:album123"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Item is already in your list").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Add while disconnected and title lookup failure both show toasts")
    fun addWhileDisconnectedAndTitleLookupFailureBothShowToasts() {
        harness.clearAccessToken()
        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("spotify:album:album123"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Connect Spotify first so the app can load item titles").check(matches(isDisplayed()))

        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/missing") {
                    MockResponse().setResponseCode(404)
                }
            },
        )
        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("spotify:album:missing"), closeSoftKeyboard())
        Ui.SavedItems.addButton().perform(click())
        Ui.Toasts.instance("Unable to load title for that item; please try another URI").check(matches(isDisplayed()))
    }
}
