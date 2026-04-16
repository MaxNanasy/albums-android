package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
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
}
