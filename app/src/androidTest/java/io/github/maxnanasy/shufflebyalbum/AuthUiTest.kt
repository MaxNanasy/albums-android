package io.github.maxnanasy.shufflebyalbum

import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Auth and Connection States")
class AuthUiTest : AbstractUiTestCase() {
    @Test
    @DisplayName("Cold start without token shows disconnected and disconnect clears auth")
    fun coldStartWithoutTokenShowsDisconnectedAndDisconnectClearsAuth() {
        launchMainActivity()

        Ui.Auth.status().check(matches(withText("Not connected")))
        Ui.Auth.disconnectButton().perform(click())
        Ui.Toasts.instance("Disconnected from Spotify").check(matches(isDisplayed()))
    }

    @Test
    @DisplayName("Connect button stores a PKCE verifier and redirects to Spotify authorize")
    fun connectButtonStoresAPkceVerifierAndRedirectsToSpotifyAuthorize() {
        launchMainActivity()

        Ui.Auth.connectButton().perform(click())

        waitUntil(label = "authorization launch attempt") {
            check(harness.authorizationLaunchAttempts.size == 1)
        }

        val attempt = harness.authorizationLaunchAttempts.single()
        check(harness.readStringPref(UiTestHarness.KEY_VERIFIER).isNullOrBlank().not())
        check(attempt.responseType == "code")
        check(attempt.redirectUri == "https://unused-but-required-redirect-uri.invalid")
        check(
            attempt.scopes.joinToString(" ") ==
                "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative app-remote-control",
        )
        check(attempt.codeChallengeMethod == "S256")
        check(!attempt.showDialog)
    }

    @Test
    @DisplayName("Expired access token with refresh token silently refreshes during bootstrap")
    fun expiredAccessTokenWithRefreshTokenSilentlyRefreshesDuringBootstrap() {
        harness.seedConnectedSession(
            accessToken = "expired-access-token",
            refreshToken = "refresh-token",
            expiresInMs = -5_000L,
            scopes = "user-modify-playback-state",
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/api/token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "access_token":"new-access-token",
                              "refresh_token":"new-refresh-token",
                              "expires_in":3600,
                              "scope":"user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative"
                            }
                            """.trimIndent(),
                        )
                }
            },
        )

        launchMainActivity()

        waitUntil(label = "connected auth status") {
            Ui.Auth.status().check(matches(withText("Connected")))
        }
        check(harness.readStringPref(UiTestHarness.KEY_TOKEN) == "new-access-token")
        check(harness.readStringPref(UiTestHarness.KEY_REFRESH_TOKEN) == "new-refresh-token")
        check(harness.readLongPref(UiTestHarness.KEY_TOKEN_EXPIRY) > System.currentTimeMillis())
        check(
            harness.readStringPref(UiTestHarness.KEY_TOKEN_SCOPE) ==
                "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative",
        )
    }

    @Test
    @DisplayName("Expired access token with unsuccessful refresh shows auth restore failure")
    fun expiredAccessTokenWithUnsuccessfulRefreshShowsAuthRestoreFailure() {
        harness.seedConnectedSession(
            accessToken = "expired-access-token",
            refreshToken = "refresh-token",
            expiresInMs = -5_000L,
        )
        harness.setDispatcher(
            jsonDispatcher {
                route("/api/token") {
                    MockResponse().setResponseCode(400).setBody("bad refresh")
                }
            },
        )

        launchMainActivity()

        waitUntil(label = "auth restore failure") {
            Ui.Auth.status().check(matches(withText("Unable to restore Spotify session; please reconnect")))
        }
    }

    @Test
    @DisplayName("Missing playlist scopes shows reconnect warning")
    fun missingPlaylistScopesShowsReconnectWarning() {
        harness.seedConnectedSession(
            scopes = "user-modify-playback-state user-read-playback-state",
        )

        launchMainActivity()

        Ui.Auth.status().check(
            matches(withText("Connected, but token is missing playlist import scopes; disconnect and reconnect")),
        )
    }

    @Test
    @DisplayName("Auth redirect with `access_denied` clears query and reports authorization denied")
    fun authRedirectWithAccessDeniedClearsQueryAndReportsAuthorizationDenied() {
        launchMainActivity()

        deliverSpotifyAuthorizationResponse(
            AuthorizationResponse.Builder()
                .setType(AuthorizationResponse.Type.ERROR)
                .setError("access_denied")
                .build(),
        )

        waitUntil(label = "authorization denied status") {
            Ui.Auth.status().check(matches(withText("Spotify authorization denied")))
        }
    }

    @Test
    @DisplayName("Auth redirect with other error clears query and reports an explicit auth error")
    fun authRedirectWithOtherErrorClearsQueryAndReportsAnExplicitAuthError() {
        launchMainActivity()

        deliverSpotifyAuthorizationResponse(
            AuthorizationResponse.Builder()
                .setType(AuthorizationResponse.Type.ERROR)
                .setError("unauthorized_client")
                .build(),
        )

        waitUntil(label = "explicit authorization error") {
            Ui.Auth.status().check(matches(withText("Spotify authorization error: unauthorized_client")))
        }
    }

    @Test
    @DisplayName("Auth redirect with code and missing verifier clears the code and reports the missing verifier")
    fun authRedirectWithCodeAndMissingVerifierClearsTheCodeAndReportsTheMissingVerifier() {
        launchMainActivity()

        deliverSpotifyAuthorizationResponse(
            AuthorizationResponse.Builder()
                .setType(AuthorizationResponse.Type.CODE)
                .setCode("abc123")
                .build(),
        )

        waitUntil(label = "missing verifier status") {
            Ui.Auth.status().check(matches(withText("Spotify authorization failed: Missing PKCE verifier")))
        }
    }

    @Test
    @DisplayName("Successful code exchange stores tokens, clears verifier, and removes code from the URL")
    fun successfulCodeExchangeStoresTokensClearsVerifierAndRemovesCodeFromTheUrl() {
        harness.seedVerifier("verifier")
        harness.setDispatcher(
            jsonDispatcher {
                route("/api/token") {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "access_token":"exchange-access-token",
                              "refresh_token":"exchange-refresh-token",
                              "expires_in":3600,
                              "scope":"user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative"
                            }
                            """.trimIndent(),
                        )
                }
            },
        )

        launchMainActivity()
        deliverSpotifyAuthorizationResponse(
            AuthorizationResponse.Builder()
                .setType(AuthorizationResponse.Type.CODE)
                .setCode("abc123")
                .build(),
        )

        waitUntil(label = "successful code exchange status") {
            Ui.Auth.status().check(matches(withText("Connected")))
        }
        check(harness.readStringPref(UiTestHarness.KEY_TOKEN) == "exchange-access-token")
        check(harness.readStringPref(UiTestHarness.KEY_REFRESH_TOKEN) == "exchange-refresh-token")
        check(harness.readLongPref(UiTestHarness.KEY_TOKEN_EXPIRY) > System.currentTimeMillis())
        check(
            harness.readStringPref(UiTestHarness.KEY_TOKEN_SCOPE) ==
                "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative",
        )
        check(harness.readStringPref(UiTestHarness.KEY_VERIFIER) == null)
    }

    @Test
    @DisplayName("Failed code exchange clears the code, clears the verifier, and reports the exchange failure")
    fun failedCodeExchangeClearsTheCodeClearsTheVerifierAndReportsTheExchangeFailure() {
        harness.seedVerifier("verifier")
        val requestCount = AtomicInteger(0)
        harness.setDispatcher(
            jsonDispatcher {
                route("/api/token") {
                    requestCount.incrementAndGet()
                    MockResponse().setResponseCode(400).setBody("bad code")
                }
            },
        )

        launchMainActivity()
        deliverSpotifyAuthorizationResponse(
            AuthorizationResponse.Builder()
                .setType(AuthorizationResponse.Type.CODE)
                .setCode("abc123")
                .build(),
        )

        waitUntil(label = "failed code exchange status") {
            Ui.Auth.status().check(
                matches(withText("Spotify token exchange failed: Network error while contacting Spotify; please try again")),
            )
        }
        check(harness.readStringPref(UiTestHarness.KEY_VERIFIER) == null)
        check(requestCount.get() == 1)
    }
}
