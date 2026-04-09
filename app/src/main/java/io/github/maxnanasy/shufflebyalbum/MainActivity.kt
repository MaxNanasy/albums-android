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
import org.json.JSONTokener
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

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private lateinit var authStatus: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var itemUriInput: EditText
    private lateinit var storageJsonInput: EditText
    private lateinit var undoBannerContainer: LinearLayout

    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
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
    private val errorToastCooldowns = mutableMapOf<String, Long>()
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

        appScope.launch {
            ensureUsableStartupAuth(intent?.data)
            renderItemList()
            renderQueue()
            renderPlaybackControls()
            ensureStoredItemTitles()
            restoreSessionMonitoringIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appScope.launch {
            processAuthRedirect(intent.data)
        }
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

        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
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
        connectButton.setOnClickListener { startConnect() }
        disconnectButton.setOnClickListener {
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
        val token = getToken()
        if (token == null) {
            authStatus.text = "Not connected."
            return
        }
        val grantedScopes = getGrantedScopes()
        val hasPlaylistScopes = grantedScopes.contains("playlist-read-private") &&
            grantedScopes.contains("playlist-read-collaborative")
        authStatus.text = if (hasPlaylistScopes) {
            "Connected."
        } else {
            "Connected, but token is missing playlist import scopes. Disconnect and reconnect."
        }
    }

    private fun startConnect() {
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

    private suspend fun ensureUsableStartupAuth(uri: Uri?) {
        if (processAuthRedirect(uri)) {
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

        val token = refreshSpotifyAccessToken()
        if (token == null) {
            return
        }
        refreshAuthStatus()
    }

    private suspend fun processAuthRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "shufflebyalbum") return false
        val error = uri.getQueryParameter("error")
        if (error != null) {
            authStatus.text = "Spotify authorization error: $error"
            prefs.edit().remove(KEY_VERIFIER).apply()
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            authStatus.text = "Spotify authorization failed: missing authorization code."
            reportError(toastMessage = "Spotify login did not return an authorization code.")
            prefs.edit().remove(KEY_VERIFIER).apply()
            return true
        }
        val verifier = getStringPref(KEY_VERIFIER)
        if (verifier.isNullOrBlank()) {
            authStatus.text = "Missing PKCE verifier. Try connecting again."
            return true
        }

        val token = exchangeCodeForToken(code, verifier) ?: run {
            prefs.edit().remove(KEY_VERIFIER).apply()
            return true
        }
        saveToken(token)
        prefs.edit().remove(KEY_VERIFIER).apply()
        refreshAuthStatus()
        renderItemList()
        return true
    }

    private suspend fun addItem() {
        val parsed = parseSpotifyUri(itemUriInput.text.toString().trim())
            ?: return toast("Enter a valid Spotify album/playlist URI or URL.")

        val items = getItems().toMutableList()
        if (items.any { it.uri == parsed.uri }) {
            return toast("Item is already in your list.")
        }

        val token = getUsableAccessToken() ?: return toast("Connect Spotify first so the app can load item titles.")
        val titled = withItemTitle(parsed, token)
            ?: return toast("Unable to load title for that item. Please try another URI.")
        items.add(titled)
        saveItems(items)
        renderItemList()
        itemUriInput.setText("")
        toast("Item added.")
    }

    private suspend fun importAlbumsFromPlaylist() {
        val token = getUsableAccessToken() ?: return toast("Connect Spotify first so the app can import albums.")
        val playlist = parseSpotifyPlaylistRef(itemUriInput.text.toString().trim())
            ?: return toast("Enter a valid Spotify playlist URL, URI, or playlist ID.")
        toast("Importing albums from playlist...")

        val existing = getItems().toMutableList()
        val existingUris = existing.map { it.uri }.toMutableSet()
        val playlistResult = fetchPlaylistAlbums(playlist.id, token)
        if (!playlistResult.fullyLoaded) {
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
        toast("Imported $added album(s) from playlist (${albums.size} unique album(s) found).")
    }

    private suspend fun startShuffleSession() {
        val token = getUsableAccessToken() ?: return toast("Connect Spotify first.")
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
        playbackStatus.text = "Session started with ${session.queue.size} item(s)."
        when (playCurrentItem(token)) {
            PlaybackStartResult.STARTED -> transitionActive(startMonitoring = true)
            PlaybackStartResult.DETACHED,
            PlaybackStartResult.STOPPED,
            -> Unit
        }
    }

    private suspend fun reattachSession() {
        if (session.activationState != ActivationState.DETACHED) return
        val token = getUsableAccessToken()
        if (token == null) {
            playbackStatus.text = "Spotify session expired. Please reconnect."
            toast("Spotify session expired. Please reconnect.")
            return
        }
        if (session.queue.isEmpty()) {
            stopSession("No queued item available to reattach.")
            toast("No queued item available to reattach.")
            return
        }

        val snapshotResult = fetchCurrentPlaybackSnapshot(token)
        if (!snapshotResult.ok) {
            val failure = spotifyFailureMessage(snapshotResult.status, snapshotResult.failureReason)
            transitionDetached("Cannot reattach: $failure.")
            reportError(toastMessage = "Reattach failed: $failure.")
            return
        }

        val current = session.queue.getOrNull(session.index) ?: run {
            stopSession("No queued item available to reattach.")
            toast("No queued item available to reattach.")
            return
        }
        val expectedUri = session.currentUri ?: current.uri
        val snapshot = snapshotResult.snapshot

        if (snapshot?.contextUri == expectedUri) {
            session = session.copy(
                activationState = ActivationState.ACTIVE,
                currentUri = expectedUri,
                observedCurrentContext = true,
            )
            persistRuntimeState()
            renderPlaybackControls()
            playbackStatus.text = formatNowPlayingStatus(current)
            startMonitorLoop()
            toast("Session reattached.")
        } else {
            when (playCurrentItem(token)) {
                PlaybackStartResult.STARTED -> {
                    transitionActive(startMonitoring = true)
                    toast("Session reattached.")
                }
                PlaybackStartResult.DETACHED,
                PlaybackStartResult.STOPPED,
                -> Unit
            }
        }
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

    private suspend fun playCurrentItem(token: String): PlaybackStartResult {
        val current = session.queue.getOrNull(session.index)
            ?: run {
                stopSession("Finished: all selected albums/playlists were played.")
                return PlaybackStartResult.STOPPED
            }

        session = session.copy(
            currentUri = current.uri,
            observedCurrentContext = false,
        )
        persistRuntimeState()
        renderPlaybackControls()
        renderQueue()

        val preflightResult = runPlaybackPreflight(token)
        if (!preflightResult.ok) {
            if (preflightResult.detach) {
                transitionDetached(preflightResult.message)
                reportError(toastMessage = preflightResult.message)
                return PlaybackStartResult.DETACHED
            }
            stopSession(preflightResult.message)
            reportError(toastMessage = preflightResult.message)
            return PlaybackStartResult.STOPPED
        }

        val payload = JSONObject()
            .put("context_uri", current.uri)
            .put("offset", JSONObject().put("position", 0))
            .put("position_ms", 0)

        val response = spotifyApi("/me/player/play", "PUT", token, payload.toString())
        if (!response.ok) {
            val failure = spotifyFailureMessage(response.status, response.failureReason)
            transitionDetached("Playback detached: $failure.")
            if (isUnrecoverableSpotifyStatus(response.status)) {
                reportError(toastMessage = "Playback detached: $failure.")
            }
            return PlaybackStartResult.DETACHED
        }

        playbackStatus.text = formatNowPlayingStatus(current)
        return PlaybackStartResult.STARTED
    }

    private suspend fun runPlaybackPreflight(token: String): PlaybackPreflightResult {
        val steps = listOf(
            PlaybackPreflightStep(
                path = "/me/player/shuffle?state=false",
                action = "disable shuffle",
            ),
            PlaybackPreflightStep(
                path = "/me/player/repeat?state=off",
                action = "disable repeat",
            ),
        )

        for (step in steps) {
            val response = spotifyApi(step.path, "PUT", token, null)
            if (response.ok) continue

            val failure = spotifyFailureMessage(response.status, response.failureReason)
            val message = "Playback preflight failed: could not ${step.action} ($failure)."
            if (isUnrecoverableSpotifyStatus(response.status)) {
                return PlaybackPreflightResult(
                    ok = false,
                    detach = true,
                    message = "Playback detached: $message",
                )
            }
            return PlaybackPreflightResult(
                ok = false,
                detach = false,
                message = "Playback stopped: $message",
            )
        }
        return PlaybackPreflightResult(ok = true, detach = false, message = "")
    }

    private suspend fun monitorPlayback() {
        if (session.activationState != ActivationState.ACTIVE || session.currentUri == null) return
        val token = getUsableAccessToken() ?: return transitionDetached("Spotify session expired. Please reconnect.")

        val snapshotResult = fetchCurrentPlaybackSnapshot(token)
        if (snapshotResult.status == 204) return
        if (!snapshotResult.ok) {
            val failure = spotifyFailureMessage(snapshotResult.status, snapshotResult.failureReason)
            if (isUnrecoverableMonitorStatus(snapshotResult.status)) {
                transitionDetached("Playback monitoring paused: $failure.")
                reportError(
                    toastMessage = "Playback monitoring paused: $failure.",
                    cooldownKey = "monitor-failure-detached",
                )
            } else {
                playbackStatus.text = "Unable to check playback state right now."
                reportError(
                    toastMessage = "Playback monitor encountered an error.",
                    cooldownKey = "monitor-failure-recoverable",
                )
            }
            return
        }
        val snapshot = snapshotResult.snapshot ?: run {
            playbackStatus.text = "Unable to check playback state right now."
            reportError(
                toastMessage = "Playback monitor encountered an error.",
                cooldownKey = "monitor-failure-recoverable",
            )
            return
        }
        val contextUri = snapshot.contextUri

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
            transitionDetached("Spotify is playing a different album/playlist than this app expects. Reattach to resume.")
        }
    }

    private fun transitionDetached(message: String) {
        stopMonitorLoop()
        session = session.copy(activationState = ActivationState.DETACHED)
        persistRuntimeState()
        renderPlaybackControls()
        playbackStatus.text = message
    }

    private fun transitionActive(startMonitoring: Boolean) {
        session = session.copy(activationState = ActivationState.ACTIVE)
        persistRuntimeState()
        renderPlaybackControls()
        if (startMonitoring) {
            startMonitorLoop()
        } else {
            stopMonitorLoop()
        }
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
        messageView.text = "Removed ${quotedTitle(item.title)}."

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
            toast("Item is already in your list.")
            return
        }

        val insertIndex = removal.index.coerceIn(0, currentItems.size)
        currentItems.add(insertIndex, removal.item)
        saveItems(currentItems)
        renderItemList()
        toast("Restored ${quotedTitle(removal.item.title)}.")
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

    private suspend fun restoreSessionMonitoringIfNeeded() {
        val restoredState = session.activationState
        if (restoredState == ActivationState.INACTIVE) return

        val current = session.queue.getOrNull(session.index)
        if (current == null) {
            session = SessionState()
            clearRuntimeState()
            renderQueue()
            renderPlaybackControls()
            playbackStatus.text = "No active session."
            return
        }

        session = session.copy(currentUri = current.uri)
        persistRuntimeState()
        renderQueue()
        renderPlaybackControls()
        playbackStatus.text = formatNowPlayingStatus(current)

        if (restoredState == ActivationState.ACTIVE) {
            val token = getUsableAccessToken()
            if (token == null) {
                handleExpiredApiSession()
                return
            }
            startMonitorLoop()
        } else {
            stopMonitorLoop()
        }
    }

    private suspend fun fetchCurrentPlaybackSnapshot(token: String): PlaybackSnapshotResult {
        val response = spotifyApi("/me/player", "GET", token, null)
        if (!response.ok || response.status == 204) return PlaybackSnapshotResult(response.status, null, response.ok, response.failureReason, response.body)
        val body = response.body ?: return PlaybackSnapshotResult(
            status = response.status,
            snapshot = null,
            ok = true,
            failureReason = response.failureReason,
            body = null,
        )
        val json = JSONObject(body)
        val context = json.optJSONObject("context")
        val contextUri = if (context == null || context.isNull("uri")) null else context.optString("uri")
        return PlaybackSnapshotResult(
            status = response.status,
            snapshot = PlaybackSnapshot(contextUri = contextUri),
            ok = true,
            failureReason = response.failureReason,
            body = response.body,
        )
    }

    private fun exportStorageJson() {
        val exportItems = runCatching { getStoredItemArrayForExport() }.getOrElse {
            storageJsonInput.setText("")
            toast("Unable to export saved items because stored data is invalid JSON.")
            return
        }
        val data = JSONObject().put(KEY_ITEMS, exportItems)
        storageJsonInput.setText(data.toString(2))
        toast("Exported saved items to JSON.")
    }

    private fun importStorageJson() {
        val raw = storageJsonInput.text.toString().trim()
        if (raw.isEmpty()) return toast("Paste a JSON object to import.")

        val parsed = try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            return toast("Invalid JSON. Please provide a valid JSON object.")
        }
        if (parsed !is JSONObject) return toast("Import JSON must be an object of key/value pairs.")

        val importedItemsArray = parsed.optJSONArray(KEY_ITEMS)
            ?: return toast("Import JSON must include a valid shuffle-by-album.items array.")

        val importedItems = buildList {
            for (index in 0 until importedItemsArray.length()) {
                val obj = importedItemsArray.optJSONObject(index) ?: continue
                val type = obj.optString("type")
                val uri = obj.opt("uri")
                if ((type != "album" && type != "playlist") || uri !is String) continue
                val title = obj.opt("title")
                add(
                    ShuffleItem(
                        type = type,
                        uri = uri,
                        title = if (title is String) title else uri,
                    ),
                )
            }
        }
        saveItems(importedItems)

        stopSession("Data imported. Session reset.")
        refreshAuthStatus()
        renderItemList()
        toast("Imported saved items.")
    }

    private fun getStoredItemArrayForExport(): JSONArray {
        val raw = getStringPref(KEY_ITEMS) ?: return JSONArray()
        val parsed = JSONTokener(raw).nextValue()
        if (parsed !is JSONArray) throw IllegalArgumentException("Expected stored items array")
        return parsed
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
        refreshAuthStatus()
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
            reportError(
                statusView = authStatus,
                statusMessage = "Spotify token exchange failed: ${spotifyFailureMessage(response.status, response.failureReason)}.",
            )
            return null
        }
        return parseTokenResponse(response.body) ?: run {
            reportError(
                statusView = authStatus,
                statusMessage = "Spotify token exchange failed: invalid token response.",
            )
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
            val refreshStatusMessage = if (response.failureReason != null) {
                "Network issue refreshing Spotify session. Please reconnect if this continues."
            } else {
                "Unable to validate Spotify session. Please reconnect."
            }
            reportError(
                statusView = authStatus,
                statusMessage = refreshStatusMessage,
            )
            return null
        }
        val token = parseTokenResponse(response.body) ?: run {
            reportError(
                statusView = authStatus,
                statusMessage = "Unable to validate Spotify session. Please reconnect.",
            )
            return null
        }
        saveToken(token)
        refreshAuthStatus()
        return token.accessToken
    }

    private fun getGrantedScopes(): Set<String> {
        return getStringPref(KEY_TOKEN_SCOPE)
            .orEmpty()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
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
                    items = emptyList(),
                    fullyLoaded = false,
                    failureMessage = response.describePlaylistImportFailure(),
                )
            }
            val body = JSONObject(response.body)
            val items = body.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val item = entry.optJSONObject("item") ?: continue
                val album = item.optJSONObject("album") ?: continue
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
        val firstAttempt = runSpotifyApiRequest(path, method, token, body)
        if (firstAttempt.status != 401) return firstAttempt

        val refreshedToken = refreshSpotifyAccessToken() ?: run {
            handleExpiredApiSession()
            return firstAttempt
        }
        val replayed = runSpotifyApiRequest(path, method, refreshedToken, body)
        if (replayed.status == 401) {
            handleExpiredApiSession()
        }
        return replayed
    }

    private suspend fun runSpotifyApiRequest(path: String, method: String, token: String, body: String?): HttpResult {
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

    private fun handleExpiredApiSession() {
        clearAuth()
        refreshAuthStatus()
        val message = "Spotify session expired. Please reconnect."
        if (session.activationState == ActivationState.ACTIVE || session.activationState == ActivationState.DETACHED) {
            transitionDetached(message)
        } else {
            playbackStatus.text = message
        }
        reportError(
            toastMessage = "Spotify session expired. Please reconnect.",
            cooldownKey = "auth-expired",
        )
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

    private suspend fun ensureStoredItemTitles() {
        val existingItems = getItems()
        if (existingItems.isEmpty()) return
        val token = getUsableAccessToken() ?: return

        var updated = false
        val reconciled = existingItems.map { item ->
            val needsTitle = item.title.isBlank() || item.title == item.uri
            if (!needsTitle) return@map item
            val titled = withItemTitle(item, token) ?: return@map item
            updated = true
            titled
        }

        if (!updated) return
        saveItems(reconciled)
        renderItemList()
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

    private fun quotedTitle(title: String): String {
        return "“$title”"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun reportError(
        statusView: TextView? = null,
        statusMessage: String? = null,
        toastMessage: String? = null,
        cooldownKey: String? = null,
    ) {
        if (!statusMessage.isNullOrBlank() && statusView != null) {
            statusView.text = statusMessage
        }
        if (toastMessage.isNullOrBlank()) return
        if (cooldownKey == null) {
            toast(toastMessage)
            return
        }

        val now = System.currentTimeMillis()
        val nextAllowed = errorToastCooldowns[cooldownKey] ?: 0L
        if (now < nextAllowed) return
        errorToastCooldowns[cooldownKey] = now + ERROR_TOAST_COOLDOWN_MS
        toast(toastMessage)
    }

    private fun spotifyFailureMessage(status: Int, failureReason: String?): String {
        failureReason?.let { return normalizeNetworkError(it) }
        return spotifyStatusMessage(status)
    }

    private fun spotifyStatusMessage(status: Int): String {
        return when (status) {
            400 -> "Network error while contacting Spotify. Please try again."
            401 -> "Spotify session expired. Please reconnect."
            403 -> "Spotify permissions are missing. Disconnect and reconnect."
            404 -> "Requested Spotify item or playback device was not found."
            429 -> "Spotify rate limit reached. Please wait a moment and retry."
            in 500..599 -> "Spotify is temporarily unavailable. Please try again shortly."
            else -> "Network error while contacting Spotify. Please try again."
        }
    }

    private fun isUnrecoverableSpotifyStatus(status: Int): Boolean {
        return status in setOf(400, 401, 403, 404)
    }

    private fun isUnrecoverableMonitorStatus(status: Int): Boolean {
        return status in setOf(401, 403, 404)
    }

    private fun normalizeNetworkError(reason: String): String {
        return normalizeSpotifyNetworkError(reason)
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
        private const val PREFS_NAME = "shuffle-by-album"

        private val SCOPES = listOf(
            "user-modify-playback-state",
            "user-read-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative",
        )

        private const val KEY_VERIFIER = "shuffle-by-album.pkceVerifier"
        private const val KEY_TOKEN = "shuffle-by-album.token"
        private const val KEY_REFRESH_TOKEN = "shuffle-by-album.refreshToken"
        private const val KEY_TOKEN_EXPIRY = "shuffle-by-album.tokenExpiry"
        private const val KEY_TOKEN_SCOPE = "shuffle-by-album.tokenScope"
        private const val KEY_ITEMS = "shuffle-by-album.items"
        private const val KEY_RUNTIME = "shuffle-by-album.runtime"
        private const val UNDO_BANNER_DURATION_MS = 5_000L
        private const val ERROR_TOAST_COOLDOWN_MS = 45_000L
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

private data class PlaybackSnapshot(
    val contextUri: String?,
)

private data class PlaybackSnapshotResult(
    val status: Int,
    val snapshot: PlaybackSnapshot?,
    val ok: Boolean,
    val failureReason: String?,
    val body: String?,
)

private data class PlaybackPreflightStep(
    val path: String,
    val action: String,
)

private data class PlaybackPreflightResult(
    val ok: Boolean,
    val detach: Boolean,
    val message: String,
)

private fun HttpResult.describeFailure(): String {
    failureReason?.let { return normalizeSpotifyNetworkError(it) }
    val statusPart = if (status >= 0) "status $status" else "request failed"
    val detail = extractErrorDetail(body)

    return if (detail.isNullOrBlank()) statusPart else "$statusPart: $detail"
}

private fun HttpResult.describePlaylistImportFailure(): String {
    if (status < 0) {
        return normalizeSpotifyNetworkError(failureReason ?: "network error")
    }
    val details = extractErrorDetail(body)
    return if (details.isNullOrBlank()) {
        "Unable to import albums from that playlist (status $status). Please try again."
    } else {
        "Unable to import albums from that playlist (status $status). $details"
    }
}

private fun PlaybackSnapshotResult.describeFailure(): String {
    failureReason?.let { return normalizeSpotifyNetworkError(it) }
    val statusPart = if (status >= 0) "status $status" else "request failed"
    val detail = extractErrorDetail(body)

    return if (detail.isNullOrBlank()) statusPart else "$statusPart: $detail"
}

private fun extractErrorDetail(body: String?): String? {
    return runCatching {
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
}

private fun normalizeSpotifyNetworkError(reason: String): String {
    return when (reason.lowercase()) {
        "network unavailable", "network error" -> "Network error while contacting Spotify. Please try again."
        else -> "Network error while contacting Spotify. Please try again."
    }
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

private enum class PlaybackStartResult {
    STARTED,
    DETACHED,
    STOPPED,
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
