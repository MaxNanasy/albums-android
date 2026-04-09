## Issue

The web app tolerates many transient playback-monitor failures and only detaches on unrecoverable Spotify statuses or an actual context mismatch. Android detaches on any failed playback snapshot request, which makes the session more fragile.

## Solution

Change `monitorPlayback()` and the playback-snapshot path so Android only detaches for the same categories of failures as the web app:

- add helpers that classify unrecoverable Spotify statuses such as 401, 403, and 404
- when `/me/player` returns a recoverable non-OK response, keep the session state and surface a temporary error instead of detaching immediately
- keep ignoring 204 responses
- continue detaching when Spotify reports a different active context than the expected one
- preserve the existing `observedCurrentContext` guard before auto-advancing

This should make Android monitoring behavior closer to the web app during transient Spotify or network failures.
