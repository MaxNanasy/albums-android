package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Playback Monitor")
class PlaybackMonitorUiTest : AbstractUiTestCase() {
    @Test
    @DisplayName("Monitor polls only when harness triggers it")
    fun monitorPollsOnlyWhenHarnessTriggersIt() {
        harness.seedConnectedSession()
        harness.seedSavedItems(
            listOf(
                ShuffleItem(
                    type = "album",
                    uri = "spotify:album:albumOne",
                    title = "Album One",
                ),
            ),
        )
        val playbackMonitorRequests = AtomicInteger(0)
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    playbackMonitorRequests.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.startButton)).perform(scrollTo(), click())

        waitUntil(
            label = "playback monitor tick to be scheduled",
            state = { harness.playbackMonitorLoop.hasScheduledTick() to harness.playbackMonitorLoop.intervalMs },
        ) { (hasScheduledTick, intervalMs) ->
            check(hasScheduledTick)
            check(intervalMs == 4_000L)
        }
        check(playbackMonitorRequests.get() == 0)

        harness.playbackMonitorLoop.triggerTick()

        waitUntil(
            label = "first playback monitor poll",
            state = { playbackMonitorRequests.get() },
        ) { requestCount ->
            check(requestCount == 1)
        }

        harness.playbackMonitorLoop.triggerTick()

        waitUntil(
            label = "second playback monitor poll",
            state = { playbackMonitorRequests.get() },
        ) { requestCount ->
            check(requestCount == 2)
        }
    }

    @Test
    @DisplayName("Monitor advances on null context after observing current context")
    fun monitorAdvancesOnNullContextAfterObservingCurrentContext() {
        harness.seedConnectedSession()
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:one", title = "One"),
                ShuffleItem(type = "album", uri = "spotify:album:two", title = "Two"),
            ),
        )
        harness.useIdentityShuffle()
        val state = AtomicReference("match-one")
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    val body = if (state.get() == "null") {
                        """{"context":null}"""
                    } else {
                        """{"context":{"uri":"spotify:album:one"}}"""
                    }
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
                }
            },
        )

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        waitUntil(label = "initial playback status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }

        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "matched monitor status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }

        state.set("null")
        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "advanced monitor status") {
            Ui.Playback.status().check(matches(withText("Now playing album 2 of 2: Two")))
        }
    }

    @Test
    @DisplayName("Monitor ignores 204 playback snapshots")
    fun monitorIgnores204PlaybackSnapshots() {
        harness.seedConnectedSession()
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:one", title = "One"),
                ShuffleItem(type = "album", uri = "spotify:album:two", title = "Two"),
            ),
        )
        harness.useIdentityShuffle()
        val state = AtomicReference("no-content")
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    if (state.get() == "no-content") {
                        MockResponse().setResponseCode(204)
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"context":{"uri":"spotify:album:one"}}""")
                    }
                }
            },
        )

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        waitUntil(label = "initial no-content status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }

        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "status after first 204") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }

        state.set("match-one")
        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "status after matched snapshot") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }

        state.set("no-content")
        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "status after second 204") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 2: One")))
        }
    }

    @Test
    @DisplayName("Monitor mismatch detaches session with mismatch message")
    fun monitorMismatchDetachesSessionWithMismatchMessage() {
        harness.seedConnectedSession()
        harness.seedSavedItems(listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")))
        harness.useIdentityShuffle()
        val state = AtomicReference("match")
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    val uri = if (state.get() == "match") "spotify:album:one" else "spotify:album:other"
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"context":{"uri":"$uri"}}""")
                }
            },
        )

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        waitUntil(label = "initial matched playback status") {
            Ui.Playback.status().check(matches(withText("Now playing album 1 of 1: One")))
        }

        harness.playbackMonitorLoop.triggerTick()
        state.set("mismatch")
        harness.playbackMonitorLoop.triggerTick()
        waitUntil(label = "mismatch detached status") {
            Ui.Playback.status().check(
                matches(withText("Spotify is playing a different album/playlist than this app expects; reattach to resume")),
            )
        }
    }

    @Test
    @DisplayName("Recoverable monitor errors show status/toast and keep session active")
    fun recoverableMonitorErrorsShowStatusToastAndKeepSessionActive() {
        harness.seedConnectedSession()
        harness.seedSavedItems(listOf(ShuffleItem(type = "album", uri = "spotify:album:one", title = "One")))
        harness.useIdentityShuffle()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/me/player") {
                    MockResponse().setResponseCode(429).setBody("too many requests")
                }
            },
        )

        launchMainActivity()
        Ui.Playback.startButton().perform(scrollTo(), click())
        harness.playbackMonitorLoop.triggerTick()

        waitUntil(label = "recoverable monitor error status") {
            Ui.Playback.status().check(
                matches(withText("Playback monitor encountered an error: Spotify rate limit reached; please wait a moment and retry")),
            )
        }
        Ui.Toasts.instance("Playback monitor encountered an error").check(matches(isDisplayed()))
        Ui.Playback.reattachButton().check(matches(withEffectiveVisibility(GONE)))
        Ui.Playback.nextButton().check(matches(isEnabled()))
    }
}
