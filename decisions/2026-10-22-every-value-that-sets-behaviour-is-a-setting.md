> **Changed in part** by [Accept-Encoding is read by its grammar; gzip;q=0 is no](2026-10-27-accept-encoding-is-read-by-its-grammar.md) (2026-10-27, v0.7.13): a browser takes gzip when its Accept-Encoding lists gzip (or x-gzip), else *, with a quality above 0.

> **Changed in part** by [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md) (2026-10-23, v0.7.5): `:max-header-line-bytes` is `:max-request-head-bytes` (the request line and headers together, 431); `:idle-timeout-ms` and `:threads` are new, required; the http-kit options and loggers named here are gone.

> **Changed in part** by [A message answered off the server's addresses is limited by the caller's limiter](2026-10-23-a-message-answered-off-the-servers-addresses-is-limited-by-the-callers-limiter.md) (2026-10-23, v0.7.6): `answer-message` takes `:no-rate-limits` or a limiter from `make-message-limiter`, which counts each handler's `:rate-limit` off the server's addresses, in buckets of its own.

> **Changed in part** by [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md) (2026-10-23, v0.7.7): reitit's default handler answers a request no route and no file matches by the app's `:not-found`, required; nil is the empty 404.

> **Changed in part** by [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md) (2026-10-23, v0.7.0): the settings gain `:metric-buckets {:answer-seconds […]}`, required.

> **Changed in part** by [A page has its query, #fragment and language; a move says how to scroll](2026-10-23-a-page-has-its-query-and-language-a-move-says-how-to-scroll.md) (2026-10-23, v0.6.0): `start-following-address!` takes options, every key required; `go-to-page!` and `make-address-of-page` take a query, `{}` for none; reitit-frontend's history options are gone with `easy`.

> **Changed in part** by [A message sent over http may carry more headers, written out](2026-10-23-a-message-may-carry-more-headers.md) (2026-10-23, v0.5.0): `send-message!`'s options gain `:headers`, `{}` for none.

# Every value that sets behaviour is a setting; no hidden defaults

> **Changed in part** by [The app passes its log](2026-10-22-the-app-passes-its-log.md) (2026-10-22, v0.3.0): a handler's failure and http-kit's errors and warnings go to the app's log, `:log`, not to `System.Logger` `mailroom`; `start-server!` takes an options map, `{:log log}`.

> **Changed in part** by [The app passes its wire; mailroom never makes one](2026-10-22-the-app-passes-its-wire.md) (2026-10-22, v0.2.0): `answer-message` takes an options map, `{:wire wire}`, last; the settings and options are checked by malli itself.

> **Changed in part** by [An sse line reopens after an HTTP error too](2026-10-22-sse-reopens-after-an-http-error.md) (2026-10-22, v0.1.3): the app hands `:sse-reconnect-after-ms` to the browser too, `open-sse-line!`'s required `:reconnect-after-ms`, and a line the server answered with an HTTP error opens again after it.

> Changes in part [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md), [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) and [A handler says its shapes](2026-10-22-a-handler-says-its-shapes.md): the values they still left fixed or filled in, and the keys they left optional. Since v0.1.2.

**Decision:** every value that sets how mailroom behaves is written out by the app: a setting, a required option or a required key, checked by malli before anything runs, the maps of settings and options closed, a missing or unknown key refused, named, with every other problem at once. No function fills in a value: there is no shorter arity, no `:or`, no optional key, and no default of http-kit's or reitit's left in effect unwritten. What stays fixed is listed below, each with its reason.

- **The settings**, `settings-shape`, every key required:

  ```clojure
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:proxy-protocol [:enum :disable :enable :optional]]
   [:client-address-header [:maybe :string]]
   [:server-header [:maybe :string]]
   [:max-body-bytes [:int {:min 1}]]
   [:max-header-line-bytes [:int {:min 1}]]
   [:max-ws-message-bytes [:int {:min 1}]]
   [:addresses [:map {:closed true} [:http :string] [:sse :string] [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]
   [:sse-reconnect-after-ms [:int {:min 1}]]
   [:ws-reopen-after-ms [:int {:min 1}]]
   [:stop-wait-ms [:int {:min 0}]]
   [:stop-wait-after-cut-ms [:int {:min 0}]]
   [:rate-limit-buckets-before-cleanup [:int {:min 1}]]]
  ```

  New since v0.1.1:
  - `:proxy-protocol`, `:server-header`, `:max-body-bytes`, `:max-header-line-bytes`, `:max-ws-message-bytes`: http-kit's options, which mailroom left at http-kit's defaults (`:disable`, `"http-kit"`, 8 MB, 8 KB, 4 MB). A body over the limit is refused with 413, a line with 414.
  - `:sse-reconnect-after-ms`: the server writes it on every sse line as it opens, `retry: 3000`, so the browser waits that long before it reopens a broken line. Before, the server sent no `retry:` and each browser used its own.
  - `:stop-wait-after-cut-ms`: `stop-server!` had two fixed waits after `:stop-wait-ms`: 1000 ms more for http-kit's stop, and 1 s for the request threads. http-kit's stop returns once it has waited `:stop-wait-ms` and cut what was left (it interrupts the request threads), so the two waits were one: how long the cut requests get to end. They are one setting. `stop-server!` returns whether every request's thread had ended. Not folded into `:stop-wait-ms`: one total would leave a cut request no time at all to end (to roll back its transaction before the app closes its pool), a choice the app should see.

- **`make-ring-handler-from-routes`'s options**: `:files-folder` is now `:files`, nil for none, or every key required:

  ```clojure
  {:files {:folder "target/public"
           :hashed-name-min-hex-digits 8           ; main.3F9A0C1D.js counts as hashed
           :hashed-file-cache-seconds 31536000}}   ; and is kept this long
  ```

  Both numbers were fixed: a year, and at least 8 hex characters.

- **A handler writes out every key mailroom reads**: `:steps`, `:params-shape`, `:answer-shape`, `:rate-limit` and `:run-steps-in`, nil where nothing applies (`settings/handler-shape`). `make-routes-for-messages` and `answer-message` refuse a table that leaves one out, `:mailroom/bad-handlers`. A handler map stays open: an app's or a library's own keys (an access rule) ride along.
- **A page writes out its `:title`**, nil when the app sets the tab's title itself; the pages are checked by malli (`settings/pages-shape`), `:mailroom/bad-pages`, every problem at once, instead of the first one thrown.
- **The browser's shapes write out both keys for every name**, nil where the data passes as it is (`settings/shapes-by-name-shape`, `:mailroom/bad-shapes`), checked by `send-message!`, `open-sse-line!` and `open-ws-line!`. A name the table doesn't have no longer passes as it is: going out it throws `:mailroom/no-shapes` and nothing is sent; coming in it arrives as `{:mailroom/errors […] :data …}`, as an answer that doesn't fit.
- **No arity fills in a value**:
  - `open-sse-line!` takes its options always, `{:on-open f-or-nil}`, checked (`:mailroom/bad-options`); the 3-argument form passing `{}` is gone.
  - `answer-message` takes the rate limits: `(answer-message handlers :no-rate-limits request message)`. The server's limiter counts a handler's `:rate-limit`; outside a server there is none, and the call says so. Inside, `answer-named-messages` takes the limiter always.
  - `send-message-on-sse-line!` takes the id always, nil written out for none.
  - `go-to-page!` and `make-address-of-page` take the path params always, `{}` for none.
- **`mailroom.settings` is a `.cljc`**: every shape the data is checked by, the server's and the browser's, is in one file; the browser's ws options moved there from `mailroom.ws`.
- **The defaults of the libraries under mailroom are written out** where mailroom calls them: http-kit's options (above, and its error and warning loggers), reitit's file handler (`:index-files []`, `:canonicalize-uris? false`, `:index-redirect? false`, `:allow-symlinks? false`) and its default handler (404, 405, 406), reitit-frontend's history (`:use-fragment false`, `:ignore-anchor-click?` its own check), `fetch`'s options, and the EventSource's `withCredentials`.

**What stays fixed, and why:**

- **The browser's credentials: `fetch` with `mode "same-origin"` and `credentials "same-origin"`, the EventSource with `withCredentials false`.** A security constant of the design: the three addresses are paths on the page's own origin (ws builds its URL from the page's host), and mailroom sends no CORS headers, so the cookie goes with every message to its own origin and to no other. `withCredentials` only matters across origins; it was `true`, which invited a cross-origin line the server never answers, and is `false` now. An app that wanted another origin would need CORS, a design change, not a setting.
- **The other `fetch` options**: `cache "no-store"` (an answer is never kept; the server says `no-store` too) and `redirect "follow"` (a redirect a proxy in front adds is followed, as the browser does).
- **The files folder serves a file by its exact name only** (no index file, no redirect to a folder, no link out of the folder): a page is answered by name, by the page handler, so a folder's address is never a file. This changes v0.1.1, which served a folder's `index.html` (reitit's default). A file that isn't hashed is `no-cache`: its name stays when its content changes, so anything else serves stale code. A `.gz` beside a file goes out to a browser that takes gzip: the same bytes, fewer of them.
- **Each request on its own virtual thread**, and Ring handlers synchronous (`:ring-async? false`): [http-kit serves, reitit routes](2026-10-12-http-kit-and-reitit.md).
- **One log, `System.Logger` named `mailroom`**: a handler's failure, http-kit's errors and warnings; where it goes is the JVM's logging setup. http-kit's per-request events are not logged.
- **The protocol**: the ping texts (`: ping` on sse, `{:mailroom/ping {}}` on ws), the status codes (400 for a body that isn't a JSON object or a GET on ws that isn't a websocket, 404, 405, 406), the headers (`no-store` on answers; `text/event-stream`, `no-cache` and `x-accel-buffering: no` on sse), JSON in UTF-8, and the error texts (`"Too many requests."`, `"Something went wrong."`, `"No step answered."`, `"The answer doesn't fit its shape."`, `"No handler named …"`). Both ends of mailroom and its tests read them; they are what mailroom says, not what an app chooses. An app shows its own words in the browser.
- **The rate limit's arithmetic**: a token bucket that starts full, holds `:max` and refills at `:max` per `:per-ms`; the numbers are each handler's.
- **`x-forwarded-for` is read from its last entry**, the one the proxy in front added; everything before it the asker can write. Behind more than one proxy, `:client-address-header` names the header the outer one sets (`cf-connecting-ip`).
- **ws follows the page**: `wss://` when the page came over https, the page's host.
- **A message no browser handler takes is a console warning**, not an error: the line keeps working, and it isn't dropped silently.

**Why:** a value left to a default is a value the app doesn't show. An 8 MB upload limit, a year of caching, an index page served from the files folder, a reconnect delay each browser picks: each is behaviour a user feels, and each was invisible in the app's settings, set by mailroom or by a library under it. The same holds for a filled-in argument: `open-sse-line!` without options, `answer-message` with no limiter, a handler with no `:answer-shape`: the code didn't say whether it was chosen or forgotten, and a forgotten `:answer-shape` sends an answer unchecked. Written out, each one says so. "No defaults" in [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) meant this from the start; it had been applied only to the values someone had noticed.

**The price:** an app's settings grow from 8 keys to 15, every handler writes four keys more, nearly always nil, and every name the browser sends or receives needs its two shapes. A browser line, a page move and a link each write out what used to be filled in. An app moving from v0.1.1 changes its settings, its handlers, its pages, its browser shapes and its calls of `open-sse-line!`, `answer-message`, `send-message-on-sse-line!`, `go-to-page!` and `make-address-of-page`.

**Instead of:**
- defaults the app may override: a good value without thought, but the value in effect is invisible, as before
- writing out only the values mailroom itself chose, leaving http-kit's and reitit's: the app still couldn't read its body limit or its index-file rule
- the credentials as settings: a cross-origin mailroom would need CORS headers and a different cookie story; a setting would promise what the server can't do
- one stop wait for everything: a cut request would get no time to end
- optional handler keys and browser shapes with nil as the default: a forgotten answer shape and a chosen "as it is" read the same
- the error texts as settings: they are the protocol both ends read; an app shows its own words in the browser
