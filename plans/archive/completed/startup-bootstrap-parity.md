## Issue

The web app's startup sequence restores runtime state, handles auth redirect, validates or refreshes auth, renders UI, and then backfills missing titles. Android's startup flow differs in both order and recovery behavior, which produces different statuses and startup outcomes.

## Solution

Refactor Android startup into an explicit bootstrap sequence that mirrors the web app's decision order as closely as the platform allows:

- keep view binding and list wiring first
- restore runtime state as described in `session-restore-parity.md` before any playback-monitor restart decision
- process auth redirect using the flow described in `auth-redirect-flow-parity.md` if present, otherwise validate or refresh the token if possible
- refresh auth status using the scope-aware status function described in `missing-playlist-scope-status.md`
- render item list, queue, and controls after auth bootstrap settles
- run the saved-title backfill described in `saved-item-title-backfill.md` after auth is usable
- only then decide whether to resume playback monitoring

This should make Android startup behavior easier to reason about and closer to web semantics.

## Depends On

- `auth-redirect-flow-parity.md`: startup bootstrap needs a defined callback-handling sequence for launches that arrive through the Spotify auth redirect
- `missing-playlist-scope-status.md`: startup bootstrap refreshes auth status, so the scope-aware auth-status behavior needs to be defined before the startup sequence can rely on it
- `saved-item-title-backfill.md`: startup bootstrap explicitly runs saved-title backfill after auth becomes usable, so that backfill behavior must be defined first
- `session-restore-parity.md`: startup bootstrap restores runtime state before deciding whether monitoring should resume, so it depends on the restore-time state and activation rules from that plan
