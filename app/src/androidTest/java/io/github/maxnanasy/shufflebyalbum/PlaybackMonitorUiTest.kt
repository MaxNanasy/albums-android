package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Test

class PlaybackMonitorUiTest : AbstractUiTestCase() {
    @Test
    fun playbackMonitorRunsOnlyWhenHarnessTriggersIt() {
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
}
