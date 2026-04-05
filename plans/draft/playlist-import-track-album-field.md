## Issue

Android currently treats playlist import as if the playlist items response were shaped around a top-level track object with an `album` field. Spotify's playlist-items API instead returns an array of playlist entry objects, where each entry contains an `item` object, and album data for track entries is nested under that `item`. Because of that response shape, Android can read the wrong nested field or miss albums that are present in the playlist-items response.

## Solution

Update the playlist import plan so it is based on the actual shape of the Spotify playlist-items endpoint.

Describe the code change at a high level as:

- the import logic should treat each element in the playlist-items response as a playlist entry object
- album extraction should follow the nested path inside the entry's `item` object, rather than assuming album data is exposed at a different level
- the parsing logic should clearly reflect that the playlist-items endpoint returns entry wrappers around the actual playlist item payload
- entries whose `item` does not include album data in that nested shape should be ignored intentionally
- URI-based deduplication and pagination behavior should remain unchanged

The goal is to make Android's playlist import behavior match the structure of Spotify's playlist-items response, instead of assuming a flatter track-centered object layout.