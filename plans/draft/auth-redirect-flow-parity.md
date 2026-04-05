## Issue

The web app handles OAuth redirect state during page bootstrap and removes handled query parameters from the URL, while Android handles a deep-link callback in `onCreate()` and `onNewIntent()`. The platform mechanics are different, but the surrounding state transitions and status updates are not documented as a parity goal.

## Solution

Make the Android auth-callback flow explicitly mirror the web app's state transitions even though the transport differs:

- keep the deep-link callback transport, since Android cannot use the exact browser URL flow from web
- centralize callback handling so both `onCreate()` and `onNewIntent()` go through the same post-callback sequence
- after a successful code exchange, always refresh auth status, rerender items if needed, and clear any temporary verifier state
- after callback errors, set a stable auth-status message and avoid leaving partially updated auth state behind
- document in code comments that transport differs by platform but post-callback state handling is intended to stay aligned with the web app

This keeps the unavoidable platform difference isolated while making the user-visible outcome more consistent.