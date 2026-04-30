package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Startup Item Title Refresh")
class StartupItemRefreshUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Startup title refresh updates missing title and tolerates failures")
    fun startupTitleRefreshUpdatesMissingTitleAndToleratesFailures() {
        harness.seedSavedItems(
            listOf(
                ShuffleItem(type = "album", uri = "spotify:album:ok", title = ""),
                ShuffleItem(type = "album", uri = "spotify:album:fail", title = ""),
            ),
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/albums/ok") {
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"name":"OK Title"}""")
                }
                route("/v1/albums/fail") {
                    MockResponse().setResponseCode(404)
                }
            },
        )

        launchMainActivity()

        waitUntil(label = "startup title refresh rows") {
            Ui.SavedItems.row("OK Title").check(matches(isDisplayed()))
            Ui.SavedItems.row("spotify:album:fail").check(matches(isDisplayed()))
        }
    }
}
