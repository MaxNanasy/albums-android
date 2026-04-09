## Issue

The Android app still diverges from the web implementation in four user-visible behavior areas that affect startup, recurring error handling, playback monitoring, and playlist import UX:

1. **Auth bootstrap / session restore**
   - Android runs an explicit startup restore flow with restore-specific progress and failure UI.
   - Web performs silent auth validation/refresh during bootstrap and does not present a dedicated restore lifecycle.
   - Android also resolves expired-auth-at-launch earlier than web when restoring an active session.
2. **Repeated error notification cooldown**
   - Android rate-limits repeated error notifications for 8 seconds.
   - Web rate-limits repeated error notifications for 45 seconds.
3. **Monitor-loop recoverable error UX**
   - Android shows recoverable monitor failures as playback-status updates such as `Playback monitoring temporary error: ...` and resurfaces them frequently.
   - Web keeps the session active but uses generic monitor failure messaging and a much longer cooldown.
4. **Import albums from playlist**
   - Android does not show any progress message while importing.
   - Web immediately surfaces an in-progress notification before API work begins.

The goal of this plan is to make Android match the web behavior for these categories without requiring the implementer to inspect the web source.

## Solution

### 1. Normalize auth bootstrap / session restore behavior

Refactor Android startup auth handling so that it matches the web sequence and resulting UI behavior:

1. During activity startup, after runtime state is restored, run a generic auth validation step rather than a dedicated restore flow.
2. If a valid access token is already present, leave startup UI in the normal connected state.
3. If there is no valid access token but there is a refresh token, attempt token refresh silently.
4. If silent refresh succeeds, continue normal bootstrap and show the same steady-state auth text used elsewhere: `Connected.`
5. If silent refresh fails because the request returns a non-OK response or the token payload is invalid:
   - do **not** show a restore-specific progress or failure message,
   - do **not** show a restore-failure toast,
   - fall back to the normal disconnected/auth-validation outcome used by web.
6. If silent refresh fails because of a network exception:
   - surface the generic auth validation failure UI rather than restore-specific messaging,
   - use the same generic auth-validation wording planned in `normalize-android-copy-to-web-auth-playback-import`.
7. Keep the Android startup ordering close to web:
   - restore runtime state,
   - process any auth redirect,
   - ensure a usable access token,
   - render lists/controls/status,
   - reconcile missing titles,
   - then restore session monitoring if still appropriate.
8. Preserve Android-only platform requirements such as custom-scheme callback handling and lifecycle-safe coroutine usage.

Implementation notes:

- Replace `bootstrapAuthState()` with a web-aligned validation helper whose job is “ensure a usable token if possible; otherwise leave generic disconnected/auth-validation UI”.
- Remove restore-only status text such as `Restoring Spotify session...` and `Spotify session restore failed. Connect again.` from the behavior path.
- Remove the restore-only toast `Could not restore Spotify session. Please reconnect.`.
- Ensure auth expiration encountered during runtime still uses the existing expired-session handling path; this plan only changes bootstrap/session-restore behavior.

### 2. Normalize repeated error notification cooldown

1. Change Android's repeated error notification cooldown constant from 8 seconds to 45 seconds.
2. Apply the 45-second cooldown consistently anywhere Android currently uses keyed cooldowns for recurring error toasts.
3. Keep non-keyed toasts immediate.
4. Do not change success or informational toast timing unless required by another plan.

Implementation notes:

- The target behavior is “show the first error immediately, then suppress repeats with the same cooldown key for 45 seconds”.
- This should cover monitor-loop recoverable errors, expired-session notifications, and other repeated keyed errors.

### 3. Normalize monitor-loop recoverable error UX

For recoverable playback-monitor failures (for example network errors, rate limits, or temporary server failures that should not detach the session):

1. Keep the session in the active state.
2. Do not detach the session for recoverable monitor errors.
3. Update playback status using a generic web-style message rather than a highly specific monitor-status sentence.
4. Report the error using the generic monitor failure UX described below.
5. Combine this with the 45-second keyed error cooldown so repeated monitor failures do not spam the user.

Target UI behavior:

- Playback status should use a generic monitor failure line equivalent to: `Unable to check playback state right now.`
- The toast/error notification should use a generic monitor failure line equivalent to: `Playback monitor encountered an error.`
- If parsing the playback payload fails, treat it as the same recoverable monitor failure category unless the status is one of the unrecoverable detach statuses.

Unrecoverable monitor statuses should remain detach-worthy:

- 401
- 403
- 404

These should continue transitioning the session to detached state, but the text for those cases is covered by `normalize-android-copy-to-web-auth-playback-import.md`.

### 4. Normalize playlist import progress behavior

1. When the user starts “Import Albums” / “Import Albums From Playlist”, immediately show an informational in-progress notification with the exact copy specified by the text-normalization plan.
2. Only after showing that progress notification should Android begin playlist fetch work.
3. Preserve current success semantics:
   - deduplicate albums by URI,
   - append only new albums,
   - report the final imported/unique counts.
4. Preserve current invalid-input and no-auth gating behavior, subject to the text changes described in the text-normalization plan.
5. Preserve current failure handling semantics except for copy changes; this plan is about progress behavior and keeping failure flow aligned with the web model.

## Depends On

- `normalize-android-copy-to-web-auth-playback-import.md`: Supplies the exact target strings for the normalized UI states in this plan.
