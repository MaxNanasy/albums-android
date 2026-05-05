package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Playlist Album Import")
class PlaylistImportUiTest : AbstractUiTestCase() {
    @BeforeEach
    fun seedConnectedAuth() {
        harness.seedConnectedSession()
    }

    @Test
    @DisplayName("Imports playlist albums across pages and skips saved duplicates")
    fun importsPlaylistAlbumsAcrossPagesAndSkipsSavedDuplicates() {
        harness.seedSavedItems(
            listOf(ShuffleItem(type = "album", uri = "spotify:album:existing", title = "Existing Album")),
        )
        val requests = CopyOnWriteArrayList<String>()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") { request ->
                    requests += request.path.orEmpty()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "items":[
                                {"item":{"album":{"uri":"spotify:album:existing","name":"Existing Album"}}},
                                {"item":{"album":{"uri":"spotify:album:new-one","name":"New Album One"}}}
                              ],
                              "next":"https://api.spotify.com/v1/playlists/playlist123/items?offset=50"
                            }
                            """.trimIndent(),
                        )
                }
                route("/v1/playlists/playlist123/items?limit=50&offset=50&additional_types=track&market=from_token") { request ->
                    requests += request.path.orEmpty()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "items":[
                                {"item":{"album":{"uri":"spotify:album:new-one","name":"New Album One"}}},
                                {"item":{"album":{"uri":"spotify:album:new-two","name":"New Album Two"}}}
                              ],
                              "next":null
                            }
                            """.trimIndent(),
                        )
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())

        waitUntil(label = "imported playlist albums") {
            Ui.SavedItems.row("Existing Album").check(matches(isDisplayed()))
            Ui.SavedItems.row("New Album One").check(matches(isDisplayed()))
            Ui.SavedItems.row("New Album Two").check(matches(isDisplayed()))
        }
        Ui.Toasts.instance("Imported 2 album(s) from playlist (3 unique album(s) found)").check(matches(isDisplayed()))
        check(
            requests.toList() == listOf(
                "/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token",
                "/v1/playlists/playlist123/items?limit=50&offset=50&additional_types=track&market=from_token",
            ),
        )
    }

    @Test
    @DisplayName("Imports playlist albums from a Spotify playlist URL")
    fun importsPlaylistAlbumsFromASpotifyPlaylistUrl() {
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"items":[{"item":{"album":{"uri":"spotify:album:new-one","name":"New Album One"}}}],"next":null}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("https://open.spotify.com/playlist/playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())

        waitUntil(label = "playlist URL import row") {
            Ui.SavedItems.row("New Album One").check(matches(isDisplayed()))
        }
        Ui.Toasts.instance("Imported 1 album(s) from playlist (1 unique album(s) found)").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Imports playlist albums from a Spotify playlist URI")
    fun importsPlaylistAlbumsFromASpotifyPlaylistUri() {
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"items":[{"item":{"album":{"uri":"spotify:album:new-two","name":"New Album Two"}}}],"next":null}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("spotify:playlist:playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())

        waitUntil(label = "playlist URI import row") {
            Ui.SavedItems.row("New Album Two").check(matches(isDisplayed()))
        }
        Ui.Toasts.instance("Imported 1 album(s) from playlist (1 unique album(s) found)").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Playlist import unhappy paths and no-op imports")
    fun playlistImportUnhappyPathsAndNoOpImports() {
        harness.clearAccessToken()
        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())
        Ui.Toasts.instance("Connect Spotify first so the app can import albums").check(matches(isDisplayed()))

        harness.seedConnectedSession()
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse().setResponseCode(500).setBody("boom")
                }
                route("/v1/playlists/emptyplaylist/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""{"items":[],"next":null}""")
                }
            },
        )
        launchMainActivity()

        Ui.SavedItems.uriInput().perform(replaceText("$$$"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())
        Ui.Toasts.instance("Enter a valid Spotify playlist URL, URI, or playlist ID").check(matches(isDisplayed()))

        Ui.SavedItems.uriInput().perform(replaceText("playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())
        Ui.Toasts.instance("Error importing albums: 500 boom").check(matches(isDisplayed()))

        Ui.SavedItems.uriInput().perform(replaceText("emptyplaylist"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())
        Ui.Toasts.instance("Imported 0 album(s) from playlist (0 unique album(s) found)").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Importing playlist with all albums already saved keeps list unchanged")
    fun importingPlaylistWithAllAlbumsAlreadySavedKeepsListUnchanged() {
        harness.seedSavedItems(
            listOf(ShuffleItem(type = "album", uri = "spotify:album:existing", title = "Existing Album")),
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/v1/playlists/playlist123/items?limit=50&offset=0&additional_types=track&market=from_token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"items":[{"item":{"album":{"uri":"spotify:album:existing","name":"Existing Album"}}}],"next":null}""")
                }
            },
        )

        launchMainActivity()
        Ui.SavedItems.uriInput().perform(replaceText("playlist123"), closeSoftKeyboard())
        Ui.SavedItems.importAlbumsButton().perform(click())

        Ui.Toasts.instance("Imported 0 album(s) from playlist (1 unique album(s) found)").check(matches(isDisplayed()))
        check(textsInRecycler(R.id.itemRecycler, R.id.title) == listOf("Existing Album"))
    }
}
