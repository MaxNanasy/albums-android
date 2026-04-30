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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Share Intent")
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
    @DisplayName("Share intent adds album")
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
    @DisplayName("Share intent adds playlist without importing albums")
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
    @DisplayName("Share intent shows error for unsupported text")
    fun shareIntentShowsErrorForUnsupportedText() {
        launchShareIntent(sharedText = "https://example.com/not-spotify")

        assertShareErrorDisplayed(waitLabel = "share action error to appear")
    }

    @Test
    @DisplayName("Share intent shows error for blank text")
    fun shareIntentShowsErrorForBlankText() {
        launchShareIntent(sharedText = "   ")

        assertShareErrorDisplayed(waitLabel = "blank share action error to appear")
    }

    @Test
    @DisplayName("Share intent shows error when text is missing")
    fun shareIntentShowsErrorWhenTextIsMissing() {
        launchShareIntent(includeTextExtra = false)

        assertShareErrorDisplayed(waitLabel = "missing share text error to appear")
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

    private fun assertShareErrorDisplayed(waitLabel: String) {
        waitUntil(label = waitLabel) {
            onView(withText(SHARE_ERROR_MESSAGE)).check(matches(isDisplayed()))
        }
    }

    companion object {
        private const val ACTIVITY_LAUNCH_TIMEOUT_MS = 5_000L
        private const val SHARE_ERROR_MESSAGE =
            "Spotify album or playlist required for this Share action"
    }
}
