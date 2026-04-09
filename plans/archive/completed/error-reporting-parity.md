## Issue

The web app centralizes error handling, maps Spotify statuses to user-facing messages, and suppresses repeated toasts with cooldown logic. Android reports errors inline at each call site, often with rawer failure text and no suppression, so repeated failures are noisier and less consistent.

## Solution

Introduce a shared Android error-reporting layer similar to the web implementation:

- add helpers for `spotifyStatusMessage`, `isUnrecoverableSpotifyStatus`, and network-error normalization
- add a small reporting function that can update `authStatus`, `playbackStatus`, and toast output from one place
- keep a cooldown map for repeated background errors such as monitor failures so transient loops do not spam toasts
- replace the most repetitive direct `toast(...)` and `playbackStatus.text = ...` error branches with calls into the new helper

This does not need to copy the web UI exactly, but it should align the decision logic and message consistency.
