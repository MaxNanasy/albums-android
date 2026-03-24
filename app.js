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
  exportStorageBtn: /** @type {HTMLButtonElement} */ (
    document.getElementById('export-storage-btn')
  ),
  importStorageBtn: /** @type {HTMLButtonElement} */ (
    document.getElementById('import-storage-btn')
  ),
  storageJson: /** @type {HTMLTextAreaElement} */ (document.getElementById('storage-json')),
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

bootstrap().catch((error) => {
  console.error(error);
  setAuthStatus(`Startup error: ${error instanceof Error ? error.message : String(error)}`);
});

async function bootstrap() {
  el.redirectUri.textContent = location.origin + location.pathname;
  el.clientId.value = localStorage.getItem(STORAGE_KEYS.clientId) ?? '';

  hookEvents();
  restoreRuntimeState();
  await handleAuthRedirect();
  renderItemList();
  refreshAuthStatus();
  await ensureStoredItemTitles();
}

function hookEvents() {
  el.loginBtn.addEventListener('click', () => {
    void startLogin();
  });

  el.logoutBtn.addEventListener('click', () => {
    clearAuth();
    refreshAuthStatus();
    setPlaybackStatus('Disconnected from Spotify.');
  });

  el.addForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const parsed = parseSpotifyUri(el.itemUri.value.trim());
    if (!parsed) {
      setPlaybackStatus('Enter a valid Spotify album/playlist URI or URL.');
      return;
    }
    const items = getItems();
    if (items.some((item) => item.uri === parsed.uri)) {
      setPlaybackStatus('Item is already in your list.');
      return;
    }
    const token = getToken();
    if (!token) {
      setPlaybackStatus('Connect Spotify first so the app can load item titles.');
      return;
    }

    const titledItem = await withItemTitle(parsed, token);
    if (!titledItem) {
      setPlaybackStatus('Unable to load title for that item. Please try another URI.');
      return;
    }

    items.push(titledItem);
    saveItems(items);
    el.itemUri.value = '';
    renderItemList();
  });

  el.startBtn.addEventListener('click', () => {
    void startShuffleSession();
  });

  el.importPlaylistBtn.addEventListener('click', () => {
    void importAlbumsFromPlaylist();
  });

  el.skipBtn.addEventListener('click', () => {
    void goToNextItem();
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
  const expiresMs = Number(localStorage.getItem(STORAGE_KEYS.tokenExpiry) ?? 0);
  const minutes = Math.max(0, Math.floor((expiresMs - Date.now()) / 60000));
  const scopeSet = getGrantedScopes();
  if (!scopeSet.has('playlist-read-private') || !scopeSet.has('playlist-read-collaborative')) {
    setAuthStatus(
      `Connected, but token is missing playlist import scopes. Disconnect and reconnect.`,
    );
    return;
  }
  setAuthStatus(`Connected. Token expires in about ${minutes} minute(s).`);
}

function getGrantedScopes() {
  const scopeText = localStorage.getItem(STORAGE_KEYS.tokenScope) ?? '';
  return new Set(scopeText.split(/\s+/).filter(Boolean));
}

async function ensureStoredItemTitles() {
  const items = getItems();
  if (items.length === 0) return;

  const token = getToken();
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

  const response = await fetch('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: formData,
  });
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
  setPlaybackStatus(`Exported ${Object.keys(data).length} local storage key(s) to JSON.`);
}

function importLocalStorageJson() {
  const raw = el.storageJson.value.trim();
  if (!raw) {
    setPlaybackStatus('Paste a JSON object to import.');
    return;
  }

  /** @type {unknown} */
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    setPlaybackStatus('Invalid JSON. Please provide a valid JSON object.');
    return;
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    setPlaybackStatus('Import JSON must be an object of key/value pairs.');
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
  setPlaybackStatus(`Imported ${entries.length} local storage key(s).`);
}

function getToken() {
  const token = localStorage.getItem(STORAGE_KEYS.token);
  const expiryMs = Number(localStorage.getItem(STORAGE_KEYS.tokenExpiry) ?? 0);
  if (!token || Date.now() >= expiryMs) {
    return null;
  }
  return token;
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
    text.textContent = item.title ? `${item.title} (${item.type})` : `${item.uri} (${item.type})`;

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
  const token = getToken();
  if (!token) {
    setPlaybackStatus('Connect Spotify first.');
    return;
  }

  const items = getItems();
  if (items.length === 0) {
    setPlaybackStatus('Add at least one album or playlist first.');
    return;
  }

  session.queue = shuffledCopy(items);
  session.active = true;
  session.index = 0;
  persistRuntimeState();

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

  await playCurrentItem();
}

async function playCurrentItem() {
  const current = session.queue[session.index];
  session.currentUri = current.uri;
  session.observedCurrentContext = false;
  persistRuntimeState();

  const token = getToken();
  if (!token) {
    stopSession('Spotify session expired. Please reconnect.');
    return;
  }

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

  setPlaybackStatus(formatNowPlayingStatus(current));
}

async function importAlbumsFromPlaylist() {
  const token = getToken();
  if (!token) {
    setPlaybackStatus('Connect Spotify first so the app can import albums.');
    return;
  }

  const parsedPlaylist = parseSpotifyPlaylistRef(el.itemUri.value.trim());
  if (!parsedPlaylist) {
    setPlaybackStatus('Enter a valid Spotify playlist URL, URI, or playlist ID.');
    return;
  }

  setPlaybackStatus('Importing albums from playlist...');

  const existingItems = getItems();
  const existingUris = new Set(existingItems.map((item) => item.uri));
  const importResult = await fetchPlaylistAlbums(parsedPlaylist.id, token);
  if (importResult.errorMessage) {
    setPlaybackStatus(importResult.errorMessage);
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
  setPlaybackStatus(
    `Imported ${added} album(s) from playlist (${albumsFromPlaylist.length} unique album(s) found).`,
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
    void monitorPlayback();
  }, 4000);
}

async function monitorPlayback() {
  if (!session.active || !session.currentUri) return;
  const token = getToken();
  if (!token) {
    stopSession('Spotify session expired. Please reconnect.');
    return;
  }

  const response = await spotifyApi('/me/player', { method: 'GET' }, token, false);
  if (response.status === 204) {
    // nothing currently playing/active
    return;
  }

  /** @type {{context?: {uri?: string} | null}} */
  const data = await response.json();
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
    throw new Error(`Spotify API ${path} failed (${response.status}): ${body}`);
  }
  return response;
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
