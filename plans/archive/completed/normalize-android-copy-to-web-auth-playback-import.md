## Issue

The Android app still diverges from the web implementation in several pieces of user-facing copy. The remaining differences are concentrated in app description text, auth/login flows, startup/session validation, playback warnings, status/error wording, and playlist import messaging.

This plan defines the exact target copy so Android can be updated without reading the web implementation.

## Solution

Update Android strings, status text, toast text, and any helper copy so the app uses the following target wording.

### 1. Hero description

Set the top-of-screen descriptive text to exactly:

`Randomly cycles through your saved set of Spotify albums/playlists while keeping each item's tracks in order.`

Implementation notes:

- Use straight apostrophe `'` in `item's` rather than a typographic curly apostrophe.
- This should replace the existing shorter Android hero sentence.

### 2. URI helper copy

Add helper text beneath the add/import controls with exactly this wording:

`Tip: You can paste a normal Spotify URL and it will be converted. For playlist imports, you can also paste a playlist ID.`

Implementation notes:

- This helper text should always be visible in the same section as the URI input.
- It should remain visible whether or not the list contains items.

### 3. Startup restore / session validation messaging

Normalize Android startup auth text so it no longer exposes a dedicated restore lifecycle. Use these target strings instead:

- Generic auth validation failure status: `Unable to validate Spotify session. Please reconnect.`
- Generic auth refresh network-failure status: `Network issue refreshing Spotify session. Please reconnect if this continues.`

Implementation notes:

- Remove startup-only messages such as:
  - `Restoring Spotify session...`
  - `Spotify session restore failed. Connect again.`
  - `Could not restore Spotify session. Please reconnect.`
- Startup success should simply converge to the existing steady-state connected text: `Connected.`
- There should be no dedicated startup restore success toast.

### 4. Missing PKCE verifier

Use this exact wording in all missing-verifier auth/login cases:

`Missing PKCE verifier. Try connecting again.`

Implementation notes:

- Apply this consistently to callback handling and any other verifier-missing path.
- This covers the standalone “Missing PKCE verifier” category and the auth/login subcategory with the same target text.

### 5. No-auth prompts for add/import/start

Use these exact strings for the following cases:

- Add manual item with no usable token: `Connect Spotify first so the app can load item titles.`
- Import albums from playlist with no usable token: `Connect Spotify first so the app can import albums.`
- Start shuffle session with no usable token: `Connect Spotify first.`

Implementation notes:

- Do not use the Android-specific generic reconnect wording for these flows.
- These are intentionally task-specific.

### 6. Wrong-context playback warning

When playback monitoring detects that Spotify is playing the wrong context, use exactly:

`Spotify is playing a different album/playlist than this app expects. Reattach to resume.`

Implementation notes:

- Use this text for the detach warning triggered by a mismatched non-null playback context.

### 7. Network / Spotify status wording

Normalize Android's human-readable Spotify/network error wording to the following target strings:

- Network fetch/auth/API failure: `Network error while contacting Spotify. Please try again.`
- Missing permissions / 403: `Spotify permissions are missing. Disconnect and reconnect.`
- Missing player or item / 404: `Requested Spotify item or playback device was not found.`
- Rate limit / 429: `Spotify rate limit reached. Please wait a moment and retry.`
- 5xx: `Spotify is temporarily unavailable. Please try again shortly.`

Implementation notes:

- Reuse these strings anywhere Android currently emits lower-level wording such as `network unavailable`, `network error`, `Spotify denied permission for this action`, `Spotify player or item was not found`, or `Spotify rate limited this request`.
- For statuses not explicitly listed above, preserve existing fallback structure but prefer user-oriented wording over raw `status N` output where practical.

### 8. Playlist import progress / failure copy

Use the following target copy for playlist import:

- Import starts: `Importing albums from playlist...`
- Invalid playlist reference: `Enter a valid Spotify playlist URL, URI, or playlist ID.`
- Import fetch failure wrapper: `Unable to import albums from that playlist (STATUS). DETAILS`
- Import fetch failure fallback when there is no detailed body: `Unable to import albums from that playlist (STATUS). Please try again.`
- Generic import fallback: `Failed to import albums from playlist.`
- Import success: `Imported N album(s) from playlist (M unique album(s) found).`

Implementation notes:

- `STATUS` and `DETAILS` are placeholders for the actual HTTP status code and parsed response text.
- Prefer the import-specific failure wrapper over generic low-level messages such as `network unavailable`, `request failed`, or `status 404: ...`.
- If the request fails before an HTTP status exists, map that case through the shared network-error wording where appropriate, or use the generic import fallback if no better user-oriented message is available.

### 9. Auth/login subcategory normalization

Apply the following auth/login-specific copy updates.

#### a. Startup/session validation failure

Use:

`Unable to validate Spotify session. Please reconnect.`

Use this as the generic auth-validation failure status instead of restore-specific failure wording.

#### b. Login success

Do not show a dedicated login-success toast after auth redirect handling.

Implementation notes:

- Successful login should be reflected by the connected state in the auth status area.
- This removes the Android-only `Connected to Spotify.` toast.

#### c. Missing PKCE verifier

Covered by section 4 above. Use exactly:

`Missing PKCE verifier. Try connecting again.`
