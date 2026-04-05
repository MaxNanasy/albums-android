## Issue

The web app restores persisted runtime state with a few compatibility behaviors that Android does not mirror, including support for a legacy `active: true` flag and a different restored-status flow. Android can therefore restore the same saved runtime data differently.

## Solution

Update `restoreRuntimeState()` and the post-restore monitoring path to align more closely with the web implementation:

- accept both `activationState` and the legacy boolean `active` field when rebuilding session state
- keep treating an empty restored queue as inactive
- preserve `currentUri` and `observedCurrentContext` exactly as saved
- after restoration, render the queue and controls before any monitor restart decision
- use the same active-state guard proposed in the start and reattach plan drafts so restored sessions do not become active unless monitoring should actually resume

This should make persisted runtime state more portable between implementations and more robust across app versions.