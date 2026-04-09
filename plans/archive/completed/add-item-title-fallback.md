## Issue

When adding an item, the web app refuses to save it if Spotify title lookup fails. Android instead saves the item with the raw URI as its title. The result is different list contents and different validation behavior.

## Solution

Change `addItem()` to match the web app's stricter flow:

- keep the existing URI parsing and duplicate check
- require a usable access token before title lookup
- call `withItemTitle(parsed, token)`
- if title lookup returns `null`, do not save the item and show an error toast such as `Unable to load title for that item. Please try another URI.`
- only append the item after title lookup succeeds

Keep clearing the input only on successful add.
