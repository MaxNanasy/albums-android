package io.github.maxnanasy.shufflebyalbum

import android.content.Context
import android.os.SystemClock
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.spotify.android.appremote.api.AppRemote
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Types
import java.io.IOException
import java.lang.reflect.Proxy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before

abstract class AbstractUiTestCase {
    protected lateinit var harness: UiTestHarness
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUpUiTestHarness() {
        harness = UiTestHarness()
        harness.start()
    }

    @After
    fun tearDownUiTestHarness() {
        scenario?.close()
        scenario = null
        harness.close()
    }

    protected fun launchMainActivity(): ActivityScenario<MainActivity> {
        return ActivityScenario.launch(MainActivity::class.java).also { launchedScenario ->
            scenario = launchedScenario
        }
    }

    protected inline fun waitUntil(
        label: String = "condition",
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 50L,
        crossinline state: () -> String = { "" },
        crossinline assertion: () -> Unit,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertion()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(intervalMs)
            }
        }
        val stateDescription = runCatching(state).getOrElse { error ->
            val type = error::class.java.simpleName.ifBlank { "Exception" }
            val detail = error.message?.takeIf { it.isNotBlank() }
            if (detail == null) {
                "<unavailable: $type>"
            } else {
                "<unavailable: $type: $detail>"
            }
        }
        val suffix = if (stateDescription.isBlank()) "" else " Last state: $stateDescription"
        throw AssertionError("Timed out waiting for $label.$suffix", lastError)
    }

    protected fun textOf(@IdRes viewId: Int): String? {
        var text: String? = null
        scenario?.onActivity { activity ->
            text = activity.findViewById<TextView>(viewId)?.text?.toString()
        }
        return text
    }
}

class UiTestHarness : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val server = MockWebServer()
    val spotifyAppRemoteService = TestSpotifyAppRemoteService()
    private var started = false

    fun start() {
        clearAppState()
        server.start()
        started = true
        MainActivity.spotifyAccountsBaseUrl = server.url("/").toString().removeSuffix("/")
        MainActivity.spotifyApiBaseUrl = server.url("/v1").toString()
        MainActivity.spotifyAppRemoteService = spotifyAppRemoteService
    }

    fun setDispatcher(dispatcher: Dispatcher) {
        server.dispatcher = dispatcher
    }

    fun enqueueJson(body: String, statusCode: Int = 200): MockResponse {
        val response = MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
        server.enqueue(response)
        return response
    }

    fun seedConnectedSession(
        accessToken: String = "test-access-token",
        refreshToken: String = "test-refresh-token",
        expiresInMs: Long = 60_000L,
        scopes: String = DEFAULT_TOKEN_SCOPES,
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInMs)
            .putString(KEY_TOKEN_SCOPE, scopes)
            .commit()
    }

    fun seedSavedItems(items: List<ShuffleItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("type", item.type)
                    .put("uri", item.uri)
                    .put("title", item.title),
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).commit()
    }

    fun clearAppState() {
        prefs.edit().clear().commit()
        spotifyAppRemoteService.reset()
        MainActivity.spotifyAccountsBaseUrl = DEFAULT_SPOTIFY_ACCOUNTS_BASE_URL
        MainActivity.spotifyApiBaseUrl = DEFAULT_SPOTIFY_API_BASE_URL
        MainActivity.spotifyAppRemoteService = spotifyAppRemoteService
    }

    override fun close() {
        clearAppState()
        if (started) {
            try {
                server.shutdown()
            } catch (_: IOException) {
                // Ignore shutdown failures during test cleanup.
            }
            started = false
        }
    }

    companion object {
        private const val PREFS_NAME = "shuffle-by-album"
        private const val KEY_TOKEN = "shuffle-by-album.token"
        private const val KEY_REFRESH_TOKEN = "shuffle-by-album.refreshToken"
        private const val KEY_TOKEN_EXPIRY = "shuffle-by-album.tokenExpiry"
        private const val KEY_TOKEN_SCOPE = "shuffle-by-album.tokenScope"
        private const val KEY_ITEMS = "shuffle-by-album.items"
        private const val DEFAULT_TOKEN_SCOPES =
            "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative app-remote-control"
        private const val DEFAULT_SPOTIFY_ACCOUNTS_BASE_URL = "https://accounts.spotify.com"
        private const val DEFAULT_SPOTIFY_API_BASE_URL = "https://api.spotify.com/v1"
    }
}

class TestSpotifyAppRemoteService : SpotifyAppRemoteService {
    sealed interface PlayerCommand {
        data class Play(
            val uri: String,
        ) : PlayerCommand

        data class SetShuffle(
            val enabled: Boolean,
        ) : PlayerCommand

        data class SetRepeat(
            val mode: Int,
        ) : PlayerCommand
    }

    var spotifyInstalled = true
    var connectFailure: Throwable? = null
    var commandFailure: Throwable? = null
    var disconnectCount = 0
    val commands = mutableListOf<PlayerCommand>()
    private var connected = false

    override fun isSpotifyInstalled(context: Context): Boolean {
        return spotifyInstalled
    }

    override fun connect(
        context: Context,
        params: com.spotify.android.appremote.api.ConnectionParams,
        onConnected: (AppRemote) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val failure = connectFailure
        if (failure != null) {
            onFailure(failure)
            return
        }
        connected = true
        onConnected(appRemote)
    }

    override fun disconnect(appRemote: AppRemote) {
        connected = false
        disconnectCount += 1
    }

    fun reset() {
        spotifyInstalled = true
        connectFailure = null
        commandFailure = null
        disconnectCount = 0
        commands.clear()
        connected = false
    }

    private val playerApi: PlayerApi =
        Proxy.newProxyInstance(
            PlayerApi::class.java.classLoader,
            arrayOf(PlayerApi::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "play" -> {
                    commands += PlayerCommand.Play(uri = args?.get(0) as String)
                    completedCallResult(commandFailure)
                }
                "setShuffle" -> {
                    commands += PlayerCommand.SetShuffle(enabled = args?.get(0) as Boolean)
                    completedCallResult(commandFailure)
                }
                "setRepeat" -> {
                    commands += PlayerCommand.SetRepeat(mode = args?.get(0) as Int)
                    completedCallResult(commandFailure)
                }
                "toString" -> "TestPlayerApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("Unsupported PlayerApi method: ${method.name}")
            }
        } as PlayerApi

    private val appRemote: AppRemote =
        Proxy.newProxyInstance(
            AppRemote::class.java.classLoader,
            arrayOf(AppRemote::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getPlayerApi" -> playerApi
                "isConnected" -> connected
                "toString" -> "TestAppRemote"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("Unsupported AppRemote method: ${method.name}")
            }
        } as AppRemote

    private fun completedCallResult(error: Throwable?): CallResult<Any?> {
        return ImmediateCallResult(error = error)
    }
}

private class ImmediateCallResult<T>(
    private val value: T? = null,
    private val error: Throwable? = null,
) : CallResult<T>(Types.RequestId.NONE) {
    init {
        error?.let(::deliverError)
    }

    override fun setResultCallback(callback: CallResult.ResultCallback<T>): CallResult<T> {
        if (error == null) {
            callback.onResult(value)
        }
        return this
    }
}

fun jsonDispatcher(handler: (RecordedRequest) -> MockResponse): Dispatcher {
    return object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return handler(request)
        }
    }
}
