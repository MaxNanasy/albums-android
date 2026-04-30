package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.spotify.protocol.types.Repeat
import okhttp3.mockwebserver.MockResponse
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

class AddPlusStartUiTest : AbstractUiTestCase() {
    @Test
    fun addsAlbum() {
        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/testAlbum") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"name":"Test Album"}""")
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.itemUriInput)).perform(
            replaceText("spotify:album:testAlbum"),
            closeSoftKeyboard(),
        )
        onView(withId(R.id.addButton)).perform(click())

        waitUntil(
            label = "added album to appear in the list",
        ) {
            onView(withText("Test Album")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun startsPlayback() {
        harness.seedConnectedSession()
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:albumOne", title = "Album One"),
                ShuffleItem(type = "album", uri = "spotify:album:albumTwo", title = "Album Two"),
                ShuffleItem(type = "album", uri = "spotify:album:albumThree", title = "Album Three"),
            ),
        )

        launchMainActivity()

        onView(withId(R.id.startButton)).perform(scrollTo(), click())

        waitUntil(
            label = "playback status to show the current album",
            state = { textOf(R.id.playbackStatus) },
        ) { playbackStatus ->
            check(playbackStatus?.startsWith("Now playing album 1 of 3: ") == true)
        }

        waitUntil(
            label = "Spotify App Remote commands for playback start",
            state = { harness.spotifyAppRemoteService.commands.toList() },
        ) { commands ->
            check(commands.size == 3)
            check(
                commands[0] ==
                    TestSpotifyAppRemoteService.PlayerCommand.SetShuffle(enabled = false),
            )
            check(
                commands[1] ==
                    TestSpotifyAppRemoteService.PlayerCommand.SetRepeat(mode = Repeat.OFF),
            )
            check(
                commands[2] is
                    TestSpotifyAppRemoteService.PlayerCommand.Play,
            )
        }
    }

    @Test
    fun showsPlaylistImportErrorForServerFailures() {
        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse()
                        .setResponseCode(500)
                        .setHeader("Content-Type", "text/plain")
                        .setBody("boom")
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.itemUriInput)).perform(
            replaceText("playlist123"),
            closeSoftKeyboard(),
        )
        onView(withId(R.id.importPlaylistButton)).perform(click())

        waitUntil(
            label = "playlist import error message",
        ) {
            onView(withText("Error importing albums: 500 boom")).check(matches(isDisplayed()))
        }
    }
}
