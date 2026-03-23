// @ts-check

/** @typedef {'album' | 'playlist'} ItemType */

/**
 * @typedef ShuffleItem
 * @property {string} uri
 * @property {ItemType} type
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
];

const STORAGE_KEYS = {
  clientId: 'spotifyShuffler.clientId',
  verifier: 'spotifyShuffler.pkceVerifier',
  token: 'spotifyShuffler.token',
  tokenExpiry: 'spotifyShuffler.tokenExpiry',
  items: 'spotifyShuffler.items',
};

const el = {
  clientId: /** @type {HTMLInputElement} */ (document.getElementById('client-id')),
  loginBtn: /** @type {HTMLButtonElement} */ (document.getElementById('login-btn')),
  logoutBtn: /** @type {HTMLButtonElement} */ (document.getElementById('logout-btn')),
  authStatus: /** @type {HTMLParagraphElement} */ (document.getElementById('auth-status')),
  redirectUri: /** @type {HTMLElement} */ (document.getElementById('redirect-uri')),
  addForm: /** @type {HTMLFormElement} */ (document.getElementById('add-form')),
  itemUri: /** @type {HTMLInputElement} */ (document.getElementById('item-uri')),
  itemList: /** @type {HTMLUListElement} */ (document.getElementById('item-list')),
  startBtn: /** @type {HTMLButtonElement} */ (document.getElementById('start-btn')),
  skipBtn: /** @type {HTMLButtonElement} */ (document.getElementById('skip-btn')),
  stopBtn: /** @type {HTMLButtonElement} */ (document.getElementById('stop-btn')),
  playbackStatus: /** @type {HTMLParagraphElement} */ (document.getElementById('playback-status')),
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
  await handleAuthRedirect();
  renderItemList();
  refreshAuthStatus();
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

  el.addForm.addEventListener('submit', (event) => {
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
    items.push(parsed);
    saveItems(items);
    el.itemUri.value = '';
    renderItemList();
  });

  el.startBtn.addEventListener('click', () => {
    void startShuffleSession();
  });

  el.skipBtn.addEventListener('click', () => {
    void goToNextItem();
  });

  el.stopBtn.addEventListener('click', () => {
    stopSession('Session stopped.');
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
  setAuthStatus(`Connected. Token expires in about ${minutes} minute(s).`);
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

  /** @type {{access_token: string; expires_in: number}} */
  const data = await response.json();
  localStorage.setItem(STORAGE_KEYS.token, data.access_token);
  localStorage.setItem(STORAGE_KEYS.tokenExpiry, String(Date.now() + data.expires_in * 1000));
  localStorage.removeItem(STORAGE_KEYS.verifier);

  url.searchParams.delete('code');
  history.replaceState({}, '', url.toString());
}

function clearAuth() {
  localStorage.removeItem(STORAGE_KEYS.token);
  localStorage.removeItem(STORAGE_KEYS.tokenExpiry);
  localStorage.removeItem(STORAGE_KEYS.verifier);
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
    return parsed.filter(
      (item) =>
        item &&
        typeof item === 'object' &&
        (item.type === 'album' || item.type === 'playlist') &&
        typeof item.uri === 'string',
    );
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
    text.textContent = item.uri;

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

  setPlaybackStatus(
    `Now playing ${current.type} ${session.index + 1} of ${session.queue.length}: ${current.uri}`,
  );
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
    return;
  }

  if (session.observedCurrentContext && contextUri !== session.currentUri) {
    // Current context moved away (likely finished, or user manually changed it).
    await goToNextItem();
  }
}

/**
 * @param {string} path
 * @param {RequestInit} init
 * @param {string} token
 * @param {boolean} throwOnError
 */
async function spotifyApi(path, init, token, throwOnError = true) {
  const response = await fetch(`https://api.spotify.com/v1${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(init.headers ?? {}),
    },
  });

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
    return { type: /** @type {ItemType} */ (uriMatch[1]), uri: raw };
  }

  try {
    const url = new URL(raw);
    if (!url.hostname.includes('spotify.com')) return null;

    const segments = url.pathname.split('/').filter(Boolean);
    if (segments.length < 2) return null;

    const [, type, id] = ['', segments[0], segments[1]];
    if ((type === 'album' || type === 'playlist') && /^[a-zA-Z0-9]+$/.test(id)) {
      return { type, uri: `spotify:${type}:${id}` };
    }
  } catch {
    // not a URL
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
