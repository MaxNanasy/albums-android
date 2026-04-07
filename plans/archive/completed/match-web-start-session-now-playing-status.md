## Issue

After a successful session start, Android currently replaces the playback status set by `playCurrentItem()` with the generic text `Monitoring playback.`. The web app briefly shows `Session started with N item(s).` before playback begins and then leaves the final success state as `Now playing ...`.

## Solution

Make Android's start-session status flow match the web app while keeping the target behavior explicit.

- after shuffling the queue, persisting runtime state, and rendering the queue/controls, set the transient status text `Session started with N item(s).`
- keep the playback-start path responsible for deciding the final success status text for the current queue item
- when playback start succeeds, start monitor polling without overwriting the `Now playing TYPE INDEX of TOTAL: TITLE` status produced by the playback-start path
- preserve the detached and stopped outcomes returned by the playback-start flow instead of forcing a generic active-monitoring message
- if a shared helper is introduced for entering the active state, make sure it can start monitoring and persist or render state without replacing an already-correct playback status string

Implementation is complete when a successful session start shows the brief `Session started with N item(s).` message and then leaves the final visible status in the `Now playing ...` form.
