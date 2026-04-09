## Issue

The remove-and-undo flows are similar, but the UI behavior and exact user-visible text are not the same between platforms.

Current text differences:

- Web remove message: `Removed “<title>”.`
- Android remove message: `Removed <title>.`

- Web undo success message: `Restored “<title>”.`
- Android undo success message: `Restored <title>.`

- Web duplicate-on-undo message: `Item is already in your list.`
- Android duplicate-on-undo message: `<title> is already in your list.`

The interaction model also differs:

- the web app shows transient notifications with inline Undo actions
- Android shows custom undo banner rows in a dedicated container

Both current implementations allow multiple Undo actions to be active at the same time, so any parity change that would reduce Android to only one active Undo action at a time would be a behavior regression.

## Solution

Keep the plan focused on parity of interaction and text, without requiring a standardized UI component unless it can support multiple simultaneous Undo actions.

Describe the code change at a high level as:

- preserve Android's ability to show multiple active Undo actions at the same time
- do not replace the current custom banner approach with any standardized transient component unless that component can support multiple simultaneous Undo actions with independent restore behavior
- align the remove, undo-success, and duplicate-on-undo text with the web app's exact wording and title formatting
- keep the existing reinsertion-at-original-index behavior and duplicate guard unchanged
- keep the change scoped to UI presentation and user-visible messaging, not to the underlying saved-item mutation logic

The goal is to make Android's remove-and-undo flow closer to the web app's wording and interaction model while preserving the current multi-Undo capability.
