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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotify.protocol.types.Repeat
import okhttp3.mockwebserver.MockResponse
import org.hamcrest.Matchers.startsWith
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddPlusStartUiTest : AbstractUiTestCase() {
    @Test
    fun addsAlbum() {
        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher { request ->
                when (request.path) {
                    "/v1/albums/testAlbum" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"name":"Test Album"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.itemUriInput)).perform(
            replaceText("spotify:album:testAlbum"),
            closeSoftKeyboard(),
        )
        onView(withId(R.id.addButton)).perform(click())

        waitUntil {
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
        harness.setDispatcher(
            jsonDispatcher { request ->
                when (request.path) {
                    "/v1/me/player" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.startButton)).perform(scrollTo(), click())

        waitUntil(
            label = "playback status to show the current album",
            state = {
                "playbackStatus=${textOf(R.id.playbackStatus)}; commands=${harness.spotifyAppRemoteService.commands}"
            },
        ) {
            onView(withId(R.id.playbackStatus)).check(
                matches(withText(startsWith("Now playing album 1 of 3: "))),
            )
        }

        waitUntil(
            label = "Spotify App Remote commands for playback start",
            state = {
                "commands=${harness.spotifyAppRemoteService.commands}; playbackStatus=${textOf(R.id.playbackStatus)}"
            },
        ) {
            check(harness.spotifyAppRemoteService.commands.size == 3)
            check(
                harness.spotifyAppRemoteService.commands[0] ==
                    TestSpotifyAppRemoteService.PlayerCommand.SetShuffle(enabled = false),
            )
            check(
                harness.spotifyAppRemoteService.commands[1] ==
                    TestSpotifyAppRemoteService.PlayerCommand.SetRepeat(mode = Repeat.OFF),
            )
            check(
                harness.spotifyAppRemoteService.commands[2] is
                    TestSpotifyAppRemoteService.PlayerCommand.Play,
            )
        }
    }
}
