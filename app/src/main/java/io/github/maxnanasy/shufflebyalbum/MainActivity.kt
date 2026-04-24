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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.spotify.android.appremote.api.AppRemote
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private lateinit var removedItemsSection: LinearLayout
    private lateinit var removedItemsCount: TextView
    private lateinit var purgeRemovedItemsButton: Button
    private lateinit var removedItemsRecycler: RecyclerView

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

    private val itemAdapter = ItemActionAdapter(actionLabel = "Remove", onAction = ::removeItem)
    private val removedItemsAdapter = ItemActionAdapter(actionLabel = "Restore", onAction = ::restoreRemovedItem)
    private val queueAdapter = QueueAdapter()
    private val removedItems = mutableListOf<ShuffleItem>()
    private val errorSnackbarCooldowns = mutableMapOf<String, Long>()

    private var session = SessionState()
    private var spotifyAppRemote: AppRemote? = null
    private var connectingAppRemote = false

    private var undoSnackbar: Snackbar? = null
    private val playbackMonitorLoop by lazy { playbackMonitorLoopFactory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupLists()
        wireEvents()

        removedItems.clear()
        removedItems.addAll(getRemovedItems())
        restoreRuntimeState()

        appScope.launch {
            handleIncomingIntent(intent)
            ensureUsableStartupAuth()
            renderItemList()
            renderRemovedItems()
            renderQueue()
            renderPlaybackControls()
            ensureStoredItemTitles()
            restoreSessionMonitoringIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appScope.launch {
            handleIncomingIntent(intent)
        }
    }

    override fun onStop() {
        disconnectAppRemote()
        super.onStop()
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
        removedItemsSection = findViewById(R.id.removedItemsSection)
        removedItemsCount = findViewById(R.id.removedItemsCount)
        purgeRemovedItemsButton = findViewById(R.id.purgeRemovedItemsButton)
        removedItemsRecycler = findViewById(R.id.removedItemsRecycler)

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
        removedItemsRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = removedItemsAdapter
        }
    }

    private fun wireEvents() {
        connectButton.setOnClickListener { startConnect() }
        disconnectButton.setOnClickListener {
            clearAuth()
            refreshAuthStatus()
            snackbar("Disconnected from Spotify")
        }
        addButton.setOnClickListener { launchUiAction("Add item") { addItem() } }
        itemUriInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addButton.performClick()
                true
            } else {
                false
            }
        }
        importPlaylistButton.setOnClickListener {
            launchUiAction("Import playlist albums") { importAlbumsFromPlaylist() }
        }
        startButton.setOnClickListener { launchUiAction("Start playback") { startShuffleSession() } }
        reattachButton.setOnClickListener { launchUiAction("Reattach session") { reattachSession() } }
        skipButton.setOnClickListener { launchUiAction("Next item") { goToNextItem() } }
        stopButton.setOnClickListener { stopSession("Session stopped") }
        exportStorageButton.setOnClickListener { exportStorageJson() }
        importStorageButton.setOnClickListener { importStorageJson() }
        purgeRemovedItemsButton.setOnClickListener { showPurgeRemovedItemsDialog() }
    }

    private fun refreshAuthStatus() {
        val token = getToken()
        if (token == null) {
            authStatus.text = "Not connected"
            return
        }
        val grantedScopes = getGrantedScopes()
        val hasPlaylistScopes = grantedScopes.contains("playlist-read-private") &&
            grantedScopes.contains("playlist-read-collaborative")
        authStatus.text = if (hasPlaylistScopes) {
            "Connected"
        } else {
            "Connected, but token is missing playlist import scopes; disconnect and reconnect"
        }
    }

    private fun launchUiAction(actionLabel: String, block: suspend () -> Unit) {
        appScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                handleUiActionFailure(actionLabel, error)
            }
        }
    }

    private fun handleUiActionFailure(actionLabel: String, error: Throwable) {
        val message = "$actionLabel failed: ${describeAppRemoteError(error)}"
        playbackStatus.text = message
        snackbar(message)
    }

    private fun handleAppRemoteConnectionFailure(error: Throwable) {
        val message = "Spotify app connection failed: ${describeAppRemoteError(error)}"
        runOnUiThread {
            playbackStatus.text = message
        }
    }

    private fun describeAppRemoteError(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "UnknownError" }
        val detail = sequenceOf(error.message?.trim(), error.cause?.message?.trim())
            .firstOrNull { !it.isNullOrBlank() }
        return if (detail == null || detail == type) type else "$type: $detail"
    }

    private fun startConnect() {
        val verifier = randomString(64)
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val challenge = codeChallengeFromVerifier(verifier)

        val authUri = spotifyAccountsUri("/authorize").buildUpon()
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
            snackbar("Unable to open browser for Spotify login")
        }
    }

    private suspend fun connectAppRemote(): AppRemote {
        spotifyAppRemote?.let { return it }
        if (connectingAppRemote) {
            throw IllegalStateException("Spotify app connection is already in progress")
        }
        if (!spotifyAppRemoteService.isSpotifyInstalled(this)) {
            throw IllegalStateException("Spotify app is not installed on this device")
        }

        connectingAppRemote = true

        val params = ConnectionParams.Builder(SPOTIFY_APP_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()

        return suspendCancellableCoroutine { continuation ->
            try {
                spotifyAppRemoteService.connect(
                    context = this,
                    params = params,
                    onConnected = { remote ->
                        connectingAppRemote = false
                        spotifyAppRemote = remote
                        if (continuation.isActive) {
                            continuation.resume(remote)
                        }
                    },
                    onFailure = { error ->
                        connectingAppRemote = false
                        spotifyAppRemote = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    },
                )
            } catch (t: Throwable) {
                connectingAppRemote = false
                spotifyAppRemote = null
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        }
    }

    private suspend fun awaitAppRemoteCall(
        action: (AppRemote) -> com.spotify.protocol.client.CallResult<*>,
    ) {
        val remote = connectAppRemote()

        suspendCancellableCoroutine<Unit> { continuation ->
            try {
                action(remote)
                    .setResultCallback {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                    .setErrorCallback { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }
        }
    }

    private suspend fun playUriWithAppRemote(uri: String) {
        awaitAppRemoteCall { remote -> remote.playerApi.play(uri) }
    }

    private fun disconnectAppRemote() {
        spotifyAppRemote?.also { spotifyAppRemoteService.disconnect(it) }
        spotifyAppRemote = null
        connectingAppRemote = false
    }

    private suspend fun handleIncomingIntent(intent: Intent?) {
        when {
            isSpotifyAuthRedirectIntent(intent) -> {
                processAuthRedirect(intent?.data)
            }

            isSharedTextIntent(intent) -> {
                processSharedSpotifyItem(intent)
            }
        }
    }

    private fun isSpotifyAuthRedirectIntent(intent: Intent?): Boolean {
        return intent?.data?.scheme == "shufflebyalbum"
    }

    private fun isSharedTextIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SEND
    }

    private suspend fun processSharedSpotifyItem(intent: Intent?) {
        val sharedText = extractSharedText(intent) ?: return
        val sharedItem = parseSpotifyUri(sharedText)
        if (sharedItem == null) {
            snackbar("Spotify album or playlist required for this Share action")
            setIntent(Intent())
            return
        }
        addItem(sharedItem, clearInput = false)
        setIntent(Intent())
    }

    private suspend fun ensureUsableStartupAuth() {
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
            authStatus.text = if (error == "access_denied") {
                "Spotify authorization denied"
            } else {
                "Spotify authorization error: $error"
            }
            prefs.edit().remove(KEY_VERIFIER).apply()
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            authStatus.text = "Spotify authorization failed: missing authorization code"
            reportError(snackbarMessage = "Spotify login did not return an authorization code")
            prefs.edit().remove(KEY_VERIFIER).apply()
            return true
        }
        val verifier = getStringPref(KEY_VERIFIER)
        if (verifier.isNullOrBlank()) {
            authStatus.text = "Missing PKCE verifier; try connecting again"
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
            ?: return snackbar("Enter a valid Spotify album/playlist URI or URL")
        addItem(parsed)
    }

    private suspend fun addItem(parsed: ShuffleItem, clearInput: Boolean = true) {
        val items = getItems().toMutableList()
        if (items.any { it.uri == parsed.uri }) {
            removeRemovedItemByUri(parsed.uri)
            renderRemovedItems()
            return snackbar("Item is already in your list")
        }

        val token = getUsableAccessToken() ?: return snackbar("Connect Spotify first so the app can load item titles")
        val titled = withItemTitle(parsed, token)
            ?: return snackbar("Unable to load title for that item; please try another URI")
        items.add(titled)
        saveItems(items)
        removeRemovedItemByUri(titled.uri)
        renderItemList()
        renderRemovedItems()
        if (clearInput) {
            itemUriInput.setText("")
        }
        snackbar("Added ${quotedTitle(titled.title)}")
    }

    private suspend fun importAlbumsFromPlaylist() {
        val token = getUsableAccessToken() ?: return snackbar("Connect Spotify first so the app can import albums")
        val playlist = parseSpotifyPlaylistRef(itemUriInput.text.toString().trim())
            ?: return snackbar("Enter a valid Spotify playlist URL, URI, or playlist ID")
        snackbar("Importing albums from playlist")

        val existing = getItems().toMutableList()
        val existingUris = existing.map { it.uri }.toMutableSet()
        val playlistResult = fetchPlaylistAlbums(playlist.id, token)
        if (!playlistResult.fullyLoaded) {
            return snackbar(playlistResult.failureMessage ?: "Failed to import albums from playlist")
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
        removeRemovedItemsByUris(albums.map { it.uri })
        renderItemList()
        renderRemovedItems()
        snackbar("Imported $added album(s) from playlist (${albums.size} unique album(s) found)")
    }

    private suspend fun startShuffleSession() {
        getUsableAccessToken() ?: return snackbar("Connect Spotify first")
        val items = getItems()
        if (items.isEmpty()) return snackbar("Add at least one album or playlist first")

        session = session.copy(
            activationState = ActivationState.ACTIVE,
            queue = items.shuffled().toMutableList(),
            index = 0,
        )
        persistRuntimeState()
        renderQueue()
        renderPlaybackControls()
        playbackStatus.text = "Session started with ${session.queue.size} item(s)"
        when (playCurrentItem()) {
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
            playbackStatus.text = "Spotify session expired; please reconnect"
            snackbar("Spotify session expired; please reconnect")
            return
        }
        if (session.queue.isEmpty()) {
            stopSession("No queued item available to reattach")
            snackbar("No queued item available to reattach")
            return
        }

        val snapshotResult = fetchCurrentPlaybackSnapshot(token)
        if (!snapshotResult.ok) {
            val failure = spotifyFailureMessage(snapshotResult.status, snapshotResult.failureReason)
            transitionDetached("Failed to reattach: $failure")
            reportError(snackbarMessage = "Failed to reattach")
            return
        }

        val current = session.queue.getOrNull(session.index) ?: run {
            stopSession("No queued item available to reattach")
            snackbar("No queued item available to reattach")
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
            snackbar("Session reattached")
        } else {
            when (playCurrentItem()) {
                PlaybackStartResult.STARTED -> {
                    transitionActive(startMonitoring = true)
                    snackbar("Session reattached")
                }
                PlaybackStartResult.DETACHED,
                PlaybackStartResult.STOPPED,
                -> Unit
            }
        }
    }

    private suspend fun goToNextItem() {
        if (session.activationState != ActivationState.ACTIVE) {
            playbackStatus.text = "No active session"
            return
        }
        session = session.copy(index = session.index + 1)
        if (session.index >= session.queue.size) {
            stopSession("Finished: all selected albums/playlists were played")
            return
        }
        persistRuntimeState()
        renderQueue()
        getUsableAccessToken() ?: return stopSession("Spotify session expired; reconnect to continue")
        playCurrentItem()
    }

    private suspend fun playCurrentItem(): PlaybackStartResult {
        val current = session.queue.getOrNull(session.index)
            ?: run {
                stopSession("Finished: all selected albums/playlists were played")
                return PlaybackStartResult.STOPPED
            }

        session = session.copy(
            currentUri = current.uri,
            observedCurrentContext = false,
        )
        persistRuntimeState()
        renderPlaybackControls()
        renderQueue()

        val preflightResult = runPlaybackPreflight()
        if (!preflightResult.ok) {
            if (preflightResult.detach) {
                transitionDetached(preflightResult.message)
                reportError(snackbarMessage = preflightResult.message)
                return PlaybackStartResult.DETACHED
            }
            stopSession(preflightResult.message)
            reportError(snackbarMessage = preflightResult.message)
            return PlaybackStartResult.STOPPED
        }

        try {
            playUriWithAppRemote(current.uri)
        } catch (error: Throwable) {
            val failure = describeAppRemoteError(error)
            transitionDetached("Playback detached due to a Spotify error: $failure")
            reportError(snackbarMessage = "Playback detached due to a Spotify error: $failure")
            return PlaybackStartResult.DETACHED
        }

        playbackStatus.text = formatNowPlayingStatus(current)
        return PlaybackStartResult.STARTED
    }

    private suspend fun runPlaybackPreflight(): PlaybackPreflightResult {
        try {
            awaitAppRemoteCall { remote -> remote.playerApi.setShuffle(false) }
        } catch (error: Throwable) {
            val failure = describeAppRemoteError(error)
            return PlaybackPreflightResult(
                ok = false,
                detach = true,
                message = "Playback detached due to a Spotify error: Playback preflight failed: could not disable shuffle ($failure)",
            )
        }

        try {
            awaitAppRemoteCall { remote ->
                remote.playerApi.setRepeat(com.spotify.protocol.types.Repeat.OFF)
            }
        } catch (error: Throwable) {
            val failure = describeAppRemoteError(error)
            return PlaybackPreflightResult(
                ok = false,
                detach = true,
                message = "Playback detached due to a Spotify error: Playback preflight failed: could not disable repeat ($failure)",
            )
        }

        return PlaybackPreflightResult(ok = true, detach = false, message = "")
    }

    private suspend fun monitorPlayback() {
        if (session.activationState != ActivationState.ACTIVE || session.currentUri == null) return
        val token = getUsableAccessToken() ?: return transitionDetached("Spotify session expired; please reconnect")

        val snapshotResult = fetchCurrentPlaybackSnapshot(token)
        if (snapshotResult.status == 204) return
        if (!snapshotResult.ok) {
            val failure = spotifyFailureMessage(snapshotResult.status, snapshotResult.failureReason)
            if (isUnrecoverableMonitorStatus(snapshotResult.status)) {
                transitionDetached("Playback monitoring paused: $failure")
                reportError(
                    snackbarMessage = "Playback monitoring paused: $failure",
                    cooldownKey = "monitor-failure-detached",
                )
            } else {
                playbackStatus.text = "Playback monitor encountered an error: $failure"
                reportError(
                    snackbarMessage = "Playback monitor encountered an error",
                    cooldownKey = "monitor-failure-recoverable",
                )
            }
            return
        }
        val snapshot = snapshotResult.snapshot ?: run {
            val failure = "Missing playback snapshot"
            playbackStatus.text = "Playback monitor encountered an error: $failure"
            reportError(
                snackbarMessage = "Playback monitor encountered an error",
                cooldownKey = "monitor-failure-recoverable",
            )
            return
        }
        val contextUri = snapshot.contextUri

        if (contextUri == session.currentUri) {
            session = session.copy(observedCurrentContext = true)
            persistRuntimeState()
            session.queue.getOrNull(session.index)?.let { current ->
                playbackStatus.text = formatNowPlayingStatus(current)
            }
            return
        }

        if (!session.observedCurrentContext) return

        if (contextUri == null) {
            goToNextItem()
            return
        }

        if (contextUri != session.currentUri) {
            transitionDetached("Spotify is playing a different album/playlist than this app expects; reattach to resume")
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
        playbackMonitorLoop.start(
            intervalMs = PLAYBACK_MONITOR_INTERVAL_MS,
        ) {
            appScope.launch {
                monitorPlayback()
            }
        }
    }

    private fun stopMonitorLoop() {
        playbackMonitorLoop.stop()
    }

    private fun removeItem(item: ShuffleItem) {
        val next = getItems().toMutableList()
        val removedIndex = next.indexOfFirst { it.uri == item.uri }
        if (removedIndex == -1) return

        next.removeAt(removedIndex)
        saveItems(next)
        upsertRemovedItem(item)
        renderItemList()
        renderRemovedItems()
        showUndoSnackbar(item, removedIndex)
    }

    private fun restoreRemovedItem(item: ShuffleItem) {
        val removedIndex = removedItems.indexOfFirst { it.uri == item.uri }
        if (removedIndex == -1) return

        removedItems.removeAt(removedIndex)
        persistRemovedItems()
        val currentItems = getItems().toMutableList()
        if (currentItems.any { it.uri == item.uri }) {
            renderItemList()
            renderRemovedItems()
            snackbar("Item is already in your list")
            return
        }

        currentItems.add(item)
        saveItems(currentItems)
        renderItemList()
        renderRemovedItems()
        snackbar("Restored ${quotedTitle(item.title)}")
    }

    private fun showUndoSnackbar(item: ShuffleItem, removedIndex: Int) {
        undoSnackbar?.dismiss()
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content),
            "Removed ${quotedTitle(item.title)}",
            Snackbar.LENGTH_LONG,
        )
        snackbar.setAction("Undo") {
            restoreRemovedItemAtIndex(item, removedIndex)
        }
        snackbar.addCallback(
            object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (undoSnackbar === transientBottomBar) {
                        undoSnackbar = null
                    }
                }
            },
        )
        undoSnackbar = snackbar
        snackbar.show()
    }

    private fun restoreRemovedItemAtIndex(item: ShuffleItem, insertAt: Int) {
        val currentItems = getItems().toMutableList()
        if (currentItems.any { it.uri == item.uri }) {
            renderItemList()
            snackbar("Item is already in your list")
            return
        }

        val insertIndex = insertAt.coerceIn(0, currentItems.size)
        currentItems.add(insertIndex, item)
        saveItems(currentItems)
        removeRemovedItemByUri(item.uri)
        renderItemList()
        renderRemovedItems()
        snackbar("Restored ${quotedTitle(item.title)}")
    }

    private fun upsertRemovedItem(item: ShuffleItem) {
        removeRemovedItemByUri(item.uri)
        removedItems.add(0, item)
        persistRemovedItems()
    }

    private fun removeRemovedItemByUri(uri: String): Boolean {
        val removedIndex = removedItems.indexOfFirst { it.uri == uri }
        if (removedIndex == -1) return false
        removedItems.removeAt(removedIndex)
        persistRemovedItems()
        return true
    }

    private fun removeRemovedItemsByUris(uris: Collection<String>): Boolean {
        if (uris.isEmpty()) return false
        val removedUriSet = uris.toSet()
        val nextRemovedItems = removedItems.filterNot { it.uri in removedUriSet }
        if (nextRemovedItems.size == removedItems.size) return false
        removedItems.clear()
        removedItems.addAll(nextRemovedItems)
        persistRemovedItems()
        return true
    }

    private fun clearRemovedItems() {
        removedItems.clear()
        persistRemovedItems()
        renderRemovedItems()
    }

    private fun showPurgeRemovedItemsDialog() {
        if (removedItems.isEmpty()) return
        val itemLabel = if (removedItems.size == 1) "1 item" else "${removedItems.size} items"
        AlertDialog.Builder(this)
            .setMessage("Permanently remove $itemLabel?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Purge") { _, _ ->
                clearRemovedItems()
                snackbar("Purged Removed Items")
            }
            .show()
    }

    private fun renderItemList() {
        itemAdapter.submit(getItems())
    }

    private fun renderRemovedItems() {
        removedItemsAdapter.submit(removedItems)
        removedItemsSection.visibility = if (removedItems.isEmpty()) View.GONE else View.VISIBLE
        removedItemsCount.text = if (removedItems.size == 1) "1 item" else "${removedItems.size} items"
        purgeRemovedItemsButton.isEnabled = removedItems.isNotEmpty()
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
            playbackStatus.text = "No active session"
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
            snackbar("Unable to export saved items because stored data is invalid JSON")
            return
        }
        val exportRemovedItems = runCatching { getStoredRemovedItemArrayForExport() }.getOrElse {
            storageJsonInput.setText("")
            snackbar("Unable to export Removed Items because stored data is invalid JSON")
            return
        }
        val data = JSONObject()
            .put(KEY_ITEMS, exportItems)
            .put(KEY_REMOVED_ITEMS, exportRemovedItems)
        storageJsonInput.setText(data.toString(2))
        snackbar("Exported saved items to JSON")
    }

    private fun importStorageJson() {
        val raw = storageJsonInput.text.toString().trim()
        if (raw.isEmpty()) return snackbar("Paste a JSON object to import")

        val parsed = try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            return snackbar("Invalid JSON; please provide a valid JSON object")
        }
        if (parsed !is JSONObject) return snackbar("Import JSON must be an object of key/value pairs")

        val importedItemsArray = parsed.optJSONArray(KEY_ITEMS)
            ?: return snackbar("Import JSON must include a valid shuffle-by-album.items array")
        val importedRemovedItemsValue = parsed.opt(KEY_REMOVED_ITEMS)
        if (importedRemovedItemsValue != null && importedRemovedItemsValue !is JSONArray) {
            return snackbar("Import JSON must include a valid shuffle-by-album.removedItems array when provided")
        }

        val importedItems = parseShuffleItems(importedItemsArray)
        val importedItemUris = importedItems.map { it.uri }.toSet()
        val importedRemovedItems = parseShuffleItems(importedRemovedItemsValue as? JSONArray ?: JSONArray())
            .filterNot { it.uri in importedItemUris }
        saveItems(importedItems)
        saveRemovedItems(importedRemovedItems)
        removedItems.clear()
        removedItems.addAll(importedRemovedItems)

        stopSession("Data imported; session reset")
        refreshAuthStatus()
        renderItemList()
        renderRemovedItems()
        snackbar("Imported saved items")
    }

    private fun getStoredItemArrayForExport(): JSONArray {
        val raw = getStringPref(KEY_ITEMS) ?: return JSONArray()
        val parsed = JSONTokener(raw).nextValue()
        if (parsed !is JSONArray) throw IllegalArgumentException("Expected stored items array")
        return parsed
    }

    private fun getStoredRemovedItemArrayForExport(): JSONArray {
        val raw = getStringPref(KEY_REMOVED_ITEMS) ?: return JSONArray()
        val parsed = JSONTokener(raw).nextValue()
        if (parsed !is JSONArray) throw IllegalArgumentException("Expected stored removed items array")
        return parsed
    }

    private fun getItems(): List<ShuffleItem> {
        val raw = getStringPref(KEY_ITEMS) ?: return emptyList()
        return try {
            parseShuffleItems(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getRemovedItems(): List<ShuffleItem> {
        val raw = getStringPref(KEY_REMOVED_ITEMS) ?: return emptyList()
        return try {
            parseShuffleItems(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseShuffleItems(array: JSONArray): List<ShuffleItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = obj.optString("type")
                val uri = obj.opt("uri")
                if ((type != "album" && type != "playlist") || uri !is String || uri.isBlank()) continue
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
    }

    private fun saveItems(items: List<ShuffleItem>) {
        prefs.edit().putString(KEY_ITEMS, serializeShuffleItems(items).toString()).apply()
    }

    private fun saveRemovedItems(items: List<ShuffleItem>) {
        if (items.isEmpty()) {
            prefs.edit().remove(KEY_REMOVED_ITEMS).apply()
            return
        }
        prefs.edit().putString(KEY_REMOVED_ITEMS, serializeShuffleItems(items).toString()).apply()
    }

    private fun persistRemovedItems() {
        saveRemovedItems(removedItems)
    }

    private fun serializeShuffleItems(items: List<ShuffleItem>): JSONArray {
        return JSONArray().apply {
            items.forEach {
                put(JSONObject().put("type", it.type).put("uri", it.uri).put("title", it.title))
            }
        }
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
        disconnectAppRemote()
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
        val response = formPost(spotifyAccountsUrl("/api/token"), params)
        if (!response.ok || response.body == null) {
            reportError(
                statusView = authStatus,
                statusMessage = "Spotify token exchange failed: ${spotifyFailureMessage(response.status, response.failureReason)}",
            )
            return null
        }
        return parseTokenResponse(response.body) ?: run {
            reportError(
                statusView = authStatus,
                statusMessage = "Spotify token exchange failed: invalid token response",
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
        val response = formPost(spotifyAccountsUrl("/api/token"), params)
        if (!response.ok || response.body == null) {
            val refreshStatusMessage = if (response.failureReason != null) {
                "Network issue refreshing Spotify session; please reconnect if this continues"
            } else {
                "Unable to restore Spotify session; please reconnect"
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
                statusMessage = "Unable to restore Spotify session; please reconnect",
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
                val url = URL(spotifyApiUrl(path))
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
        val message = "Spotify session expired; please reconnect"
        if (session.activationState == ActivationState.ACTIVE || session.activationState == ActivationState.DETACHED) {
            transitionDetached(message)
        } else {
            playbackStatus.text = message
        }
        reportError(
            snackbarMessage = "Spotify session expired; please reconnect",
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

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
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

    private fun spotifyAccountsUri(path: String): Uri {
        return Uri.parse(spotifyAccountsUrl(path))
    }

    private fun spotifyAccountsUrl(path: String): String {
        return buildSpotifyUrl(spotifyAccountsBaseUrl, path)
    }

    private fun spotifyApiUrl(path: String): String {
        return buildSpotifyUrl(spotifyApiBaseUrl, path)
    }

    private fun buildSpotifyUrl(baseUrl: String, path: String): String {
        return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
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

    private fun snackbar(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(findViewById(android.R.id.content), message, duration).show()
    }

    private fun reportError(
        statusView: TextView? = null,
        statusMessage: String? = null,
        snackbarMessage: String? = null,
        cooldownKey: String? = null,
    ) {
        if (!statusMessage.isNullOrBlank() && statusView != null) {
            statusView.text = statusMessage
        }
        if (snackbarMessage.isNullOrBlank()) return
        if (cooldownKey == null) {
            snackbar(snackbarMessage)
            return
        }

        val now = System.currentTimeMillis()
        val nextAllowed = errorSnackbarCooldowns[cooldownKey] ?: 0L
        if (now < nextAllowed) return
        errorSnackbarCooldowns[cooldownKey] = now + ERROR_SNACKBAR_COOLDOWN_MS
        snackbar(snackbarMessage)
    }

    private fun spotifyFailureMessage(status: Int, failureReason: String?): String {
        failureReason?.let { return normalizeNetworkError(it) }
        return spotifyStatusMessage(status)
    }

    private fun spotifyStatusMessage(status: Int): String {
        return when (status) {
            400 -> "Network error while contacting Spotify; please try again"
            401 -> "Spotify session expired; please reconnect"
            403 -> "Spotify permissions are missing; disconnect and reconnect"
            404 -> "Requested Spotify item or playback device was not found"
            429 -> "Spotify rate limit reached; please wait a moment and retry"
            in 500..599 -> "Spotify is temporarily unavailable; please try again shortly"
            else -> "Network error while contacting Spotify; please try again"
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
        internal var spotifyAccountsBaseUrl = "https://accounts.spotify.com"
        internal var spotifyApiBaseUrl = "https://api.spotify.com/v1"
        internal var spotifyAppRemoteService: SpotifyAppRemoteService = RealSpotifyAppRemoteService
        internal val defaultPlaybackMonitorLoopFactory: () -> PlaybackMonitorLoop = { HandlerPlaybackMonitorLoop() }
        internal var playbackMonitorLoopFactory: () -> PlaybackMonitorLoop = defaultPlaybackMonitorLoopFactory

        private val SCOPES = listOf(
            "user-modify-playback-state",
            "user-read-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative",
            "app-remote-control",
        )

        private const val KEY_VERIFIER = "shuffle-by-album.pkceVerifier"
        private const val KEY_TOKEN = "shuffle-by-album.token"
        private const val KEY_REFRESH_TOKEN = "shuffle-by-album.refreshToken"
        private const val KEY_TOKEN_EXPIRY = "shuffle-by-album.tokenExpiry"
        private const val KEY_TOKEN_SCOPE = "shuffle-by-album.tokenScope"
        private const val KEY_ITEMS = "shuffle-by-album.items"
        private const val KEY_REMOVED_ITEMS = "shuffle-by-album.removedItems"
        private const val KEY_RUNTIME = "shuffle-by-album.runtime"
        private const val PLAYBACK_MONITOR_INTERVAL_MS = 4_000L
        private const val ERROR_SNACKBAR_COOLDOWN_MS = 45_000L
    }
}

internal interface PlaybackMonitorLoop {
    fun start(intervalMs: Long, task: () -> Unit)

    fun stop()
}

private class HandlerPlaybackMonitorLoop : PlaybackMonitorLoop {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledTask: Runnable? = null

    override fun start(intervalMs: Long, task: () -> Unit) {
        val repeatingTask = object : Runnable {
            override fun run() {
                task()
                handler.postDelayed(this, intervalMs)
            }
        }
        scheduledTask = repeatingTask
        handler.postDelayed(repeatingTask, intervalMs)
    }

    override fun stop() {
        scheduledTask?.let(handler::removeCallbacks)
        scheduledTask = null
    }
}

internal interface SpotifyAppRemoteService {
    fun isSpotifyInstalled(context: Context): Boolean

    fun connect(
        context: Context,
        params: ConnectionParams,
        onConnected: (AppRemote) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun disconnect(appRemote: AppRemote)
}

private object RealSpotifyAppRemoteService : SpotifyAppRemoteService {
    override fun isSpotifyInstalled(context: Context): Boolean {
        return SpotifyAppRemote.isSpotifyInstalled(context)
    }

    override fun connect(
        context: Context,
        params: ConnectionParams,
        onConnected: (AppRemote) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        SpotifyAppRemote.connect(
            context,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    onConnected(remote)
                }

                override fun onFailure(error: Throwable) {
                    onFailure(error)
                }
            },
        )
    }

    override fun disconnect(appRemote: AppRemote) {
        val spotifyAppRemote = appRemote as? SpotifyAppRemote ?: return
        SpotifyAppRemote.disconnect(spotifyAppRemote)
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
    val error = if (details.isNullOrBlank()) "status $status" else details
    return "Error importing albums: $error"
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
        "network unavailable", "network error" -> "Network error while contacting Spotify; please try again"
        else -> "Network error while contacting Spotify; please try again"
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

private class ItemActionAdapter(
    private val actionLabel: String,
    private val onAction: (ShuffleItem) -> Unit,
) : RecyclerView.Adapter<ItemActionAdapter.ItemViewHolder>() {
    private val items = mutableListOf<ShuffleItem>()

    fun submit(next: List<ShuffleItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return ItemViewHolder(view, actionLabel, onAction)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ItemViewHolder(
        itemView: View,
        private val actionLabel: String,
        private val onAction: (ShuffleItem) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.title)
        private val actionButton: Button = itemView.findViewById(R.id.removeButton)

        fun bind(item: ShuffleItem) {
            title.text = item.title.ifBlank { item.uri }
            actionButton.text = actionLabel
            actionButton.setOnClickListener { onAction(item) }
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
