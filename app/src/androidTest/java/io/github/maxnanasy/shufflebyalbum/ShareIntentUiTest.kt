package io.github.maxnanasy.shufflebyalbum

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentUiTest : AbstractUiTestCase() {
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

        launchMainActivity(
            Intent(Intent.ACTION_SEND).apply {
                setClass(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/album/sharedAlbum")
            },
        )

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

        launchMainActivity(
            Intent(Intent.ACTION_SEND).apply {
                setClass(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/playlist/sharedPlaylist?si=test")
            },
        )

        waitUntil(label = "shared playlist to appear in the list") {
            onView(withText("Shared Playlist")).check(matches(isDisplayed()))
        }
    }
}
