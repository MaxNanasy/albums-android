## Issue

Android's "Import / Export" feature currently serializes the entire `SharedPreferences` map, imports arbitrary keys and value types, and exposes Android-specific storage names such as `spotifyShuffler.items`. The web app's contract is much narrower: export/import only covers the saved item list, and the external JSON uses the single key `shuffle-by-album.items` whose value is an array of saved album/playlist objects.

## Solution

Change the Android import/export feature so its external contract matches the web app exactly.

- treat the import/export payload as a public cross-platform format, not as a raw dump of Android preferences
- export a JSON object with exactly one supported key: `shuffle-by-album.items`
- set that key's value to the current saved item array, not to the serialized string currently stored in preferences
- if the internally stored item payload is missing, export `{ "shuffle-by-album.items": [] }`
- if the internally stored item payload exists but cannot be parsed into an array, clear the export text box and surface an export error instead of exporting partially valid data
- stop exporting auth tokens, refresh tokens, runtime/session state, verifier state, and any other preference keys
- stop importing arbitrary preference keys and typed values; import should only read `shuffle-by-album.items`
- migrate Android item persistence to the web key `shuffle-by-album.items`
- remove `spotifyShuffler` naming from the Android source code entirely, except where it appears inside plan files; use `shuffle-by-album` naming instead for storage keys, constants, helper names, comments, and any other source identifiers or strings
- keep auth-related prefs and runtime/session prefs Android-local; this change is only for the saved-items data contract

Implementation is complete when Android can round-trip the same JSON shape that the web app produces and consumes without including any unrelated preference data, and when `spotifyShuffler` no longer appears anywhere in the Android source code outside of plan files.
