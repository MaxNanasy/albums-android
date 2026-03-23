# Spotify Album + Playlist Shuffler

A no-build web app that:

- Connects directly to Spotify with OAuth PKCE.
- Lets you maintain a local list of album + playlist URIs in `localStorage`.
- Randomizes the order of those selected items.
- Plays each selected album/playlist in track order before advancing to the next.

## Run locally

Because OAuth redirect URIs must match exactly, serve this directory with any static server.

```bash
python3 -m http.server 4173
```

Then open `http://localhost:4173`.

## Spotify app setup

1. In the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), create an app.
2. Add your redirect URI (shown in the app UI) to the app settings.
3. Copy the Client ID into the app.

## Requested Spotify scopes (minimal)

This app requests only:

- `user-modify-playback-state` (start playback, turn shuffle off, turn repeat off)
- `user-read-playback-state` (monitor active context and detect when to move to next item)

No library-read scopes are required because the user provides album/playlist URIs manually.
