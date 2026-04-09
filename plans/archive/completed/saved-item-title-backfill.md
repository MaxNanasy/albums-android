## Issue

The web app performs a startup pass that fills in missing titles for already-saved items once a usable token is available. Android has no equivalent reconciliation step, so legacy or partially imported items can keep raw URIs as titles indefinitely.

## Solution

Add an Android equivalent of `ensureStoredItemTitles()` during startup:

- after auth bootstrap completes, load saved items
- if there are items and a usable token exists, scan for entries whose title is blank or equal to the URI fallback
- call `withItemTitle()` for those items and persist any successful title updates
- rerender the item list after updates
- surface only a throttled or silent failure path so startup is not noisy when Spotify is unavailable

This should let Android converge saved display titles toward the same state as the web app.
