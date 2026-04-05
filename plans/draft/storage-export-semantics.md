## Issue

The web app exports every current `localStorage` key for the page origin and serializes all values as strings. Android exports only this app's `SharedPreferences` and preserves booleans, numbers, and string sets as typed JSON values. The exported JSON shape therefore differs across platforms.

## Solution

If the goal is parity with the web export format, update `exportStorageJson()` to export a portable string-valued object:

- export only the app's keys
- serialize every stored value as a string
- write missing or `null` values as empty strings
- keep the pretty-printed JSON output in the text box
- update the success toast to `Exported <count> key(s) to JSON.`

If preserving native Android types is still desirable internally, separate that into a different backup/export feature rather than the parity path.
