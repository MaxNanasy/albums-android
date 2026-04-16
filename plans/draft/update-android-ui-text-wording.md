## Issue

The Android repo still has several user-visible strings that should be aligned with the agreed cross-platform wording for detached playback, reattach failures, the next-item control label, and playlist import failures.

## Solution

Add the following text updates in production code and the affected UI tests.

| Context | Current text | Change to |
| --- | --- | --- |
| Playback status after a Spotify playback-start error detaches the session | `Playback detached: <error>.` | `Playback detached due to a Spotify error: <error>.` |
| Playback status after a recoverable reattach failure | `Cannot reattach: <error>.` | `Failed to reattach: <error>.` |
| Toast after a recoverable reattach failure | `Reattach failed: <error>.` | `Failed to reattach.` |
| Next-item session control label | `Skip` | `Next` |
| Playlist import failure toast after Spotify returns an error | `Unable to import albums from that playlist (status <status>). <details>` | `Error importing albums: <error>.` |

Implementation notes:

- Update production strings in `app/src/main/java/io/github/maxnanasy/shufflebyalbum/MainActivity.kt` and `app/src/main/res/layout/activity_main.xml`.
- Update the affected UI assertions in `app/src/androidTest/java/io/github/maxnanasy/shufflebyalbum/AddAlbumUiTest.kt` and any additional Android UI test files that cover reattach and playlist import flows.
- Keep the existing contextual error details, but format them so they fit the new `<error>` placeholders when those details remain user-visible.
