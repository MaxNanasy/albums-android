## Issue

`startShuffleSession()` calls `playCurrentItem(token)` and then unconditionally calls `transitionActive("Monitoring playback.")`. If `playCurrentItem()` detached the session because playback failed, `startShuffleSession()` can still overwrite that state and mark the session active.

## Solution

Make `startShuffleSession()` depend on the outcome of playback start instead of unconditionally forcing the active state:

- change `playCurrentItem()` to return an explicit result, such as success, detached, or stopped
- only call `transitionActive(...)` when playback start actually succeeds
- preserve detached or stopped states produced inside `playCurrentItem()`
- keep the queue persistence and control rendering before playback start, so state is still recoverable if needed

This should prevent Android from reporting an active session after a failed playback start.