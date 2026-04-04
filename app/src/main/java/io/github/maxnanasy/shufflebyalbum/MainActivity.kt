package io.github.maxnanasy.shufflebyalbum

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val prefs by lazy { getSharedPreferences("spotifyShuffler", Context.MODE_PRIVATE) }

    private lateinit var authStatus: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var itemUriInput: EditText
    private lateinit var storageJsonInput: EditText
    private lateinit var undoBannerContainer: LinearLayout

    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var addButton: Button
    private lateinit var importPlaylistButton: Button
    private lateinit var startButton: Button
    private lateinit var reattachButton: Button
    private lateinit var skipButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportStorageButton: Button
    private lateinit var importStorageButton: Button

    private val itemAdapter = ItemAdapter(onRemove = ::removeItem)
    private val queueAdapter = QueueAdapter()
    private val pendingRemovals = mutableMapOf<Long, PendingRemoval>()
    private var nextPendingRemovalId: Long = 0

    private var session = SessionState()

    private val monitorHandler = Handler(Looper.getMainLooper())
    private val monitorTask = object : Runnable {
        override fun run() {
            appScope.launch {
                monitorPlayback()
            }
            monitorHandler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupLists()
        wireEvents()

        restoreRuntimeState()
        renderItemList()
        renderQueue()
        renderPlaybackControls()

        appScope.launch {
            bootstrapAuthState(intent?.data)
            restoreSessionMonitoringIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthRedirect(intent.data)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitorLoop()
        appScope.cancel()
    }

    private fun bindViews() {
        authStatus = findViewById(R.id.authStatus)
        playbackStatus = findViewById(R.id.playbackStatus)
        itemUriInput = findViewById(R.id.itemUriInput)
        storageJsonInput = findViewById(R.id.storageJsonInput)
        undoBannerContainer = findViewById(R.id.undoBannerContainer)

        loginButton = findViewById(R.id.loginButton)
        logoutButton = findViewById(R.id.logoutButton)
        addButton = findViewById(R.id.addButton)
        importPlaylistButton = findViewById(R.id.importPlaylistButton)
        startButton = findViewById(R.id.startButton)
        reattachButton = findViewById(R.id.reattachButton)
        skipButton = findViewById(R.id.skipButton)
        stopButton = findViewById(R.id.stopButton)
        exportStorageButton = findViewById(R.id.exportStorageButton)
        importStorageButton = findViewById(R.id.importStorageButton)
    }

    private fun setupLists() {
        findViewById<RecyclerView>(R.id.itemRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = itemAdapter
        }
        findViewById<RecyclerView>(R.id.queueRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
        }
    }

    private fun wireEvents() {
        loginButton.setOnClickListener { startLogin() }
        logoutButton.setOnClickListener {
            clearAuth()
            refreshAuthStatus()
            toast("Disconnected from Spotify.")
        }
        addButton.setOnClickListener { appScope.launch { addItem() } }
        itemUriInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addButton.performClick()
                true
            } else {
                false
            }
        }
        importPlaylistButton.setOnClickListener { appScope.launch { importAlbumsFromPlaylist() } }
        startButton.setOnClickListener { appScope.launch { startShuffleSession() } }
        reattachButton.setOnClickListener { appScope.launch { reattachSession() } }
        skipButton.setOnClickListener { appScope.launch { goToNextItem() } }
        stopButton.setOnClickListener { stopSession("Session stopped.") }
        exportStorageButton.setOnClickListener { exportStorageJson() }
        importStorageButton.setOnClickListener { importStorageJson() }
    }

    private fun refreshAuthStatus() {
        authStatus.text = if (getToken() == null) "Not connected." else "Connected."
    }

    private fun startLogin() {
        val verifier = randomString(64)
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val challenge = codeChallengeFromVerifier(verifier)

        val authUri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", SPOTIFY_APP_ID)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("show_dialog", "true")
            .build()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, authUri))
        } catch (_: ActivityNotFoundException) {
            toast("Unable to open browser for Spotify login.")
        }
    }

    private fun handleAuthRedirect(uri: Uri?) {
        if (uri == null || uri.scheme != "shufflebyalbum") return
        appScope.launch {
            processAuthRedirect(uri)
        }
    }

    private suspend fun bootstrapAuthState(uri: Uri?) {
        if (uri != null && uri.scheme == "shufflebyalbum") {
            processAuthRedirect(uri)
            return
        }

        if (getToken() != null) {
            refreshAuthStatus()
            return
        }

        if (getStringPref(KEY_REFRESH_TOKEN).isNullOrBlank()) {
            refreshAuthStatus()
            return
        }

        authStatus.text = "Restoring Spotify session..."
        val token = refreshSpotifyAccessToken()
        if (token == null) {
            authStatus.text = "Spotify session restore failed. Connect again."
            toast("Could not restore Spotify session. Please reconnect.")
            return
        }
        refreshAuthStatus()
    }

    private suspend fun processAuthRedirect(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (error != null) {
            authStatus.text = "Spotify authorization error: $error"
            return
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            authStatus.text = "Spotify authorization failed: missing authorization code."
            toast("Spotify login did not return an authorization code.")
            return
        }
        val verifier = getStringPref(KEY_VERIFIER)
        if (verifier.isNullOrBlank()) {
            authStatus.text = "Missing PKCE verifier. Connect again."
            return
        }

        val token = exchangeCodeForToken(code, verifier) ?: return
        saveToken(token)
        prefs.edit().remove(KEY_VERIFIER).apply()
        refreshAuthStatus()
        toast("Connected to Spotify.")
        renderItemList()
    }

    private suspend fun addItem() {
        val parsed = parseSpotifyUri(itemUriInput.text.toString().trim())
            ?: return toast("Enter a valid Spotify album/playlist URI or URL.")

        val items = getItems().toMutableList()
        if (items.any { it.uri == parsed.uri }) {
            return toast("Item is already in your list.")
        }

        val token = getUsableAccessToken() ?: return toast("Connect Spotify first or reconnect if your session expired.")
        val titled = withItemTitle(parsed, token) ?: parsed.copy(title = parsed.uri)
        items.add(titled)
        saveItems(items)
        renderItemList()
        itemUriInput.setText("")
        toast("Item added.")
    }

    private suspend fun importAlbumsFromPlaylist() {
        val token = getUsableAccessToken() ?: return toast("Connect Spotify first or reconnect if your session expired.")
        val playlist = parseSpotifyPlaylistRef(itemUriInput.text.toString().trim())
            ?: return toast("Enter a valid Spotify playlist URL, URI, or playlist ID.")

        val existing = getItems().toMutableList()
        val existingUris = existing.map { it.uri }.toMutableSet()
        val playlistResult = fetchPlaylistAlbums(playlist.id, token)
        if (playlistResult.items.isEmpty() && playlistResult.fullyLoaded.not()) {
            return toast(playlistResult.failureMessage ?: "Failed to import albums from playlist.")
        }
        val albums = playlistResult.items

        var added = 0
        for (album in albums) {
            if (existingUris.add(album.uri)) {
                existing.add(album)
                added++
            }
        }
        saveItems(existing)
        renderItemList()
        if (!playlistResult.fullyLoaded) {
            toast("Imported $added album(s), but the playlist scan was incomplete: ${playlistResult.failureMessage ?: "unknown error"}.")
            return
        }
        if (albums.isEmpty()) {
            toast("No albums were found in that playlist.")
            return
        }
        toast("Imported $added album(s) from playlist (${albums.size} unique album(s) found).")
    }

    private suspend fun startShuffleSession() {
        val token = getUsableAccessToken() ?: return toast("Connect Spotify first or reconnect if your session expired.")
        val items = getItems()
        if (items.isEmpty()) return toast("Add at least one album or playlist first.")

        session = session.copy(
            activationState = ActivationState.ACTIVE,
            queue = items.shuffled().toMutableList(),
            index = 0,
        )
        persistRuntimeState()
        renderQueue()
        renderPlaybackControls()
        playCurrentItem(token)
        startMonitorLoop()
    }

    private suspend fun reattachSession() {
        if (session.activationState != ActivationState.DETACHED) return
        if (getUsableAccessToken() == null) {
            playbackStatus.text = "Cannot reattach: Spotify session expired. Reconnect."
            toast("Reattach failed: Spotify session expired.")
            return
        }
        if (session.queue.isEmpty()) {
            stopSession("Cannot reattach: queue is empty.")
            toast("Reattach failed: queue is empty.")
            return
        }
        session = session.copy(activationState = ActivationState.ACTIVE)
        renderPlaybackControls()
        startMonitorLoop()
        playbackStatus.text = "Session reattached. Monitoring playback."
        toast("Session reattached.")
    }

    private suspend fun goToNextItem() {
        if (session.activationState != ActivationState.ACTIVE) {
            playbackStatus.text = "No active session."
            return
        }
        session = session.copy(index = session.index + 1)
        if (session.index >= session.queue.size) {
            stopSession("Finished: all selected albums/playlists were played.")
            return
        }
        persistRuntimeState()
        renderQueue()
        val token = getUsableAccessToken() ?: return stopSession("Spotify session expired. Reconnect to continue.")
        playCurrentItem(token)
    }

    private suspend fun playCurrentItem(token: String) {
        val current = session.queue.getOrNull(session.index)
            ?: return stopSession("Finished: all selected albums/playlists were played.")

        session = session.copy(currentUri = current.uri, observedCurrentContext = false)
        persistRuntimeState()
        renderPlaybackControls()
        renderQueue()

        val shuffleResponse = spotifyApi("/me/player/shuffle?state=false", "PUT", token, null)
        if (!shuffleResponse.ok) {
            playbackStatus.text = "Playback warning: failed to disable shuffle (${shuffleResponse.describeFailure()})."
        }

        val repeatResponse = spotifyApi("/me/player/repeat?state=off", "PUT", token, null)
        if (!repeatResponse.ok) {
            playbackStatus.text = "Playback warning: failed to disable repeat (${repeatResponse.describeFailure()})."
        }

        val payload = JSONObject()
            .put("context_uri", current.uri)
            .put("offset", JSONObject().put("position", 0))
            .put("position_ms", 0)

        val response = spotifyApi("/me/player/play", "PUT", token, payload.toString())
        if (!response.ok) {
            transitionDetached("Playback detached: ${response.describeFailure()}.")
            return
        }

        playbackStatus.text = formatNowPlayingStatus(current)
    }

    private suspend fun monitorPlayback() {
        if (session.activationState != ActivationState.ACTIVE || session.currentUri == null) return
        val token = getUsableAccessToken() ?: return transitionDetached("Spotify session expired. Reconnect.")

        val response = spotifyApi("/me/player", "GET", token, null)
        if (response.status == 204) return
        if (!response.ok) {
            transitionDetached("Playback monitoring paused: ${response.describeFailure()}.")
            return
        }

        val body = response.body ?: run {
            transitionDetached("Playback monitoring paused: Spotify returned an empty status payload.")
            return
        }
        val context = JSONObject(body).optJSONObject("context")
        val contextUri = if (context == null || context.isNull("uri")) null else context.optString("uri")

        if (contextUri == session.currentUri) {
            session = session.copy(observedCurrentContext = true)
            persistRuntimeState()
            return
        }

        if (!session.observedCurrentContext) return

        if (contextUri == null) {
            goToNextItem()
            return
        }

        if (contextUri != session.currentUri) {
            transitionDetached("Spotify is playing a different item. Reattach when ready.")
        }
    }

    private fun transitionDetached(message: String) {
        stopMonitorLoop()
        session = session.copy(activationState = ActivationState.DETACHED)
        persistRuntimeState()
        renderPlaybackControls()
        playbackStatus.text = message
    }

    private fun stopSession(message: String) {
        stopMonitorLoop()
        session = SessionState()
        clearRuntimeState()
        renderQueue()
        renderPlaybackControls()
        playbackStatus.text = message
    }

    private fun startMonitorLoop() {
        stopMonitorLoop()
        monitorHandler.postDelayed(monitorTask, 4000)
    }

    private fun stopMonitorLoop() {
        monitorHandler.removeCallbacks(monitorTask)
    }

    private fun removeItem(item: ShuffleItem) {
        val next = getItems().toMutableList()
        val removedIndex = next.indexOfFirst { it.uri == item.uri }
        if (removedIndex == -1) return

        next.removeAt(removedIndex)
        saveItems(next)
        renderItemList()
        showUndoBanner(item, removedIndex)
    }

    private fun showUndoBanner(item: ShuffleItem, removedIndex: Int) {
        val bannerView = layoutInflater.inflate(R.layout.undo_banner_row, undoBannerContainer, false)
        val messageView = bannerView.findViewById<TextView>(R.id.undoMessage)
        val undoButton = bannerView.findViewById<Button>(R.id.undoButton)
        val removalId = nextPendingRemovalId++
        messageView.text = "Removed ${item.title}."

        val dismissRunnable = Runnable {
            clearPendingRemoval(removalId)
        }
        val removal = PendingRemoval(
            id = removalId,
            item = item,
            index = removedIndex,
            bannerView = bannerView,
            dismissRunnable = dismissRunnable,
        )
        pendingRemovals[removalId] = removal
        undoBannerContainer.addView(bannerView, 0)
        undoButton.setOnClickListener { undoPendingRemoval(removalId) }
        monitorHandler.postDelayed(dismissRunnable, UNDO_BANNER_DURATION_MS)
    }

    private fun undoPendingRemoval(removalId: Long) {
        val removal = pendingRemovals.remove(removalId) ?: return
        monitorHandler.removeCallbacks(removal.dismissRunnable)
        undoBannerContainer.removeView(removal.bannerView)
        val currentItems = getItems().toMutableList()
        if (currentItems.any { it.uri == removal.item.uri }) {
            renderItemList()
            toast("${removal.item.title} is already in your list.")
            return
        }

        val insertIndex = removal.index.coerceIn(0, currentItems.size)
        currentItems.add(insertIndex, removal.item)
        saveItems(currentItems)
        renderItemList()
        toast("Restored ${removal.item.title}.")
    }

    private fun clearPendingRemoval(removalId: Long) {
        val removal = pendingRemovals.remove(removalId) ?: return
        undoBannerContainer.removeView(removal.bannerView)
    }

    private fun renderItemList() {
        itemAdapter.submit(getItems())
    }

    private fun renderQueue() {
        queueAdapter.submit(session.queue, session.index)
    }

    private fun renderPlaybackControls() {
        val inactive = session.activationState == ActivationState.INACTIVE
        val active = session.activationState == ActivationState.ACTIVE
        val detached = session.activationState == ActivationState.DETACHED

        startButton.isEnabled = inactive
        skipButton.isEnabled = active
        stopButton.isEnabled = !inactive
        reattachButton.visibility = if (detached) View.VISIBLE else View.GONE
        reattachButton.isEnabled = detached
    }

    private fun restoreSessionMonitoringIfNeeded() {
        if (session.activationState != ActivationState.ACTIVE) return
        if (getToken() == null) {
            transitionDetached("Spotify session expired. Reconnect.")
            return
        }
        playbackStatus.text = "Restored active session."
        startMonitorLoop()
    }

    private fun exportStorageJson() {
        val data = JSONObject().apply {
            prefs.all.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JSONObject.NULL)
                    is Set<*> -> put(key, JSONArray(value.toList()))
                    else -> put(key, value)
                }
            }
        }
        storageJsonInput.setText(data.toString(2))
        toast("Exported ${prefs.all.size} key(s) to JSON.")
    }

    private fun importStorageJson() {
        val raw = storageJsonInput.text.toString().trim()
        if (raw.isEmpty()) return toast("Paste a JSON object to import.")

        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return toast("Invalid JSON.")
        }

        val editor = prefs.edit().clear()
        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = parsed.opt(key)) {
                JSONObject.NULL, null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> {
                    if (value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                        editor.putInt(key, value.toInt())
                    } else if (value % 1.0 == 0.0 && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                        editor.putLong(key, value.toLong())
                    } else {
                        editor.putFloat(key, value.toFloat())
                    }
                }
                is JSONArray -> {
                    val items = mutableSetOf<String>()
                    for (index in 0 until value.length()) {
                        items.add(value.optString(index, ""))
                    }
                    editor.putStringSet(key, items)
                }
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()

        stopSession("Storage imported. Session reset.")
        refreshAuthStatus()
        renderItemList()
        toast("Imported local storage JSON.")
    }

    private fun getItems(): List<ShuffleItem> {
        val raw = getStringPref(KEY_ITEMS) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val type = obj.optString("type")
                    val uri = obj.optString("uri")
                    val title = obj.optString("title", uri)
                    if ((type == "album" || type == "playlist") && uri.isNotBlank()) {
                        add(ShuffleItem(type = type, uri = uri, title = title))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveItems(items: List<ShuffleItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("type", it.type).put("uri", it.uri).put("title", it.title))
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun getToken(): String? {
        val token = getStringPref(KEY_TOKEN)
        val expiry = getLongPref(KEY_TOKEN_EXPIRY, 0L)
        return if (!token.isNullOrBlank() && System.currentTimeMillis() < expiry) token else null
    }

    private suspend fun getUsableAccessToken(): String? {
        getToken()?.let { return it }
        return refreshSpotifyAccessToken()
    }

    private fun saveToken(token: TokenResponse) {
        prefs.edit()
            .putString(KEY_TOKEN, token.accessToken)
            .putString(KEY_REFRESH_TOKEN, token.refreshToken ?: getStringPref(KEY_REFRESH_TOKEN))
            .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + token.expiresIn * 1000L)
            .putString(KEY_TOKEN_SCOPE, token.scope ?: "")
            .apply()
    }

    private fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_TOKEN_SCOPE)
            .remove(KEY_VERIFIER)
            .apply()
    }

    private suspend fun exchangeCodeForToken(code: String, verifier: String): TokenResponse? {
        val params = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to SPOTIFY_APP_ID,
            "code_verifier" to verifier,
        )
        val response = formPost("https://accounts.spotify.com/api/token", params)
        if (!response.ok || response.body == null) {
            authStatus.text = "Spotify token exchange failed: ${response.describeFailure()}."
            return null
        }
        return parseTokenResponse(response.body) ?: run {
            authStatus.text = "Spotify token exchange failed: invalid token response."
            null
        }
    }

    private suspend fun refreshSpotifyAccessToken(): String? {
        val refreshToken = getStringPref(KEY_REFRESH_TOKEN) ?: return null
        val params = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to SPOTIFY_APP_ID,
        )
        val response = formPost("https://accounts.spotify.com/api/token", params)
        if (!response.ok || response.body == null) {
            authStatus.text = "Spotify token refresh failed: ${response.describeFailure()}."
            return null
        }
        val token = parseTokenResponse(response.body) ?: run {
            authStatus.text = "Spotify token refresh failed: invalid token response."
            return null
        }
        saveToken(token)
        return token.accessToken
    }

    private suspend fun withItemTitle(item: ShuffleItem, token: String): ShuffleItem? {
        val id = spotifyIdFromUri(item.uri) ?: return null
        val path = if (item.type == "album") "/albums/$id" else "/playlists/$id"
        val response = spotifyApi(path, "GET", token, null)
        if (!response.ok || response.body == null) return null
        val title = JSONObject(response.body).optString("name", "").trim()
        if (title.isBlank()) return null
        return item.copy(title = title)
    }

    private suspend fun fetchPlaylistAlbums(playlistId: String, token: String): PlaylistAlbumImportResult {
        val byUri = linkedMapOf<String, ShuffleItem>()
        var offset = 0
        while (true) {
            val path = "/playlists/$playlistId/items?limit=50&offset=$offset&additional_types=track&market=from_token"
            val response = spotifyApi(path, "GET", token, null)
            if (!response.ok || response.body == null) {
                return PlaylistAlbumImportResult(
                    items = byUri.values.toList(),
                    fullyLoaded = false,
                    failureMessage = response.describeFailure(),
                )
            }
            val body = JSONObject(response.body)
            val items = body.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val album = entry.optJSONObject("track")?.optJSONObject("album") ?: continue
                val albumUri = album.optString("uri")
                if (albumUri.isBlank()) continue
                val name = album.optString("name", albumUri)
                byUri.putIfAbsent(albumUri, ShuffleItem(type = "album", uri = albumUri, title = name))
            }
            if (body.isNull("next") || body.optString("next").isBlank()) break
            offset += 50
        }
        return PlaylistAlbumImportResult(
            items = byUri.values.toList(),
            fullyLoaded = true,
            failureMessage = null,
        )
    }

    private suspend fun spotifyApi(path: String, method: String, token: String, body: String?): HttpResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.spotify.com/v1$path")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    doInput = true
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val payload = stream?.use { BufferedReader(InputStreamReader(it)).readText() }
                HttpResult(status = status, body = payload)
            } catch (e: Exception) {
                HttpResult(status = -1, body = null, failureReason = networkFailureReason(e))
            }
        }
    }

    private suspend fun formPost(url: String, form: Map<String, String>): HttpResult {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = form.entries.joinToString("&") {
                    "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                }
                conn.outputStream.use { it.write(encoded.toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val payload = stream?.use { BufferedReader(InputStreamReader(it)).readText() }
                HttpResult(status = status, body = payload)
            } catch (e: Exception) {
                HttpResult(status = -1, body = null, failureReason = networkFailureReason(e))
            }
        }
    }

    private fun restoreRuntimeState() {
        val raw = getStringPref(KEY_RUNTIME) ?: return
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_RUNTIME).apply()
            return
        }

        val queueJson = parsed.optJSONArray("queue") ?: JSONArray()
        val queue = mutableListOf<ShuffleItem>()
        for (i in 0 until queueJson.length()) {
            val obj = queueJson.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            val uri = obj.optString("uri")
            val title = obj.optString("title", uri)
            if ((type == "album" || type == "playlist") && uri.isNotBlank()) {
                queue.add(ShuffleItem(type, uri, title))
            }
        }

        val state = when (parsed.optString("activationState")) {
            "active" -> ActivationState.ACTIVE
            "detached" -> ActivationState.DETACHED
            else -> ActivationState.INACTIVE
        }

        session = SessionState(
            activationState = if (queue.isEmpty()) ActivationState.INACTIVE else state,
            queue = queue,
            index = min(parsed.optInt("index", 0), maxOf(queue.size - 1, 0)),
            currentUri = if (parsed.isNull("currentUri")) null else parsed.optString("currentUri"),
            observedCurrentContext = parsed.optBoolean("observedCurrentContext", false),
        )
    }

    private fun persistRuntimeState() {
        val queue = JSONArray().apply {
            session.queue.forEach {
                put(JSONObject().put("type", it.type).put("uri", it.uri).put("title", it.title))
            }
        }
        val data = JSONObject()
            .put("activationState", session.activationState.value)
            .put("queue", queue)
            .put("index", session.index)
            .put("currentUri", session.currentUri)
            .put("observedCurrentContext", session.observedCurrentContext)
        prefs.edit().putString(KEY_RUNTIME, data.toString()).apply()
    }

    private fun clearRuntimeState() {
        prefs.edit().remove(KEY_RUNTIME).apply()
    }

    private fun formatNowPlayingStatus(item: ShuffleItem): String {
        return "Now playing ${item.type} ${session.index + 1} of ${session.queue.size}: ${item.title}"
    }

    private fun parseSpotifyUri(raw: String): ShuffleItem? {
        if (raw.isBlank()) return null
        val uriRegex = Regex("^spotify:(album|playlist):([a-zA-Z0-9]+)$")
        uriRegex.matchEntire(raw)?.let {
            val type = it.groupValues[1]
            return ShuffleItem(type = type, uri = raw, title = "")
        }

        val url = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (!(url.host ?: "").contains("spotify.com")) return null
        val segments = url.pathSegments
        if (segments.size < 2) return null
        val type = segments[0]
        val id = segments[1]
        if ((type == "album" || type == "playlist") && id.matches(Regex("^[a-zA-Z0-9]+$"))) {
            return ShuffleItem(type = type, uri = "spotify:$type:$id", title = "")
        }
        return null
    }

    private fun parseSpotifyPlaylistRef(raw: String): PlaylistRef? {
        val uriItem = parseSpotifyUri(raw)
        if (uriItem?.type == "playlist") {
            val id = spotifyIdFromUri(uriItem.uri) ?: return null
            return PlaylistRef(id = id, uri = uriItem.uri)
        }

        return if (raw.matches(Regex("^[a-zA-Z0-9]+$"))) {
            PlaylistRef(id = raw, uri = "spotify:playlist:$raw")
        } else {
            null
        }
    }

    private fun spotifyIdFromUri(uri: String): String? {
        return Regex("^spotify:(album|playlist):([a-zA-Z0-9]+)$").matchEntire(uri)?.groupValues?.get(2)
    }

    private fun codeChallengeFromVerifier(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rng = SecureRandom()
        return buildString {
            repeat(length) {
                append(chars[rng.nextInt(chars.length)])
            }
        }
    }

    private fun parseTokenResponse(raw: String): TokenResponse? {
        return runCatching {
            val json = JSONObject(raw)
            TokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { null },
                expiresIn = json.getLong("expires_in"),
                scope = json.optString("scope").ifBlank { null },
            )
        }.getOrNull()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getStringPref(key: String): String? {
        return when (val value = prefs.all[key]) {
            null -> null
            is String -> value
            else -> value.toString()
        }
    }

    private fun getLongPref(key: String, defaultValue: Long): Long {
        return when (val value = prefs.all[key]) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            is Double -> value.toLong()
            else -> defaultValue
        }
    }

    companion object {
        private const val SPOTIFY_APP_ID = "5082b1452bc24cc3a0955f2d1c4e5560"
        private const val REDIRECT_URI = "shufflebyalbum://callback"

        private val SCOPES = listOf(
            "user-modify-playback-state",
            "user-read-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative",
        )

        private const val KEY_VERIFIER = "spotifyShuffler.pkceVerifier"
        private const val KEY_TOKEN = "spotifyShuffler.token"
        private const val KEY_REFRESH_TOKEN = "spotifyShuffler.refreshToken"
        private const val KEY_TOKEN_EXPIRY = "spotifyShuffler.tokenExpiry"
        private const val KEY_TOKEN_SCOPE = "spotifyShuffler.tokenScope"
        private const val KEY_ITEMS = "spotifyShuffler.items"
        private const val KEY_RUNTIME = "spotifyShuffler.runtime"
        private const val UNDO_BANNER_DURATION_MS = 5_000L
    }
}

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val scope: String?,
)

data class HttpResult(
    val status: Int,
    val body: String?,
    val failureReason: String? = null,
) {
    val ok: Boolean get() = status in 200..299
}

data class PlaylistRef(
    val id: String,
    val uri: String,
)

data class ShuffleItem(
    val type: String,
    val uri: String,
    val title: String,
)

data class PendingRemoval(
    val id: Long,
    val item: ShuffleItem,
    val index: Int,
    val bannerView: View,
    val dismissRunnable: Runnable,
)



private data class PlaylistAlbumImportResult(
    val items: List<ShuffleItem>,
    val fullyLoaded: Boolean,
    val failureMessage: String?,
)

private fun HttpResult.describeFailure(): String {
    failureReason?.let { return it }
    val statusPart = if (status >= 0) "status $status" else "request failed"
    val detail = runCatching {
        if (body.isNullOrBlank()) return@runCatching null
        val json = JSONObject(body)
        val errorObject = json.optJSONObject("error")
        when {
            errorObject != null -> errorObject.optString("message").ifBlank { null }
            else -> json.optString("error_description").ifBlank {
                json.optString("message").ifBlank { null }
            }
        }
    }.getOrNull()

    return if (detail.isNullOrBlank()) statusPart else "$statusPart: $detail"
}

private fun networkFailureReason(error: Exception): String {
    return when (error) {
        is UnknownHostException -> "network unavailable"
        else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "network error"
    }
}

enum class ActivationState(val value: String) {
    INACTIVE("inactive"),
    ACTIVE("active"),
    DETACHED("detached"),
}

data class SessionState(
    val activationState: ActivationState = ActivationState.INACTIVE,
    val queue: MutableList<ShuffleItem> = mutableListOf(),
    val index: Int = 0,
    val currentUri: String? = null,
    val observedCurrentContext: Boolean = false,
)

private class ItemAdapter(
    private val onRemove: (ShuffleItem) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {
    private val items = mutableListOf<ShuffleItem>()

    fun submit(next: List<ShuffleItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return ItemViewHolder(view, onRemove)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ItemViewHolder(
        itemView: View,
        private val onRemove: (ShuffleItem) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.title)
        private val removeButton: Button = itemView.findViewById(R.id.removeButton)

        fun bind(item: ShuffleItem) {
            title.text = item.title.ifBlank { item.uri }
            removeButton.setOnClickListener { onRemove(item) }
        }
    }
}

private class QueueAdapter : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {
    private val items = mutableListOf<String>()

    fun submit(queue: List<ShuffleItem>, currentIndex: Int) {
        items.clear()
        queue.forEachIndexed { index, item ->
            val marker = if (index == currentIndex) "▶" else "•"
            items.add("$marker ${index + 1}. ${item.title}")
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.queue_row, parent, false)
        return QueueViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.title.text = items[position]
    }

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.queueTitle)
    }
}
