## Issue

Android import currently accepts any JSON object, converts many JSON value types into `SharedPreferences`, clears all prefs before import, and reports success with Android-specific wording. The web app instead validates a narrow payload shape, normalizes only saved items, replaces the saved item list, resets the active session, rerenders the item list, refreshes auth status, and reports web-specific import/export messages.

## Solution

Make Android import/export behavior match the web app's validation rules, normalization rules, and post-import side effects.

- on import, reject a blank text box with: `Paste a JSON object to import.`
- reject malformed JSON with: `Invalid JSON. Please provide a valid JSON object.`
- reject any top-level value that is not a JSON object with: `Import JSON must be an object of key/value pairs.`
- require the object to contain `shuffle-by-album.items`, and require that value to be an array; if not, reject with: `Import JSON must include a valid shuffle-by-album.items array.`
- ignore all top-level keys other than `shuffle-by-album.items`
- normalize imported array entries the same way as the web app:
  - keep only objects whose `type` is `album` or `playlist` and whose `uri` is a string
  - set `title` to the string `title` field when present
  - otherwise default `title` to the item's `uri`
- replace the saved item list with the normalized array, even if some input entries were discarded during validation
- do not clear unrelated auth or runtime preferences as part of import; only replace the saved item list
- after a successful import, stop the current session with the exact message `Data imported. Session reset.`, rerender the saved-item list, refresh auth status, and show the success toast `Imported saved items.`
- on successful export, populate the text box with pretty-printed JSON and show the success toast `Exported saved items to JSON.`
- on export failure caused by corrupt stored item JSON, clear the text box and show the error toast `Unable to export saved items because stored data is invalid JSON.`

This plan should be implemented against Android's current item parsing/saving helpers so that import/export uses the same normalized item model everywhere else in the app.

## Depends On

- `match-web-import-export-storage-contract.md`: the validation rules and side effects here assume the external payload shape has already been narrowed to the single web-compatible `shuffle-by-album.items` contract
