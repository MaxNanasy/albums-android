## Issue

The web app's startup sequence restores runtime state, handles auth redirect, validates or refreshes auth, renders UI, and then backfills missing titles. Android's startup flow differs in both order and recovery behavior, which produces different statuses and startup outcomes.

## Solution

Refactor Android startup into an explicit bootstrap sequence that mirrors the web app's decision order as closely as the platform allows:

- keep view binding and list wiring first
- restore runtime state before any playback-monitor restart decision
- process auth redirect if present, otherwise validate or refresh the token if possible
- refresh auth status using the new scope-aware status function
- render item list, queue, and controls after auth bootstrap settles
- run saved-title backfill after auth is usable
- only then decide whether to resume playback monitoring

This should make Android startup behavior easier to reason about and closer to web semantics.