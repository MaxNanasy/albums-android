# Shuffle By Album (Android)

This repository now contains a native Android app version of Shuffle By Album.

## What was ported from the web app

The Android app includes the same core flow that existed in `index.html` + `app.js`:

1. Connect / disconnect Spotify with OAuth PKCE.
2. Add album/playlist URIs (or Spotify URLs), remove items, and import albums from a playlist.
3. Start a shuffled session that plays each selected album/playlist in order.
4. Reattach, skip, stop, and monitor playback state.
5. Export/import app storage as JSON.

The UI is implemented using Android Material cards and RecyclerViews, so it is intentionally platform-native rather than a direct visual clone of the web layout.

## Open in Android Studio

1. Open this repository in Android Studio (Ladybug or newer recommended).
2. Let Gradle sync.
3. Run the `app` configuration on an emulator/device.

## Spotify notes

- Spotify app ID is currently fixed at `5082b1452bc24cc3a0955f2d1c4e5560`.
- Redirect URI in Android is `shufflebyalbum://callback`.
- Ensure this redirect URI is allowed by your Spotify app settings if authorization fails.
