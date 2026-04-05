## Issue

The web app clears all existing stored data and imports each JSON value as a string. Android clears app prefs only, preserves types where possible, converts arrays to `StringSet`, and treats JSON `null` as key removal. The same import payload can therefore produce different stored state.

## Solution

Align `importStorageJson()` with the web app's import contract:

- require the pasted payload to be a JSON object
- clear the app's stored keys before import
- write every imported value as `value?.toString()` instead of restoring native JSON types
- remove the array-to-`StringSet` special case and the numeric-type branching
- keep the existing session reset, item-list rerender, and auth-status refresh after import
- update the toast copy to include the imported key count, matching the web app's feedback style

This makes storage round-tripping more predictable between the two implementations.