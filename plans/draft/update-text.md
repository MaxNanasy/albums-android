## Issue

The Android repo has several user-visible strings that should be aligned with the new cross-platform wording.

## Solution

Add the following text updates in production code and the affected UI tests.

| Context | Current text | Change to |
| --- | --- | --- |
| Playback status after a Spotify playback-start error detaches the session | `Playback detached: <error>.` | `Playback detached due to a Spotify error: <error>.` |
| Playback status after a recoverable reattach failure | `Cannot reattach: <error>.` | `Failed to reattach: <error>.` |
| Toast after a recoverable reattach failure | `Reattach failed: <error>.` | `Failed to reattach.` |
| Next-item session control label | `Skip` | `Next` |
| Playlist import failure toast after Spotify returns an error | `Unable to import albums from that playlist (status <status>). <details>` | `Error importing albums: <error>.` |
| Add/import input placeholder in the item-management panel | `spotify:album:... or spotify:playlist:...` | `https://open.spotify.com/(album|playlist)/...` |
| Helper copy beneath the add/import controls | `Tip: You can paste a normal Spotify URL and it will be converted. For playlist imports, you can also paste a playlist ID.` | `<b>Add</b> adds one item to the list`<br>`<b>Import Albums</b> processes a playlist and adds each song's album to the list` |

Implementation notes:

- Update production strings in `app/src/main/java/io/github/maxnanasy/shufflebyalbum/MainActivity.kt` and `app/src/main/res/layout/activity_main.xml`.
- Update the affected UI assertions in `app/src/androidTest/java/io/github/maxnanasy/shufflebyalbum/AddAlbumUiTest.kt`.
- Update any helper-text assertions or snapshots that cover the input placeholder or the explanatory copy beneath the add/import controls.
- Preserve the intended two-line presentation of the helper copy when implementing the new wording.
- Keep the existing contextual error details, but format them so they fit the new `<error>` placeholders when those details remain user-visible.
