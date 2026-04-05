## Issue

The web app exports every current `localStorage` key for the page origin and serializes all values as strings. Android exports only this app's `SharedPreferences` and preserves booleans, numbers, and string sets as typed JSON values. The exported JSON shape therefore differs across platforms.

## Solution

Decide whether Android should intentionally match the web app's storage-export contract. If the goal is parity, update `exportStorageJson()` to export a web-compatible object:

- export only the app's keys, but serialize every stored value as a string so the payload matches web import expectations
- ensure `null` values are written as empty strings or omitted, matching the web contract chosen for parity
- keep the pretty-printed JSON output in the text box
- update the success toast to include the exported key count in the same style as the web app

If preserving native Android types is still desirable internally, separate that into a different backup/export feature rather than the parity path.