## Issue

Android can keep partially imported albums when playlist scanning fails mid-run, while the web app treats playlist import as all-or-nothing and leaves the saved list unchanged on failure. Users can therefore end up with different saved queues after the same error.

## Solution

Change `importAlbumsFromPlaylist()` and `fetchPlaylistAlbums()` so Android only mutates saved items after a fully successful playlist scan:

- have `fetchPlaylistAlbums()` return either a complete album list or a failure
- if any page load fails, return an error result without partial albums
- in `importAlbumsFromPlaylist()`, do not merge anything into saved items when the result is a failure
- on full success, show `Imported <added> album(s) from playlist (<unique> unique album(s) found).`

This change should make playlist import atomic from the user's perspective while keeping its success feedback explicit.
