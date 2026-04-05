## Issue

Android reports a generic connected/not-connected auth state, while the web app also detects when the current token is missing the playlist import scopes and tells the user to disconnect and reconnect. On Android, playlist import can therefore fail later without an earlier status hint.

## Solution

Add a `getGrantedScopes()` helper that reads `KEY_TOKEN_SCOPE` and splits it into a set. Update `refreshAuthStatus()` so it matches the web app's behavior:

- show `Not connected.` when there is no usable token
- show `Connected, but token is missing playlist import scopes. Disconnect and reconnect.` when either `playlist-read-private` or `playlist-read-collaborative` is absent
- otherwise show `Connected.`

Call the updated status renderer after token exchange, token refresh, logout, storage import, and startup auth restoration so the warning stays current.
