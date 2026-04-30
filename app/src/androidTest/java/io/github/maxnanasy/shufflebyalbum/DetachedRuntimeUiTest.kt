package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withText
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Detached Session and Runtime Restore")
class DetachedRuntimeUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Unrecoverable start error detaches and reattach handles empty queue + missing token")
    fun unrecoverableStartErrorDetachesAndReattachHandlesEmptyQueueAndMissingToken() {
        harness.seedSavedItems(listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")))
        harness.useIdentityShuffle()
        harness.spotifyAppRemoteService.playFailure =
            IllegalStateException("Requested Spotify item or playback device was not found: device missing")

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())

        waitUntil(label = "detached playback status") {
            Ui.Playback.status().check(
                matches(
                    withText(
                        "Playback detached due to a Spotify error: Requested Spotify item or playback device was not found: device missing",
                    ),
                ),
            )
        }
        Ui.Playback.reattachButton().check(matches(isDisplayed()))

        harness.seedRuntimeState(ActivationState.DETACHED, emptyList())
        launchMainActivity()
        Ui.Playback.reattachButton().check(matches(withEffectiveVisibility(GONE)))

        harness.seedRuntimeState(
            activationState = ActivationState.DETACHED,
            queue = listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")),
            index = 0,
        )
        harness.clearAccessToken()
        launchMainActivity()
        Ui.Playback.reattachButton().perform(scrollTo(), click())
        waitUntil(label = "missing token reattach status") {
            Ui.Playback.status().check(matches(withText("Spotify session expired; please reconnect")))
        }
    }

    @Test
    @DisplayName("Reattach with matched context resumes without restarting playback")
    fun reattachWithMatchedContextResumesWithoutRestartingPlayback() {
        harness.seedRuntimeState(
            activationState = ActivationState.DETACHED,
            queue = listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")),
            index = 0,
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"context":{"uri":"spotify:album:one"}}""")
                }
            },
        )

        launchMainActivity()
        Ui.Playback.reattachButton().perform(scrollTo(), click())

        waitUntil(label = "matched reattach status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 1: One")))
        }
        check(harness.spotifyAppRemoteService.commands.none { it is TestSpotifyAppRemoteService.PlayerCommand.Play })
    }

    @Test
    @DisplayName("Recoverable reattach player-state failure shows retry UI and keeps the session detached")
    fun recoverableReattachPlayerStateFailureShowsRetryUiAndKeepsTheSessionDetached() {
        harness.seedRuntimeState(
            activationState = ActivationState.DETACHED,
            queue = listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")),
            index = 0,
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    MockResponse().setResponseCode(500).setBody("server busy")
                }
            },
        )

        launchMainActivity()
        Ui.Playback.reattachButton().perform(scrollTo(), click())

        waitUntil(label = "reattach retry status") {
            Ui.Playback.status().check(
                matches(withText("Failed to reattach: Spotify is temporarily unavailable; please try again shortly")),
            )
        }
        Ui.Toasts.instance("Failed to reattach").check(matches(isDisplayed()))
        Ui.Playback.reattachButton().check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Reattach with mismatched context restarts expected item and detaches on play failure")
    fun reattachWithMismatchedContextRestartsExpectedItemAndDetachesOnPlayFailure() {
        harness.seedRuntimeState(
            activationState = ActivationState.DETACHED,
            queue = listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")),
            index = 0,
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"context":{"uri":"spotify:album:other"}}""")
                }
            },
        )
        harness.spotifyAppRemoteService.playFailure =
            IllegalStateException("Spotify is temporarily unavailable; please try again shortly: play failed")

        launchMainActivity()
        Ui.Playback.reattachButton().perform(scrollTo(), click())

        waitUntil(label = "mismatched reattach failure") {
            Ui.Playback.status().check(
                matches(
                    withText(
                        "Playback detached due to a Spotify error: Spotify is temporarily unavailable; please try again shortly: play failed",
                    ),
                ),
            )
        }
        Ui.Playback.reattachButton().check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Restores active runtime state and ignores invalid runtime JSON")
    fun restoresActiveRuntimeStateAndIgnoresInvalidRuntimeJson() {
        harness.seedRuntimeState(
            activationState = ActivationState.ACTIVE,
            queue = listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")),
            index = 0,
            currentUri = "spotify:album:one",
            observedCurrentContext = false,
        )

        launchMainActivity()
        waitUntil(label = "active runtime restore status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 1: One")))
        }
        Ui.Playback.nextButton().check(matches(isEnabled()))

        harness.seedRawRuntimeState("{bad json")
        launchMainActivity()
        Ui.Auth.status().check(matches(withText("Connected")))
        check(harness.readStringPref(UiTestHarness.KEY_RUNTIME) == null)
    }
}
