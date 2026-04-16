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
class AddAlbumUiTest : SpotifyUiTestCase() {
    @Test
    fun addsAlbumUsingMockedSpotifyResponse() {
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

        val scenario = launchMainActivity()
        try {
            onView(withId(R.id.itemUriInput)).perform(
                replaceText("spotify:album:testAlbum"),
                closeSoftKeyboard(),
            )
            onView(withId(R.id.addButton)).perform(click())

            waitUntil {
                onView(withText("Test Album")).check(matches(isDisplayed()))
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun startsPlaybackWithThreeSavedItems() {
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

        val scenario = launchMainActivity()
        try {
            onView(withId(R.id.startButton)).perform(scrollTo(), click())

            waitUntil {
                onView(withId(R.id.playbackStatus)).check(
                    matches(withText(startsWith("Now playing album 1 of 3: "))),
                )
            }

            waitUntil {
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
        } finally {
            scenario.close()
        }
    }
}
