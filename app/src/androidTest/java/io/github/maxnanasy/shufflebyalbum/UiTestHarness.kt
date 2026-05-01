package io.github.maxnanasy.shufflebyalbum

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import com.spotify.android.appremote.api.AppRemote
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Types
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.io.IOException
import java.lang.IllegalStateException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

abstract class AbstractUiTestCase {
    protected lateinit var harness: UiTestHarness
    private var scenario: ActivityScenario<MainActivity>? = null

    @BeforeEach
    fun setUpUiTestHarness() {
        harness = UiTestHarness()
        harness.start()
    }

    @AfterEach
    fun tearDownUiTestHarness() {
        scenario?.close()
        scenario = null
        harness.close()
    }

    protected fun launchMainActivity(intent: Intent? = null): ActivityScenario<MainActivity> {
        scenario?.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent = intent ?: Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch<MainActivity>(launchIntent).also { launchedScenario ->
            scenario = launchedScenario
        }
    }

    protected inline fun waitUntil(
        label: String = "condition",
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 50L,
        crossinline assertion: () -> Unit,
    ) {
        waitUntil(
            label = label,
            timeoutMs = timeoutMs,
            intervalMs = intervalMs,
            state = { Unit },
        ) { assertion() }
    }

    protected inline fun <T> waitUntil(
        label: String = "condition",
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 50L,
        crossinline state: () -> T,
        crossinline assertion: (T) -> Unit,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        var lastStateDescription = ""
        var hasLastState = false
        val describeUnavailableWaitState: (Throwable) -> String = { error ->
            val type = error::class.java.simpleName.ifBlank { "Exception" }
            val detail = error.message?.takeIf { it.isNotBlank() }
            if (detail == null) {
                "<unavailable: $type>"
            } else {
                "<unavailable: $type: $detail>"
            }
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            val currentState = try {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                state()
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(intervalMs)
                continue
            }
            val currentStateDescription = runCatching { currentState?.toString().orEmpty() }
                .getOrElse(describeUnavailableWaitState)
            try {
                assertion(currentState)
                return
            } catch (error: Throwable) {
                lastStateDescription = currentStateDescription
                hasLastState = true
                lastError = error
                SystemClock.sleep(intervalMs)
            }
        }
        val suffix = if (hasLastState && lastStateDescription.isNotBlank()) {
            " Last state: $lastStateDescription"
        } else {
            ""
        }
        throw AssertionError("Timed out waiting for $label.$suffix", lastError)
    }

    protected fun textOf(@IdRes viewId: Int): String? {
        var text: String? = null
        scenario?.onActivity { activity ->
            text = activity.findViewById<TextView>(viewId)?.text?.toString()
        }
        return text
    }

    protected fun performOnActivity(action: (MainActivity) -> Unit) {
        scenario?.onActivity(action)
            ?: throw AssertionError("MainActivity has not been launched")
    }

    protected fun deliverSpotifyAuthorizationResponse(response: AuthorizationResponse) {
        performOnActivity { activity ->
            activity.completeSpotifyAuthorizationForTest(response)
        }
    }

    protected fun textsInRecycler(@IdRes recyclerId: Int, @IdRes textViewId: Int): List<String> {
        var texts = emptyList<String>()
        scenario?.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(recyclerId)
            texts = buildList {
                repeat(recycler.childCount) { index ->
                    collectTexts(recycler.getChildAt(index), textViewId, this)
                }
            }
        }
        return texts
    }

    protected fun clickRecyclerActionByTitle(
        @IdRes recyclerId: Int,
        title: String,
        @IdRes actionViewId: Int,
    ) {
        performOnActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(recyclerId)
            var clicked = false
            repeat(recycler.childCount) { index ->
                val child = recycler.getChildAt(index)
                val titleView = child.findViewById<TextView>(R.id.title)
                if (titleView?.text?.toString() == title) {
                    child.findViewById<View>(actionViewId)?.performClick()
                    clicked = true
                }
            }
            check(clicked) { "No recycler action found for $title in $recyclerId" }
        }
    }

    private fun collectTexts(view: View, @IdRes textViewId: Int, texts: MutableList<String>) {
        if (view.id == textViewId && view is TextView) {
            texts += view.text.toString()
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                collectTexts(view.getChildAt(index), textViewId, texts)
            }
        }
    }
}

class UiTestHarness : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val server = MockWebServer()
    val spotifyAppRemoteService = TestSpotifyAppRemoteService()
    val playbackMonitorLoop = TestPlaybackMonitorLoop()
    internal val authorizationLaunchAttempts = mutableListOf<AuthorizationLaunchAttempt>()
    private val unexpectedRequestFailure = AtomicReference<AssertionError?>(null)
    private var started = false

    fun start() {
        clearAppState()
        server.dispatcher = failOnUnexpectedRequests(
            reason = "This test did not call harness.setDispatcher(...).",
        )
        server.start()
        started = true
        MainActivity.spotifyAccountsBaseUrl = server.url("/").toString().removeSuffix("/")
        MainActivity.spotifyApiBaseUrl = server.url("/v1").toString()
        MainActivity.spotifyAppRemoteService = spotifyAppRemoteService
        MainActivity.playbackMonitorLoopFactory = { playbackMonitorLoop }
        MainActivity.authorizationLaunchInterceptor = { attempt ->
            authorizationLaunchAttempts += attempt
        }
    }

    fun setDispatcher(dispatcher: Dispatcher) {
        server.dispatcher = dispatcher.withUnexpectedRequestFailure { request ->
            failRequest(
                request = request,
                reason = "No matching handler was configured for this request.",
            )
        }
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
        prefs.edit()
            .putString(KEY_ITEMS, serializeShuffleItems(items).toString())
            .commit()
    }

    fun seedRemovedItems(items: List<ShuffleItem>) {
        prefs.edit()
            .putString(KEY_REMOVED_ITEMS, serializeShuffleItems(items).toString())
            .commit()
    }

    fun seedRuntimeState(
        activationState: ActivationState,
        queue: List<ShuffleItem>,
        index: Int = 0,
        currentUri: String? = null,
        observedCurrentContext: Boolean = false,
    ) {
        val payload = JSONObject()
            .put("activationState", activationState.value)
            .put("queue", serializeShuffleItems(queue))
            .put("index", index)
            .put("currentUri", currentUri)
            .put("observedCurrentContext", observedCurrentContext)
        prefs.edit().putString(KEY_RUNTIME, payload.toString()).commit()
    }

    fun seedRawRuntimeState(raw: String) {
        prefs.edit().putString(KEY_RUNTIME, raw).commit()
    }

    fun seedVerifier(verifier: String = "verifier") {
        prefs.edit().putString(KEY_VERIFIER, verifier).commit()
    }

    fun clearAccessToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_TOKEN_SCOPE)
            .commit()
    }

    fun seedRawItemsJson(raw: String) {
        prefs.edit().putString(KEY_ITEMS, raw).commit()
    }

    fun seedRawRemovedItemsJson(raw: String) {
        prefs.edit().putString(KEY_REMOVED_ITEMS, raw).commit()
    }

    fun useIdentityShuffle() {
        MainActivity.shuffleOverride = { items -> items.toMutableList() }
    }

    fun readStringPref(key: String): String? {
        return prefs.getString(key, null)
    }

    fun readLongPref(key: String): Long {
        return prefs.getLong(key, Long.MIN_VALUE)
    }

    fun savedItemTitles(): List<String> {
        return readTitlesFromPref(KEY_ITEMS)
    }

    fun removedItemTitles(): List<String> {
        return readTitlesFromPref(KEY_REMOVED_ITEMS)
    }

    fun runtimeQueueTitles(): List<String> {
        val raw = prefs.getString(KEY_RUNTIME, null) ?: return emptyList()
        return try {
            val queue = JSONObject(raw).optJSONArray("queue") ?: JSONArray()
            buildList {
                for (index in 0 until queue.length()) {
                    val item = queue.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    val uri = item.optString("uri")
                    add(if (title.isNotBlank()) title else uri)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runtimeIndex(): Int? {
        val raw = prefs.getString(KEY_RUNTIME, null) ?: return null
        return try {
            JSONObject(raw).optInt("index")
        } catch (_: Exception) {
            null
        }
    }

    fun runtimeObservedCurrentContext(): Boolean {
        val raw = prefs.getString(KEY_RUNTIME, null) ?: return false
        return try {
            JSONObject(raw).optBoolean("observedCurrentContext")
        } catch (_: Exception) {
            false
        }
    }

    fun clearAppState() {
        prefs.edit().clear().commit()
        spotifyAppRemoteService.reset()
        playbackMonitorLoop.reset()
        authorizationLaunchAttempts.clear()
        unexpectedRequestFailure.set(null)
        MainActivity.spotifyAccountsBaseUrl = DEFAULT_SPOTIFY_ACCOUNTS_BASE_URL
        MainActivity.spotifyApiBaseUrl = DEFAULT_SPOTIFY_API_BASE_URL
        MainActivity.spotifyAppRemoteService = spotifyAppRemoteService
        MainActivity.playbackMonitorLoopFactory = MainActivity.defaultPlaybackMonitorLoopFactory
        MainActivity.authorizationLaunchInterceptor = null
        MainActivity.shuffleOverride = null
    }

    override fun close() {
        val failure = unexpectedRequestFailure.get()
        clearAppState()
        if (started) {
            try {
                server.shutdown()
            } catch (_: IOException) {
                // Ignore shutdown failures during test cleanup.
            }
            started = false
        }
        failure?.let { throw it }
    }

    private fun readTitlesFromPref(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val items = JSONArray(raw)
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val title = item.optString("title")
                    val uri = item.optString("uri")
                    add(if (title.isNotBlank()) title else uri)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeShuffleItems(items: List<ShuffleItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("type", item.type)
                        .put("uri", item.uri)
                        .put("title", item.title),
                )
            }
        }
    }

    private fun failOnUnexpectedRequests(reason: String): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return failRequest(request = request, reason = reason)
            }
        }
    }

    private fun failRequest(request: RecordedRequest, reason: String): MockResponse {
        val message = buildString {
            append("Unexpected mock Spotify request: ")
            append(request.method ?: "<unknown method>")
            append(' ')
            append(request.path ?: "<unknown path>")
            append(". ")
            append(reason)
        }
        unexpectedRequestFailure.compareAndSet(null, AssertionError(message))
        return MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "text/plain")
            .setBody(message)
    }

    companion object {
        const val PREFS_NAME: String = "shuffle-by-album"
        const val KEY_VERIFIER: String = "shuffle-by-album.pkceVerifier"
        const val KEY_TOKEN: String = "shuffle-by-album.token"
        const val KEY_REFRESH_TOKEN: String = "shuffle-by-album.refreshToken"
        const val KEY_TOKEN_EXPIRY: String = "shuffle-by-album.tokenExpiry"
        const val KEY_TOKEN_SCOPE: String = "shuffle-by-album.tokenScope"
        const val KEY_ITEMS: String = "shuffle-by-album.items"
        const val KEY_REMOVED_ITEMS: String = "shuffle-by-album.removedItems"
        const val KEY_RUNTIME: String = "shuffle-by-album.runtime"
        private const val DEFAULT_TOKEN_SCOPES =
            "user-modify-playback-state user-read-playback-state playlist-read-private playlist-read-collaborative app-remote-control"
        private const val DEFAULT_SPOTIFY_ACCOUNTS_BASE_URL = "https://accounts.spotify.com"
        private const val DEFAULT_SPOTIFY_API_BASE_URL = "https://api.spotify.com/v1"
    }
}

class TestPlaybackMonitorLoop : PlaybackMonitorLoop {
    var intervalMs: Long? = null
        private set
    private var task: (() -> Unit)? = null

    override fun start(intervalMs: Long, task: () -> Unit) {
        this.intervalMs = intervalMs
        this.task = task
    }

    override fun stop() {
        task = null
    }

    fun hasScheduledTick(): Boolean {
        return task != null
    }

    fun triggerTick() {
        val currentTask = checkNotNull(task) { "No playback monitor tick is scheduled." }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            currentTask()
        }
    }

    fun reset() {
        intervalMs = null
        task = null
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
    var shuffleFailure: Throwable? = null
    var repeatFailure: Throwable? = null
    var playFailure: Throwable? = null
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
        shuffleFailure = null
        repeatFailure = null
        playFailure = null
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
                    completedCallResult(playFailure)
                }
                "setShuffle" -> {
                    commands += PlayerCommand.SetShuffle(enabled = args?.get(0) as Boolean)
                    completedCallResult(shuffleFailure)
                }
                "setRepeat" -> {
                    commands += PlayerCommand.SetRepeat(mode = args?.get(0) as Int)
                    completedCallResult(repeatFailure)
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

fun jsonDispatcher(configure: JsonDispatcherBuilder.() -> Unit): Dispatcher {
    return JsonDispatcherBuilder().apply(configure)
}

class JsonDispatcherBuilder : Dispatcher() {
    private val routes = linkedMapOf<String, (RecordedRequest) -> MockResponse>()

    fun route(path: String, response: MockResponse) {
        route(path) { response.clone() }
    }

    fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        check(routes.put(path, handler) == null) { "A handler is already configured for $path." }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val handler = routes[request.path]
            ?: throw UnhandledMockRequestException(request)
        return handler(request)
    }
}

private class UnhandledMockRequestException(
    val request: RecordedRequest,
) : IllegalStateException("No handler configured for ${request.path}")

private fun Dispatcher.withUnexpectedRequestFailure(
    onUnexpectedRequest: (RecordedRequest) -> MockResponse,
): Dispatcher {
    return object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return try {
                this@withUnexpectedRequestFailure.dispatch(request)
            } catch (error: UnhandledMockRequestException) {
                onUnexpectedRequest(error.request)
            }
        }
    }
}
