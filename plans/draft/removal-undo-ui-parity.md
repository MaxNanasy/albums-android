## Issue

Removing an item is functionally similar on both platforms, but the undo experience differs. The web app uses a toast with an inline Undo action, while Android uses a dedicated undo banner row with its own dismissal timing and presentation.

## Solution

Choose whether Android should match the web interaction model more closely. If parity is the goal:

- replace the custom undo banner row with a shared transient message component that supports an inline Undo action
- keep the current reinsertion-at-original-index behavior and duplicate guard
- align the dismissal duration and success/failure copy with the web app's remove-and-undo flow
- keep the underlying saved-item mutation logic unchanged

This plan is intentionally UI-scoped and should not change the stored data semantics.