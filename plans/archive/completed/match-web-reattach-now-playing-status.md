## Issue

On a successful reattach, Android currently shows Android-specific status text such as `Session reattached. Monitoring playback.` and emits a success toast. The web app instead ends with the same `Now playing ...` status used by normal playback and simply resumes monitoring if the session is still active.

## Solution

Make successful reattach reuse the same user-visible playback state as the web app, while still defining the target behavior explicitly.

- when the expected Spotify context is already active, mark the session active, set `currentUri` and `observedCurrentContext`, and set the playback status with `Now playing TYPE INDEX of TOTAL: TITLE`
- when reattach needs to restart playback, let the playback-start path own the final success status text and do not overwrite it with `Session reattached. Monitoring playback.`
- only restart monitor polling if the reattach attempt leaves the session active
- retain the success toast behavior even though the final playback status should switch to `Now playing TYPE INDEX of TOTAL: TITLE`
- remove any other reattach-only success copy that has no web equivalent, but keep the toast as a separate confirmation surface
- align the direct reattach failure strings with the web app where there is a clear equivalent, including `No queued item available to reattach.` and `Spotify session expired. Please reconnect.` for those specific cases

Implementation is complete when a successful reattach ends with the normal `Now playing ...` playback status while still retaining the existing success toast as a separate confirmation.

## Depends On

- `match-web-start-session-now-playing-status.md`: both paths should share the same rule that successful playback leaves the final visible status in the `Now playing ...` form instead of replacing it with generic monitoring text
