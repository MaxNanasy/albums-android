package io.github.maxnanasy.shufflebyalbum

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShareIntentUiTest : AbstractUiTestCase() {
    private lateinit var instrumentation: Instrumentation
    private lateinit var targetContext: Context
    private lateinit var shareIntentMonitor: Instrumentation.ActivityMonitor
    private var launchedActivity: Activity? = null

    @BeforeEach
    fun setUpShareIntentMonitor() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        targetContext = ApplicationProvider.getApplicationContext()
        shareIntentMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        launchedActivity = null
    }

    @AfterEach
    fun tearDownShareIntentMonitor() {
        launchedActivity?.let { activity ->
            instrumentation.runOnMainSync {
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
        launchedActivity = null
        instrumentation.removeMonitor(shareIntentMonitor)
    }

    @Test
    fun shareIntentAddsAlbum() {
        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/sharedAlbum") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"name":"Shared Album"}""")
                }
            },
        )

        launchShareIntent(sharedText = "https://open.spotify.com/album/sharedAlbum")

        waitUntil(label = "shared album to appear in the list") {
            onView(withText("Shared Album")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun shareIntentAddsPlaylistWithoutImportingAlbums() {
        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/sharedPlaylist") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"name":"Shared Playlist"}""")
                }
            },
        )

        launchShareIntent(sharedText = "https://open.spotify.com/playlist/sharedPlaylist?si=test")

        waitUntil(label = "shared playlist to appear in the list") {
            onView(withText("Shared Playlist")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun shareIntentShowsErrorForUnsupportedText() {
        launchShareIntent(sharedText = "https://example.com/not-spotify")

        assertShareErrorDisplayed(label = "share action error to appear")
    }

    @Test
    fun shareIntentShowsErrorForBlankText() {
        launchShareIntent(sharedText = "   ")

        assertShareErrorDisplayed(label = "blank share action error to appear")
    }

    @Test
    fun shareIntentShowsErrorWhenTextIsMissing() {
        launchShareIntent(includeTextExtra = false)

        assertShareErrorDisplayed(label = "missing share text error to appear")
    }

    private fun launchShareIntent(sharedText: String? = null, includeTextExtra: Boolean = true) {
        targetContext.startActivity(
            Intent(Intent.ACTION_SEND).apply {
                setPackage(targetContext.packageName)
                type = "text/plain"
                if (includeTextExtra) {
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )

        launchedActivity = shareIntentMonitor.waitForActivityWithTimeout(ACTIVITY_LAUNCH_TIMEOUT_MS)
            ?: throw AssertionError("MainActivity was not launched for ACTION_SEND")

        val launchedAction = launchedActivity?.intent?.action
        if (launchedAction != Intent.ACTION_SEND) {
            throw AssertionError("Expected ACTION_SEND intent, but was $launchedAction")
        }
    }

    private fun assertShareErrorDisplayed(label: String) {
        waitUntil(label = label) {
            onView(withText(SHARE_ERROR_MESSAGE)).check(matches(isDisplayed()))
        }
    }

    companion object {
        private const val ACTIVITY_LAUNCH_TIMEOUT_MS = 5_000L
        private const val SHARE_ERROR_MESSAGE =
            "Spotify album or playlist required for this Share action"
    }
}
