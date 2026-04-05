## Issue

The web app retries a Spotify API call once after a 401 by refreshing the access token and replaying the request. Android refreshes tokens when the cached expiry has passed, but it does not retry a request that receives a live 401 from Spotify.

## Solution

Refactor `spotifyApi()` so it can transparently retry once after a 401:

- after an initial response with status 401, call `refreshSpotifyAccessToken()`
- if refresh succeeds, replay the same request once with the new bearer token
- if the replay still returns 401, clear auth and transition to a detached state with an expired-session message
- keep non-401 responses on the existing path
- make sure both JSON requests and form-post-driven auth flows keep their current behavior

This should match the web app's more robust handling of server-side token invalidation.