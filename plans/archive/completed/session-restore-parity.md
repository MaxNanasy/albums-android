## Issue

The web app restores persisted runtime state with a few compatibility behaviors that Android does not mirror, and it follows a different restored-status flow. Android can therefore restore the same saved runtime data differently.

## Solution

Update `restoreRuntimeState()` and the post-restore monitoring path to align more closely with the web implementation:

- keep treating an empty restored queue as inactive
- preserve `currentUri` and `observedCurrentContext` exactly as saved
- after restoration, render the queue and controls before any monitor restart decision
- use the same active-state guard described in `start-session-detached-overwrite.md` and `reattach-detached-overwrite.md` so restored sessions do not become active unless monitoring should actually resume

This should make persisted runtime state more portable between implementations and more robust across app versions.

## Rejected

- do not add support for rebuilding session state from a legacy boolean `active` field; that compatibility behavior is only needed for legacy persisted runtime data

## Depends On

- `start-session-detached-overwrite.md`: restored sessions should follow the same activation guard used for playback start, so restore logic does not mark the session active when start logic would leave it detached or stopped
- `reattach-detached-overwrite.md`: restored sessions should also follow the same activation guard used when reattaching, so a resumed session does not bypass the rules for when monitoring may safely become active again
