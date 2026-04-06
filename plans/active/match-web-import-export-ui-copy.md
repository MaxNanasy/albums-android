## Issue

The Android import/export section still presents Android-specific copy such as `Import / Export Local Storage`, `Export JSON`, `Import JSON`, and a placeholder that references `spotifyShuffler.items`. The surrounding explanatory text also differs from the web app, which makes the feature look like a raw local-storage tool instead of a saved-items transfer tool.

## Solution

Update Android's import/export labels, placeholder text, and helper copy so the feature presents the same contract as the web app.

- change the section heading from `4) Import / Export Local Storage` to `4) Import / Export Data`
- change the export button text from `Export JSON` to `Export Data JSON`
- change the import button text from `Import JSON` to `Import Data JSON`
- add a visible field label above the multiline text box: `Data JSON`
- change the text box placeholder to the web-compatible example `{"shuffle-by-album.items":[{"type":"album","uri":...`
- add helper copy under the text box matching the web app's meaning: export copies saved items into the text box, and import replaces saved items with the data in the text box
- make sure any success/error toasts in this section use the wording defined in `match-web-import-export-validation-and-effects.md` rather than the older `local storage` wording

The goal is that a user looking only at the Android screen can infer the same import/export contract that the web UI communicates.

## Depends On

- `match-web-import-export-storage-contract.md`: the visible copy should describe the narrowed web-compatible JSON contract rather than Android's old full-preferences dump
- `match-web-import-export-validation-and-effects.md`: button labels, helper copy, placeholder examples, and toast wording should match the actual validation rules and runtime effects defined there
