// @ts-check

/** @typedef {'album' | 'playlist'} ItemType */

/**
 * @typedef ShuffleItem
 * @property {string} uri
 * @property {ItemType} type
 * @property {string} title
 */

/**
 * @typedef SessionState
 * @property {boolean} active
 * @property {ShuffleItem[]} queue
 * @property {number} index
 * @property {string | null} currentUri
 * @property {boolean} observedCurrentContext
 */

const SCOPES = [
  // control playback + read active playback context
  'user-modify-playback-state',
  'user-read-playback-state',
  // read playlist metadata/tracks for importing albums
  'playlist-read-private',
  'playlist-read-collaborative',
];

const STORAGE_KEYS = {
  clientId: 'spotifyShuffler.clientId',
  verifier: 'spotifyShuffler.pkceVerifier',
  token: 'spotifyShuffler.token',
  refreshToken: 'spotifyShuffler.refreshToken',
  tokenExpiry: 'spotifyShuffler.tokenExpiry',
  tokenScope: 'spotifyShuffler.tokenScope',
  items: 'spotifyShuffler.items',
  runtime: 'spotifyShuffler.runtime',
};

const el = {
  clientId: /** @type {HTMLInputElement} */ (document.getElementById('client-id')),
  loginBtn: /** @type {HTMLButtonElement} */ (document.getElementById('login-btn')),
  logoutBtn: /** @type {HTMLButtonElement} */ (document.getElementById('logout-btn')),
  authStatus: /** @type {HTMLParagraphElement} */ (document.getElementById('auth-status')),
  redirectUri: /** @type {HTMLElement} */ (document.getElementById('redirect-uri')),
  addForm: /** @type {HTMLFormElement} */ (document.getElementById('add-form')),
  itemUri: /** @type {HTMLInputElement} */ (document.getElementById('item-uri')),
  importPlaylistBtn: /** @type {HTMLButtonElement} */ (
    document.getElementById('import-playlist-btn')
  ),
  itemList: /** @type {HTMLUListElement} */ (document.getElementById('item-list')),
  startBtn: /** @type {HTMLButtonElement} */ (document.getElementById('start-btn')),
  skipBtn: /** @type {HTMLButtonElement} */ (document.getElementById('skip-btn')),
  stopBtn: /** @type {HTMLButtonElement} */ (document.getElementById('stop-btn')),
  playbackStatus: /** @type {HTMLParagraphElement} */ (document.getElementById('playback-status')),
  queueList: /** @type {HTMLUListElement} */ (document.getElementById('queue-list')),
  exportStorageBtn: /** @type {HTMLButtonElement} */ (
    document.getElementById('export-storage-btn')
  ),
  importStorageBtn: /** @type {HTMLButtonElement} */ (
    document.getElementById('import-storage-btn')
  ),
  storageJson: /** @type {HTMLTextAreaElement} */ (document.getElementById('storage-json')),
  toastStack: /** @type {HTMLDivElement} */ (document.getElementById('toast-stack')),
};

/** @type {SessionState} */
const session = {
  active: false,
  queue: [],
  index: 0,
  currentUri: null,
  observedCurrentContext: false,
};

let monitorTimer = /** @type {number | null} */ (null);
const TOAST_DURATION_MS = 5000;
const ERROR_TOAST_COOLDOWN_MS = 45000;
/** @type {Map<string, number>} */
const errorToastLastShownAt = new Map();

bootstrap().catch((error) => {
  reportError(error, {
    context: 'startup',
    fallbackMessage: 'The app failed to initialize.',
    authStatusMessage: 'Startup failed. Please refresh and reconnect Spotify.',
    toastMode: 'cooldown',
    toastKey: 'startup',
  });
});

async function bootstrap() {
  el.redirectUri.textContent = location.origin + location.pathname;
  el.clientId.value = localStorage.getItem(STORAGE_KEYS.clientId) ?? '';

  hookEvents();
  restoreRuntimeState();
  await handleAuthRedirect();
  await ensureValidAccessToken();
  renderItemList();
  renderSessionQueue();
  refreshAuthStatus();
  await ensureStoredItemTitles();
}

async function ensureValidAccessToken() {
  try {
    await getUsableAccessToken();
  } catch (error) {
    reportError(error, {
      context: 'auth',
      fallbackMessage: 'Unable to validate Spotify session.',
      authStatusMessage: 'Unable to validate Spotify session. Please reconnect.',
      toastMode: 'cooldown',
      toastKey: 'auth-validate',
    });
  }
}

function hookEvents() {
  el.loginBtn.addEventListener('click', () => {
    void startLogin().catch((error) => {
      reportError(error, {
        context: 'auth',
        fallbackMessage: 'Failed to start Spotify connection.',
        authStatusMessage: 'Unable to connect right now. Please try again.',
      });
    });
  });

  el.logoutBtn.addEventListener('click', () => {
    clearAuth();
    refreshAuthStatus();
    showToast('Disconnected from Spotify.', 'info');
  });

  el.addForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const parsed = parseSpotifyUri(el.itemUri.value.trim());
      if (!parsed) {
        showToast('Enter a valid Spotify album/playlist URI or URL.', 'error');
        return;
      }
      const items = getItems();
      if (items.some((item) => item.uri === parsed.uri)) {
        showToast('Item is already in your list.', 'info');
        return;
      }
      const token = await getUsableAccessToken();
      if (!token) {
        showToast('Connect Spotify first so the app can load item titles.', 'error');
        return;
      }

      const titledItem = await withItemTitle(parsed, token);
      if (!titledItem) {
        showToast('Unable to load title for that item. Please try another URI.', 'error');
        return;
      }

      items.push(titledItem);
      saveItems(items);
      el.itemUri.value = '';
      renderItemList();
      showToast('Item added.', 'success');
    } catch (error) {
      reportError(error, {
        context: 'items',
        fallbackMessage: 'Failed to add this item.',
      });
    }
  });

  el.startBtn.addEventListener('click', () => {
    void startShuffleSession().catch((error) => {
      reportError(error, {
        context: 'playback',
        fallbackMessage: 'Failed to start shuffle session.',
        playbackStatusMessage: 'Unable to start session right now. Please try again.',
      });
    });
  });

  el.importPlaylistBtn.addEventListener('click', () => {
    void importAlbumsFromPlaylist().catch((error) => {
      reportError(error, {
        context: 'import',
        fallbackMessage: 'Failed to import albums from playlist.',
      });
    });
  });

  el.skipBtn.addEventListener('click', () => {
    void goToNextItem().catch((error) => {
      reportError(error, {
        context: 'playback',
        fallbackMessage: 'Failed to skip to the next item.',
        playbackStatusMessage: 'Unable to skip right now. Please try again.',
      });
    });
  });

  el.stopBtn.addEventListener('click', () => {
    stopSession('Session stopped.');
  });

  el.exportStorageBtn.addEventListener('click', () => {
    exportLocalStorageJson();
  });

  el.importStorageBtn.addEventListener('click', () => {
    importLocalStorageJson();
  });
}

function refreshAuthStatus() {
  const token = getToken();
  if (!token) {
    setAuthStatus('Not connected.');
    return;
  }
  const scopeSet = getGrantedScopes();
  if (!scopeSet.has('playlist-read-private') || !scopeSet.has('playlist-read-collaborative')) {
    setAuthStatus(
      `Connected, but token is missing playlist import scopes. Disconnect and reconnect.`,
    );
    return;
  }
  setAuthStatus('Connected.');
}

function getGrantedScopes() {
  const scopeText = localStorage.getItem(STORAGE_KEYS.tokenScope) ?? '';
  return new Set(scopeText.split(/\s+/).filter(Boolean));
}

async function ensureStoredItemTitles() {
  const items = getItems();
  if (items.length === 0) return;

  let token = null;
  try {
    token = await getUsableAccessToken();
  } catch (error) {
    reportError(error, {
      context: 'items',
      fallbackMessage: 'Unable to refresh saved item titles.',
      toastMode: 'cooldown',
      toastKey: 'item-title-refresh',
    });
    return;
  }
  if (!token) return;

  let changed = false;
  const updated = [];
  for (const item of items) {
    if (item.title) {
      updated.push(item);
      continue;
    }

    const titledItem = await withItemTitle(item, token);
    if (!titledItem) {
      updated.push({ ...item, title: item.uri });
    } else {
      updated.push(titledItem);
    }
    changed = true;
  }

  if (changed) {
    saveItems(updated);
    renderItemList();
  }
}

/** @param {string} message */
function setAuthStatus(message) {
  el.authStatus.textContent = message;
}

/** @param {string} message */
function setPlaybackStatus(message) {
  el.playbackStatus.textContent = message;
}


/**
 * @param {string} message
 * @param {'success' | 'info' | 'error'} [type]
 */
function showToast(message, type = 'info') {
  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.role = type === 'error' ? 'alert' : 'status';

  const body = document.createElement('span');
  body.className = 'toast-message';
  body.textContent = message;

  const closeButton = document.createElement('button');
  closeButton.type = 'button';
  closeButton.className = 'toast-close';
  closeButton.setAttribute('aria-label', 'Close notification');
  closeButton.textContent = '×';

  /** @type {number | null} */
  let timeoutId = window.setTimeout(removeToast, TOAST_DURATION_MS);

  function clearDismissTimer() {
    if (timeoutId !== null) {
      window.clearTimeout(timeoutId);
      timeoutId = null;
    }
  }

  function restartDismissTimer() {
    clearDismissTimer();
    timeoutId = window.setTimeout(removeToast, TOAST_DURATION_MS);
  }

  function removeToast() {
    clearDismissTimer();
    toast.classList.add('toast-leaving');
    window.setTimeout(() => {
      toast.remove();
    }, 180);
  }

  closeButton.addEventListener('click', removeToast);
  toast.addEventListener('mouseenter', clearDismissTimer);
  toast.addEventListener('mouseleave', restartDismissTimer);

  toast.append(body, closeButton);
  el.toastStack.appendChild(toast);
}

async function startLogin() {
  const clientId = el.clientId.value.trim();
  if (!clientId) {
    setAuthStatus('Please provide your Spotify Client ID.');
    return;
  }
  localStorage.setItem(STORAGE_KEYS.clientId, clientId);

  const verifier = randomString(64);
  const challenge = await codeChallengeFromVerifier(verifier);
  localStorage.setItem(STORAGE_KEYS.verifier, verifier);

  const params = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    scope: SCOPES.join(' '),
    redirect_uri: location.origin + location.pathname,
    code_challenge_method: 'S256',
    code_challenge: challenge,
    show_dialog: 'true',
  });

  location.href = `https://accounts.spotify.com/authorize?${params.toString()}`;
}

async function handleAuthRedirect() {
  const url = new URL(location.href);
  const code = url.searchParams.get('code');
  const error = url.searchParams.get('error');

  if (error) {
    setAuthStatus(`Spotify authorization error: ${error}`);
    url.searchParams.delete('error');
    history.replaceState({}, '', url.toString());
    return;
  }

  if (!code) return;

  const clientId = localStorage.getItem(STORAGE_KEYS.clientId);
  const verifier = localStorage.getItem(STORAGE_KEYS.verifier);

  if (!clientId || !verifier) {
    setAuthStatus('Missing PKCE verifier/client ID. Try connecting again.');
    return;
  }

  const formData = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: location.origin + location.pathname,
    client_id: clientId,
    code_verifier: verifier,
  });

  const response = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: formData,
  });

  if (!response.ok) {
    setAuthStatus('Failed to exchange Spotify code for token.');
    return;
  }

  /** @type {{access_token: string; refresh_token?: string; expires_in: number; scope?: string}} */
  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.token, data.access_token);
  if (data.refresh_token) {
    localStorage.setItem(STORAGE_KEYS.refreshToken, data.refresh_token);
  }
  localStorage.setItem(STORAGE_KEYS.tokenExpiry, String(Date.now() + data.expires_in * 1000));
  localStorage.setItem(STORAGE_KEYS.tokenScope, data.scope ?? '');
  localStorage.removeItem(STORAGE_KEYS.verifier);

  url.searchParams.delete('code');
  history.replaceState({}, '', url.toString());
}

function clearAuth() {
  localStorage.removeItem(STORAGE_KEYS.token);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  localStorage.removeItem(STORAGE_KEYS.tokenExpiry);
  localStorage.removeItem(STORAGE_KEYS.tokenScope);
  localStorage.removeItem(STORAGE_KEYS.verifier);
}

async function refreshSpotifyAccessToken() {
  const clientId = localStorage.getItem(STORAGE_KEYS.clientId);
  const refreshToken = localStorage.getItem(STORAGE_KEYS.refreshToken);
  if (!clientId || !refreshToken) return null;

  const formData = new URLSearchParams({
    grant_type: 'refresh_token',
    refresh_token: refreshToken,
    client_id: clientId,
  });

  let response;
  try {
    response = await fetch('https://accounts.spotify.com/api/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: formData,
    });
  } catch (error) {
    reportError(error, {
      context: 'auth',
      fallbackMessage: 'Unable to refresh Spotify session.',
      authStatusMessage: 'Network issue refreshing Spotify session. Please reconnect if this continues.',
      toastMode: 'cooldown',
      toastKey: 'refresh-token-network',
    });
    return null;
  }
  if (!response.ok) return null;

  /** @type {{access_token: string; refresh_token?: string; expires_in: number; scope?: string}} */
  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.token, data.access_token);
  localStorage.setItem(STORAGE_KEYS.tokenExpiry, String(Date.now() + data.expires_in * 1000));
  if (typeof data.scope === 'string') {
    localStorage.setItem(STORAGE_KEYS.tokenScope, data.scope);
  }
  if (data.refresh_token) {
    localStorage.setItem(STORAGE_KEYS.refreshToken, data.refresh_token);
  }
  return data.access_token;
}

function exportLocalStorageJson() {
  /** @type {Record<string, string>} */
  const data = {};
  for (let index = 0; index < localStorage.length; index += 1) {
    const key = localStorage.key(index);
    if (!key) continue;
    const value = localStorage.getItem(key);
    data[key] = value ?? '';
  }

  el.storageJson.value = JSON.stringify(data, null, 2);
  showToast(`Exported ${Object.keys(data).length} local storage key(s) to JSON.`, 'success');
}

function importLocalStorageJson() {
  const raw = el.storageJson.value.trim();
  if (!raw) {
    showToast('Paste a JSON object to import.', 'error');
    return;
  }

  /** @type {unknown} */
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    showToast('Invalid JSON. Please provide a valid JSON object.', 'error');
    return;
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    showToast('Import JSON must be an object of key/value pairs.', 'error');
    return;
  }

  const entries = Object.entries(parsed);
  localStorage.clear();
  for (const [key, value] of entries) {
    if (typeof key !== 'string' || key.length === 0) continue;
    localStorage.setItem(key, String(value ?? ''));
  }

  stopSession('Local storage imported. Session reset.');
  el.clientId.value = localStorage.getItem(STORAGE_KEYS.clientId) ?? '';
  renderItemList();
  refreshAuthStatus();
  showToast(`Imported ${entries.length} local storage key(s).`, 'success');
}

function getToken() {
  const token = localStorage.getItem(STORAGE_KEYS.token);
  const expiryMs = Number(localStorage.getItem(STORAGE_KEYS.tokenExpiry) ?? 0);
  if (!token || Date.now() >= expiryMs) {
    return null;
  }
  return token;
}

async function getUsableAccessToken() {
  const token = getToken();
  if (token) return token;

  const hasRefreshToken = Boolean(localStorage.getItem(STORAGE_KEYS.refreshToken));
  if (!hasRefreshToken) return null;

  return refreshSpotifyAccessToken();
}

/** @returns {ShuffleItem[]} */
function getItems() {
  const raw = localStorage.getItem(STORAGE_KEYS.items);
  if (!raw) return [];

  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter(
        (item) =>
          item &&
          typeof item === 'object' &&
          (item.type === 'album' || item.type === 'playlist') &&
          typeof item.uri === 'string',
      )
      .map((item) => ({
        type: item.type,
        uri: item.uri,
        title: typeof item.title === 'string' ? item.title : item.uri,
      }));
  } catch {
    return [];
  }
}

/** @param {ShuffleItem[]} items */
function saveItems(items) {
  localStorage.setItem(STORAGE_KEYS.items, JSON.stringify(items));
}

function renderItemList() {
  const items = getItems();
  el.itemList.innerHTML = '';

  for (const item of items) {
    const li = document.createElement('li');
    const text = document.createElement('span');
    text.textContent = item.title ? item.title : item.uri;

    const actions = document.createElement('div');
    actions.className = 'row';

    const removeButton = document.createElement('button');
    removeButton.type = 'button';
    removeButton.className = 'danger';
    removeButton.textContent = 'Remove';
    removeButton.addEventListener('click', () => {
      saveItems(getItems().filter((candidate) => candidate.uri !== item.uri));
      renderItemList();
    });

    actions.appendChild(removeButton);
    li.append(text, actions);
    el.itemList.appendChild(li);
  }
}

async function startShuffleSession() {
  const token = await getUsableAccessToken();
  if (!token) {
    showToast('Connect Spotify first.', 'error');
    return;
  }

  const items = getItems();
  if (items.length === 0) {
    showToast('Add at least one album or playlist first.', 'info');
    return;
  }

  session.queue = shuffledCopy(items);
  session.active = true;
  session.index = 0;
  persistRuntimeState();
  renderSessionQueue();

  setPlaybackStatus(`Session started with ${session.queue.length} item(s).`);
  await playCurrentItem();
  startMonitorLoop();
}

/** @param {string} message */
function stopSession(message) {
  session.active = false;
  session.queue = [];
  session.index = 0;
  session.currentUri = null;
  session.observedCurrentContext = false;
  clearRuntimeState();
  if (monitorTimer !== null) {
    clearInterval(monitorTimer);
    monitorTimer = null;
  }
  renderSessionQueue();
  setPlaybackStatus(message);
}

async function goToNextItem() {
  if (!session.active) {
    setPlaybackStatus('No active session.');
    return;
  }

  session.index += 1;
  persistRuntimeState();
  if (session.index >= session.queue.length) {
    stopSession('Finished: all selected albums/playlists were played.');
    return;
  }
  renderSessionQueue();

  await playCurrentItem();
}

async function playCurrentItem() {
  const current = session.queue[session.index];
  session.currentUri = current.uri;
  session.observedCurrentContext = false;
  persistRuntimeState();

  const token = await getUsableAccessToken();
  if (!token) {
    stopSession('Spotify session expired. Please reconnect.');
    return;
  }

  try {
    await spotifyApi('/me/player/shuffle?state=false', { method: 'PUT' }, token);
    await spotifyApi('/me/player/repeat?state=off', { method: 'PUT' }, token);

    await spotifyApi(
      '/me/player/play',
      {
        method: 'PUT',
        body: JSON.stringify({
          context_uri: current.uri,
          offset: { position: 0 },
          position_ms: 0,
        }),
      },
      token,
    );
  } catch (error) {
    reportError(error, {
      context: 'playback',
      fallbackMessage: 'Unable to start playback on Spotify.',
      playbackStatusMessage: 'Could not start playback. Ensure an active Spotify device is available.',
    });
    stopSession('Playback failed. Session stopped.');
    return;
  }

  setPlaybackStatus(formatNowPlayingStatus(current));
}

async function importAlbumsFromPlaylist() {
  const token = await getUsableAccessToken();
  if (!token) {
    showToast('Connect Spotify first so the app can import albums.', 'error');
    return;
  }

  const parsedPlaylist = parseSpotifyPlaylistRef(el.itemUri.value.trim());
  if (!parsedPlaylist) {
    showToast('Enter a valid Spotify playlist URL, URI, or playlist ID.', 'error');
    return;
  }

  showToast('Importing albums from playlist...', 'info');

  const existingItems = getItems();
  const existingUris = new Set(existingItems.map((item) => item.uri));
  const importResult = await fetchPlaylistAlbums(parsedPlaylist.id, token);
  if (importResult.errorMessage) {
    showToast(importResult.errorMessage, 'error');
    return;
  }
  const albumsFromPlaylist = importResult.albums;

  let added = 0;
  for (const album of albumsFromPlaylist) {
    if (existingUris.has(album.uri)) continue;
    existingItems.push(album);
    existingUris.add(album.uri);
    added += 1;
  }

  saveItems(existingItems);
  renderItemList();
  showToast(
    `Imported ${added} album(s) from playlist (${albumsFromPlaylist.length} unique album(s) found).`,
    'success',
  );
}

/**
 * @param {string} playlistId
 * @param {string} token
 * @returns {Promise<{albums: ShuffleItem[]; errorMessage: string | null}>}
 */
async function fetchPlaylistAlbums(playlistId, token) {
  /** @type {Map<string, ShuffleItem>} */
  const albumsByUri = new Map();
  let offset = 0;
  const limit = 50;

  while (true) {
    const params = new URLSearchParams({
      limit: String(limit),
      offset: String(offset),
      additional_types: 'track',
      market: 'from_token',
    });
    const response = await spotifyApi(
      `/playlists/${playlistId}/items?${params.toString()}`,
      { method: 'GET' },
      token,
      false,
    );
    if (!response.ok) {
      const details = await response.text();
      return {
        albums: [],
        errorMessage: `Unable to import albums from that playlist (${response.status}). ${details || 'Please try again.'}`,
      };
    }

    /** @type {{items?: Array<{item?: {album?: {uri?: string; id?: string; name?: string} | null} | null}>; next?: string | null}} */
    const data = await response.json();
    const items = data.items ?? [];
    for (const entry of items) {
      const album = entry?.item?.album;
      const albumUri = album?.uri ?? (album?.id ? `spotify:album:${album.id}` : '');
      const albumName = (album?.name ?? '').trim();
      if (!albumUri) continue;
      if (!albumsByUri.has(albumUri)) {
        albumsByUri.set(albumUri, {
          uri: albumUri,
          type: 'album',
          title: albumName || albumUri,
        });
      }
    }

    if (!data.next) break;
    offset += limit;
  }

  return { albums: [...albumsByUri.values()], errorMessage: null };
}

/**
 * @param {{uri: string; type: ItemType; title?: string}} item
 * @param {string} token
 * @returns {Promise<ShuffleItem | null>}
 */
async function withItemTitle(item, token) {
  const id = spotifyIdFromUri(item.uri);
  if (!id) return null;

  const path = item.type === 'album' ? `/albums/${id}` : `/playlists/${id}`;
  const response = await spotifyApi(path, { method: 'GET' }, token, false);
  if (!response.ok) return null;

  /** @type {{name?: string}} */
  const data = await response.json();
  const title = (data.name ?? '').trim();
  if (!title) return null;

  return { uri: item.uri, type: item.type, title };
}

/** @param {string} uri */
function spotifyIdFromUri(uri) {
  const match = uri.match(/^spotify:(album|playlist):([a-zA-Z0-9]+)$/);
  return match ? match[2] : null;
}

function startMonitorLoop() {
  if (monitorTimer !== null) clearInterval(monitorTimer);
  monitorTimer = window.setInterval(() => {
    void monitorPlayback().catch((error) => {
      reportError(error, {
        context: 'monitor',
        fallbackMessage: 'Playback monitor encountered an error.',
        playbackStatusMessage: 'Playback monitor paused due to an error. Try restarting the session.',
        toastMode: 'cooldown',
        toastKey: 'monitor-loop',
      });
    });
  }, 4000);
}

async function monitorPlayback() {
  if (!session.active || !session.currentUri) return;
  const token = await getUsableAccessToken();
  if (!token) {
    stopSession('Spotify session expired. Please reconnect.');
    return;
  }

  const response = await spotifyApi('/me/player', { method: 'GET' }, token, false);
  if (response.status === 204) {
    // nothing currently playing/active
    return;
  }

  if (!response.ok) {
    const details = await response.text();
    reportError(new Error(`Playback monitor request failed (${response.status}): ${details}`), {
      context: 'monitor',
      fallbackMessage: spotifyStatusMessage(response.status, 'Could not check playback state.'),
      playbackStatusMessage: 'Unable to check playback state right now.',
      toastMode: 'cooldown',
      toastKey: `monitor-http-${response.status}`,
    });
    return;
  }

  /** @type {{context?: {uri?: string} | null}} */
  let data;
  try {
    data = await response.json();
  } catch (error) {
    reportError(error, {
      context: 'monitor',
      fallbackMessage: 'Unexpected playback response from Spotify.',
      playbackStatusMessage: 'Unable to read current playback state.',
      toastMode: 'cooldown',
      toastKey: 'monitor-json',
    });
    return;
  }
  const contextUri = data?.context?.uri ?? null;

  if (contextUri === session.currentUri) {
    session.observedCurrentContext = true;
    persistRuntimeState();
    return;
  }

  if (session.observedCurrentContext && contextUri !== session.currentUri) {
    // Current context moved away (likely finished, or user manually changed it).
    await goToNextItem();
  }
}

function restoreRuntimeState() {
  const raw = localStorage.getItem(STORAGE_KEYS.runtime);
  if (!raw) return;

  /** @type {unknown} */
  let parsedUnknown;
  try {
    parsedUnknown = JSON.parse(raw);
  } catch {
    localStorage.removeItem(STORAGE_KEYS.runtime);
    return;
  }

  if (!parsedUnknown || typeof parsedUnknown !== 'object' || Array.isArray(parsedUnknown)) {
    localStorage.removeItem(STORAGE_KEYS.runtime);
    return;
  }
  /** @type {Record<string, unknown>} */
  const parsed = /** @type {Record<string, unknown>} */ (parsedUnknown);

  const queueValue = parsed.queue;
  const restoredQueue = Array.isArray(queueValue)
    ? queueValue.filter(
        /** @param {unknown} item */
        (item) => {
          if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
          /** @type {Record<string, unknown>} */
          const runtimeItem = /** @type {Record<string, unknown>} */ (item);
          return (
            (runtimeItem.type === 'album' || runtimeItem.type === 'playlist') &&
            typeof runtimeItem.uri === 'string' &&
            typeof runtimeItem.title === 'string'
          );
        },
      )
    : [];

  const indexValue = parsed.index;
  const restoredIndex =
    typeof indexValue === 'number' && Number.isInteger(indexValue) && indexValue >= 0
      ? indexValue
      : 0;
  const restoredCurrentUri = typeof parsed.currentUri === 'string' ? parsed.currentUri : null;
  const restoredObserved = parsed.observedCurrentContext === true;
  const restoredActive = parsed.active === true && restoredQueue.length > 0;

  session.queue = restoredQueue;
  session.index = Math.min(restoredIndex, Math.max(0, restoredQueue.length - 1));
  session.currentUri = restoredCurrentUri;
  session.observedCurrentContext = restoredObserved;
  session.active = restoredActive;

  if (!session.active) {
    clearRuntimeState();
    return;
  }

  const current = session.queue[session.index];
  setPlaybackStatus(formatNowPlayingStatus(current));
  renderSessionQueue();
  startMonitorLoop();
}

function persistRuntimeState() {
  localStorage.setItem(
    STORAGE_KEYS.runtime,
    JSON.stringify({
      active: session.active,
      queue: session.queue,
      index: session.index,
      currentUri: session.currentUri,
      observedCurrentContext: session.observedCurrentContext,
    }),
  );
}

function clearRuntimeState() {
  localStorage.removeItem(STORAGE_KEYS.runtime);
}

function renderSessionQueue() {
  el.queueList.innerHTML = '';
  if (!session.active || session.queue.length === 0) return;

  for (let i = 0; i < session.queue.length; i += 1) {
    const item = session.queue[i];
    const li = document.createElement('li');
    if (i === session.index) {
      li.classList.add('current');
    }
    const marker = i === session.index ? '▶' : '•';
    li.textContent = `${marker} ${i + 1}. ${item.title}`;
    el.queueList.appendChild(li);
  }
}

/**
 * @param {ShuffleItem} item
 * @returns {string}
 */
function formatNowPlayingStatus(item) {
  return `Now playing ${item.type} ${session.index + 1} of ${session.queue.length}: ${item.title}`;
}

/**
 * @param {string} path
 * @param {RequestInit} init
 * @param {string} token
 * @param {boolean} throwOnError
 */
async function spotifyApi(path, init, token, throwOnError = true) {
  /** @param {string} bearerToken */
  const makeRequest = (bearerToken) =>
    fetch(`https://api.spotify.com/v1${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${bearerToken}`,
        'Content-Type': 'application/json',
        ...(init.headers ?? {}),
      },
    });

  let response = await makeRequest(token);
  if (response.status === 401) {
    const refreshedToken = await refreshSpotifyAccessToken();
    if (refreshedToken) {
      response = await makeRequest(refreshedToken);
    }

    if (response.status === 401) {
      clearAuth();
      stopSession('Spotify session expired. Please reconnect.');
      setAuthStatus('Spotify session expired. Please reconnect.');
    }
  }

  if (!response.ok && throwOnError) {
    const body = await response.text();
    const message = spotifyStatusMessage(response.status, `Spotify API request failed for ${path}.`);
    throw new Error(body ? `${message} ${body}` : message);
  }
  return response;
}

/**
 * @param {unknown} error
 * @param {{
 *   context: string;
 *   fallbackMessage: string;
 *   authStatusMessage?: string;
 *   playbackStatusMessage?: string;
 *   toastMode?: 'always' | 'cooldown';
 *   toastKey?: string;
 * }} options
 */
function reportError(error, options) {
  const message = errorMessageForUser(error, options.fallbackMessage);
  console.error(`[${options.context}]`, error);
  if (options.authStatusMessage) {
    setAuthStatus(options.authStatusMessage);
  }
  if (options.playbackStatusMessage) {
    setPlaybackStatus(options.playbackStatusMessage);
  }

  const toastKey = options.toastKey ?? `${options.context}:${message}`;
  if (options.toastMode === 'cooldown') {
    const lastAt = errorToastLastShownAt.get(toastKey) ?? 0;
    if (Date.now() - lastAt >= ERROR_TOAST_COOLDOWN_MS) {
      errorToastLastShownAt.set(toastKey, Date.now());
      showToast(message, 'error');
    }
    return;
  }

  showToast(message, 'error');
}

/**
 * @param {unknown} error
 * @param {string} fallbackMessage
 */
function errorMessageForUser(error, fallbackMessage) {
  const raw = error instanceof Error ? error.message : String(error ?? '');
  if (raw && (/Failed to fetch/i.test(raw) || /NetworkError/i.test(raw))) {
    return 'Network error while contacting Spotify. Please try again.';
  }
  return fallbackMessage;
}

/**
 * @param {number} status
 * @param {string} fallbackMessage
 */
function spotifyStatusMessage(status, fallbackMessage) {
  if (status === 401) {
    return 'Spotify session expired. Please reconnect.';
  }
  if (status === 403) {
    return 'Spotify permissions are missing. Disconnect and reconnect.';
  }
  if (status === 404) {
    return 'Requested Spotify item or playback device was not found.';
  }
  if (status === 429) {
    return 'Spotify rate limit reached. Please wait a moment and retry.';
  }
  if (status >= 500) {
    return 'Spotify is temporarily unavailable. Please try again shortly.';
  }
  return fallbackMessage;
}

/**
 * @param {string} raw
 * @returns {ShuffleItem | null}
 */
function parseSpotifyUri(raw) {
  if (!raw) return null;

  const uriMatch = raw.match(/^spotify:(album|playlist):([a-zA-Z0-9]+)$/);
  if (uriMatch) {
    return { type: /** @type {ItemType} */ (uriMatch[1]), uri: raw, title: '' };
  }

  try {
    const url = new URL(raw);
    if (!url.hostname.includes('spotify.com')) return null;

    const segments = url.pathname.split('/').filter(Boolean);
    if (segments.length < 2) return null;

    const [, type, id] = ['', segments[0], segments[1]];
    if ((type === 'album' || type === 'playlist') && /^[a-zA-Z0-9]+$/.test(id)) {
      return { type, uri: `spotify:${type}:${id}`, title: '' };
    }
  } catch {
    // not a URL
  }

  return null;
}

/**
 * @param {string} raw
 * @returns {{id: string; uri: string} | null}
 */
function parseSpotifyPlaylistRef(raw) {
  if (!raw) return null;

  const uriItem = parseSpotifyUri(raw);
  if (uriItem?.type === 'playlist') {
    const id = spotifyIdFromUri(uriItem.uri);
    if (!id) return null;
    return { id, uri: uriItem.uri };
  }

  if (/^[a-zA-Z0-9]+$/.test(raw)) {
    return { id: raw, uri: `spotify:playlist:${raw}` };
  }

  return null;
}

/** @param {ShuffleItem[]} values */
function shuffledCopy(values) {
  const out = [...values];
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** @param {number} length */
function randomString(length) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let text = '';
  const randomValues = crypto.getRandomValues(new Uint8Array(length));
  for (const value of randomValues) {
    text += chars[value % chars.length];
  }
  return text;
}

/** @param {string} verifier */
async function codeChallengeFromVerifier(verifier) {
  const data = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(digest);

  let str = '';
  for (const byte of bytes) str += String.fromCharCode(byte);

  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}
