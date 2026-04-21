package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
            jsonDispatcher { request ->
                when (request.path) {
                    "/v1/me/player" -> {
                        playbackMonitorRequests.incrementAndGet()
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            },
        )

        launchMainActivity()

        onView(withId(R.id.startButton)).perform(scrollTo(), click())

        waitUntil(label = "playback monitor tick to be scheduled") {
            check(harness.playbackMonitorLoop.hasScheduledTick())
            check(harness.playbackMonitorLoop.intervalMs == 4_000L)
        }
        check(playbackMonitorRequests.get() == 0)

        harness.playbackMonitorLoop.triggerTick()

        waitUntil(label = "first playback monitor poll") {
            check(playbackMonitorRequests.get() == 1)
        }

        harness.playbackMonitorLoop.triggerTick()

        waitUntil(label = "second playback monitor poll") {
            check(playbackMonitorRequests.get() == 2)
        }
    }
}
