## Issue

Android's restore flow currently shows `Restored active session.` for restored active sessions and does not mirror the web app's status behavior for restored detached sessions. The web app restores queue/control state for any non-inactive runtime state, shows the current `Now playing ...` line immediately, and only restarts monitor polling when the restored state is active.

## Solution

Make restored-session UI state follow the web app's runtime-restore behavior, while keeping the target behavior explicit.

- during runtime restore, if the restored queue is empty or invalid, clear runtime state and fall back to the inactive state
- if the restored activation state is active or detached and the queue still contains a current item, render the queue and controls immediately and set the playback status with `Now playing TYPE INDEX of TOTAL: TITLE`
- restart monitor polling only for restored active sessions
- keep restored detached sessions detached without replacing their status with Android-specific restore copy
- remove `Restored active session.` and any similar restore-only playback status text that overrides the restored `Now playing ...` line
- review restore-time expired-auth handling so it uses the normal auth and playback expiry paths instead of introducing restore-only status wording when the restored queue state is still valid

Implementation is complete when restored active and detached sessions render the same visible playback status a user would see during a normal active or detached session, matching the web app's restore behavior.
