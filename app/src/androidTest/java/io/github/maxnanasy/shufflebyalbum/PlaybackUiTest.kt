package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.spotify.protocol.types.Repeat
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Playback Controls")
class PlaybackUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Starts playback")
    fun startsPlayback() {
        harness.seedSavedItems(
            listOf(ShuffleItem(type = "album", uri = "spotify:album:album123", title = "Discovery")),
        )
        harness.useIdentityShuffle()

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())

        waitUntil(label = "playback start status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 1: Discovery")))
        }
        Ui.Playback.queueRow("▶ 1. Discovery").check(matches(isDisplayed()))
        waitUntil(label = "playback start commands") {
            check(harness.spotifyAppRemoteService.commands.size == 3)
        }
        check(harness.spotifyAppRemoteService.commands[0] == TestSpotifyAppRemoteService.PlayerCommand.SetShuffle(enabled = false))
        check(harness.spotifyAppRemoteService.commands[1] == TestSpotifyAppRemoteService.PlayerCommand.SetRepeat(mode = Repeat.OFF))
        check(harness.spotifyAppRemoteService.commands[2] == TestSpotifyAppRemoteService.PlayerCommand.Play(uri = "spotify:album:album123"))
    }

    @Test
    @DisplayName("Starts playback for a saved playlist item")
    fun startsPlaybackForASavedPlaylistItem() {
        harness.seedSavedItems(
            listOf(ShuffleItem(type = "playlist", uri = "spotify:playlist:playlist123", title = "Road Trip Mix")),
        )
        harness.useIdentityShuffle()

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())

        waitUntil(label = "playlist playback start status") {
            Ui.Playback.status().check(matches(withText("Now playing playlist 1 of 1: Road Trip Mix")))
        }
        Ui.Playback.queueRow("▶ 1. Road Trip Mix").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Start guardrails and active controls for start/skip/stop/final item")
    fun startGuardrailsAndActiveControlsForStartSkipStopFinalItem() {
        harness.clearAccessToken()
        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Connect Spotify first").check(matches(isDisplayed()))

        harness.seedConnectedSession()
        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        Ui.Toasts.instance("Add at least one album or playlist first").check(matches(isDisplayed()))

        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:one", title = "One"),
                ShuffleItem(type = "album", uri = "spotify:album:two", title = "Two"),
            ),
        )
        harness.useIdentityShuffle()
        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())

        waitUntil(label = "active playback controls") {
            Ui.Playback.nextButton().check(matches(isEnabled()))
        }
        Ui.Playback.startButton().check(matches(not(isEnabled())))
        Ui.Playback.stopButton().check(matches(isEnabled()))

        Ui.Playback.nextButton().perform(scrollTo(), click())
        waitUntil(label = "second queue item") {
            Ui.Playback.status().check(matches(withText("Now playing album 2 of 2: Two")))
        }

        Ui.Playback.nextButton().perform(scrollTo(), click())
        waitUntil(label = "finished playback") {
            Ui.Playback.status().check(matches(withText("Finished: all selected albums/playlists were played")))
        }

        Ui.Playback.startButton().perform(scrollTo(), click())
        Ui.Playback.stopButton().perform(scrollTo(), click())
        waitUntil(label = "stopped session") {
            Ui.Playback.status().check(matches(withText("Session stopped")))
        }
    }

    @Test
    @DisplayName("Recoverable playback-start failure detaches the session")
    fun recoverablePlaybackStartFailureDetachesTheSession() {
        harness.seedSavedItems(listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")))
        harness.useIdentityShuffle()
        harness.spotifyAppRemoteService.playFailure =
            IllegalStateException("Spotify rate limit reached; please wait a moment and retry: rate limited")

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())

        waitUntil(label = "detached playback start failure") {
            Ui.Playback.status().check(
                matches(
                    withText(
                        "Playback detached due to a Spotify error: Spotify rate limit reached; please wait a moment and retry: rate limited",
                    ),
                ),
            )
        }
        Ui.Playback.reattachButton().check(matches(isDisplayed()))
    }
}
