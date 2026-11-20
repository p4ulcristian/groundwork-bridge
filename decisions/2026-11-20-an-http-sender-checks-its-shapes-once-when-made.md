# An http sender checks its shapes once, when it is made

> The user's decision, 2026-11-20. Since the release after v0.7.15.

**What was wrong:** `send-message!` takes the whole shapes map on every call and checks it against `shapes-by-name-shape` on every call, before the request: every name's `:params-shape`, `:answer-shape` and `:refusals`, each time. The map is the app's fixed data, made once when the page starts; it cannot change between requests. Measured on a real app's page (an app with a few hundred messages, release build, desktop Chrome): 13–49 ms of main-thread time per request, 0.97 s of 4.2 s main-thread work during 16 quick clicks, the page frozen while it ran.

**Decision:** `make-http-sender` makes a sender once, `(make-http-sender address shapes-by-name {:shaper shaper})`, and checks the shapes and its options there, once. The sender is `(send! message request-options)`: each send checks only its own request options (`:headers`, `:keep-alive?`, every key required, as before) and its params against their `:params-shape`, as `send-message!` does. It answers, refuses and reports exactly as `send-message!` does; the work after the checks is one function both share.

`send-message!` stays, unchanged: a library only adds, and a caller that sends once in a while may keep it.

**Why:** a check belongs where the data comes in. The sse and ws lines already work so: `open-sse-line!` and `open-ws-line!` are called once and check the shapes once. http was the one way that took the fixed shapes on every request and so checked them on every request. A check of fixed data on every call costs every request and catches nothing the first check did not.

**Instead of:**
- **`send-message!` remembers the maps it has checked** (a WeakSet, by identity): no caller changes, but the check stays in the wrong place and a hidden cache lives in the library to make up for it; a caller that builds a new map for each send still pays each time
- **no check in the browser:** a broken shapes map would show as wrong answers far from its cause; checked once it costs nothing per request
- **changing `send-message!` to a sender:** breaks every caller; a library only adds
