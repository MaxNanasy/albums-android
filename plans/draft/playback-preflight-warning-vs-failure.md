## Issue

Before starting playback, the web app treats failures to disable shuffle or repeat as part of the playback error path. Android treats those failures as warnings and still continues to attempt `/me/player/play`. This can yield different detach/stop outcomes.

## Solution

Make the pre-playback control calls follow the same decision model as the web app:

- introduce a helper that runs the shuffle-off and repeat-off requests and returns structured success or failure
- if either request fails with an unrecoverable Spotify status, detach the session with a Spotify-specific message
- if either request fails with another error, stop the session instead of continuing blindly
- only attempt `/me/player/play` after both preflight requests succeed
- reuse the same playback-result return type used by the start and reattach plan drafts

This gives Android the same stricter playback-start contract as the web app.