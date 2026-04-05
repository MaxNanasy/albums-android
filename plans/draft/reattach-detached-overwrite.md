## Issue

`reattachSession()` can call `playCurrentItem(token)` and then unconditionally call `transitionActive("Session reattached. Monitoring playback.")`. If `playCurrentItem()` detached or stopped the session, `reattachSession()` can still incorrectly reactivate it.

## Solution

Refactor `reattachSession()` and `playCurrentItem()` so reattachment respects the playback-start outcome:

- use the same explicit return type proposed for playback start results
- only transition back to active monitoring when reattach either confirms the expected current context or successfully restarts playback
- if playback restart detaches or stops the session, leave that state intact and do not overwrite the status text
- keep the success toast only for the actual successful reattach paths

This aligns reattach semantics with the web app, which only resumes monitoring if the session is still active after the reattach attempt.