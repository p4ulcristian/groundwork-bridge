# bridge

Named messages come in, a handler's steps answer them, the answers go out:
how a browser and a Clojure server talk. A message is a map of names to
data, `{:orders/of-shop {:shop/id 7}}`; the server answers it from one
handler table, by name, over three pipes: **http** (a POST, answered at
once), **sse** (the server stream, to the browser) and **ws** (a websocket,
both ways). A handler's work is its **steps**, a list over one map, the
context, run until one adds an answer (the steps, their context's keys and
the refusal's shape are [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps)'s).
Each handler may say its shapes, so ids, decimals and dates arrive as real
types and an answer leaves only as its shape allows; a rate limit per
asker; and every page answered by the server when it is opened fresh. The
browser's following of the address bar is groundwork-screens's.

```clojure
io.github.p4ulcristian/groundwork-bridge {:git/tag "v0.7.13" :git/sha "bb3b33f"}
```

Needs Jetty 12 through [rj9a](https://github.com/sunng87/ring-jetty9-adapter)
(no servlet layer; [why](decisions/2026-10-23-the-server-is-jetty-12-through-rj9a.md)),
reitit-ring (the server's routes), [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) (a handler's steps and the answer's shape, both sides) and [groundwork-shaped-json](https://github.com/p4ulcristian/groundwork-shaped-json) (data keeps its
types across the edge), and on the server
[groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) (the app's log, handed
down) and [groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics) (the app's
metrics, handed in). One public namespace, `groundwork-bridge.api`: a `.clj` for the server, a
`.cljs` for the browser.

The app makes its shaper once, on each side, and hands it in: every function
that sends or reads a message takes it under `:shaper` in its options
(`make-routes-for-messages`, `answer-message`; in the browser
`send-message!`, `open-sse-line!`, `open-ws-line!`), and a line keeps the
one it was opened with. bridge never makes a shaper.

So with the log, on the server: `make-routes-for-messages` and
`answer-message` take the app's `(log level event data)` under `:log`,
`start-server!` in a new options map, `{:log log}`. Each message gets a log
with a new random `{:request-id "…"}` as its context; every step's context
holds it at `:groundwork-steps/log`, and the app's shaper at `:groundwork-steps/shaper`, beside
the request, the message's name and its data (`:groundwork-steps/request`,
`:groundwork-steps/message-name`, `:groundwork-steps/message-data`). A step
that learns more (who is asking) puts a log with more context back at
`:groundwork-steps/log` for the steps after it. A handler that throws is written
there, `:groundwork-bridge/handler-failed`; a failure no step caught goes to
`start-server!`'s log, `:groundwork-bridge/server-error`
([why](decisions/2026-10-22-the-app-passes-its-log.md)). Jetty's own lines go
to SLF4J, to whatever the app puts behind it.

A step answers with data, or refuses with a named reason its handler
declares, with data by the reason's shape
([why](decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md)):

```clojure
{:orders/ship {… :refusals {:order/already-shipped [:map [:order/shipped-at :time/instant]]
                            :order/no-address      nil}}}            ; nil: no data; {} for no reasons

(steps/refuse context :order/already-shipped {:order/shipped-at shipped-at})
;; on the groundwork-shaped-json, over http, sse and ws alike, with the message's request id:
{:orders/ship {:groundwork-steps/refused :order/already-shipped :groundwork-steps/data {:order/shipped-at "…"}
               :groundwork-steps/request-id "5f0c…"}}
```

The reason is the key of its text in the app's catalogs and its data the
text's arguments; no English travels. The reasons every answer may carry
besides its handler's own are groundwork-steps's `refusals`, which bridge
gives: `:groundwork-steps/too-many {:retry-after-ms n}` (over the
`:rate-limit`), `:groundwork-steps/invalid {:errors …}` (params that don't fit),
`:groundwork-steps/failed` (a bug: a step threw, no step answered, an answer or a
refusal's data didn't fit its shape, a reason the handler doesn't
declare, a name no handler has), and in the browser `:groundwork-steps/no-answer
{:http-status n}` and `:groundwork-steps/unfit {:errors …}`. Every refusal is
written to the message's log, `:info :groundwork-bridge/refused`; every bug with its
details, `:error` (a name no handler has, `:warn`); either is found by the
refusal's request id. A POST carrying several names stays 200.

And with the meter: `make-routes-for-messages` takes the app's meter under
`:meter` and declares bridge's metrics on it
([why](decisions/2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md)):
`groundwork_bridge_messages_total{name,pipe,outcome}`, each named message answered
on http or ws (`ok`, `invalid`, `refused`, `too-many`, `failed`, `unfit`,
`no-handler`); `groundwork_bridge_answer_seconds{name}`, its time, in the settings'
`:metric-buckets`; `groundwork_bridge_open_lines{pipe}`, the open sse and ws lines.
The names are the handler table's: one no handler has is counted as
`"other"`. `answer-message` takes the meter too, with the settings'
`:metric-buckets`, and counts each message it answers exactly as the same
message sent to the http address, pipe `http`: a token's call answered on
the app's own route, the REPL's, a test's on a meter of its own
([why](decisions/2026-10-23-every-answered-message-is-counted-on-the-meter-whichever-door.md)).

## The api

| side | name | does |
|---|---|---|
| server | `settings-shape` | the settings as a malli shape, every key required |
| server | `make-routes-for-messages` | the three routes, at the settings' addresses, from the handler table, by the options' `:shaper`, `:log` and `:meter`; a handler's `:rate-limit` counted per asker |
| server | `make-server-routes-for-pages` `make-ring-handler-from-routes` | each page answered when opened fresh; the one Ring handler, files from `:files` (nil for none; a file's `.gz` copy to a request whose Accept-Encoding lists gzip, else `*`, with a quality above 0, [why](decisions/2026-10-27-accept-encoding-is-read-by-its-grammar.md)), what no route answers from `:not-found` (nil: 404; [why](decisions/2026-10-23-not-found-is-the-apps.md)) |
| server | `start-server!` `stop-server!` | Jetty 12, every request on a virtual thread; `{:log log}`; a stop closes the sse lines, lets running requests finish, then cuts |
| server | `send-message-on-sse-line!` `send-message-on-ws-line!` `is-line-open?` `close-line!` | the server sends on an open line by itself, by the shaper the line keeps, or closes it; a line the browser left is closed when its socket ends |
| server | `answer-message` | a message's answer without a server: tests, the REPL (`:no-rate-limits`, written out; the request nil for a message that came with none), or a message answered off the server's addresses (a limiter from `make-message-limiter`); `{:wire groundwork-shaped-json :log log :meter meter :metric-buckets (:metric-buckets settings)}`, counted on the meter as over http |
| server | `make-message-limiter` | a limiter for `answer-message`: each handler's `:rate-limit` counted per asker, by the caller's `:find-rate-limit-key`, in buckets of its own, not the server's ([why](decisions/2026-10-23-a-message-answered-off-the-servers-addresses-is-limited-by-the-callers-limiter.md)) |
| server | `find-client-address` | the asker's address, behind a proxy too |
| server | `make-rate-limited-ring-handler` | a plain route (not a message) behind its own limiter, the same buckets, `429` over it ([why](decisions/2026-10-23-a-plain-route-is-rate-limited-by-the-same-buckets.md)) |
| browser | `send-message!` | http: a promise of the answer, shaped, by the options' `:shaper`, with the options' `:headers` beside bridge's own (`{}` for none), and `:keep-alive?`, true for a request that finishes after the page closes (fetch's keepalive); it never fails for want of an answer: no response, or one that isn't the server's JSON, is `:groundwork-steps/no-answer {:http-status n}` under each name |
| browser | `open-sse-line!` `close-sse-line!` `open-ws-line!` `send-message-on-ws-line!` `close-ws-line!` | sse and ws lines, reopened after a break until closed (sse: after `:reconnect-after-ms`, given, also when the server answered with an HTTP error; ws: after `:reopen-after-ms`, given); an sse line says when it opens (`:on-open`, `again?` after a break) and when it breaks (`:on-break`, once per break), the line's status for an offline banner |

Nothing has a default: every value that sets behaviour is written out,
the settings, every option, every key of a handler, a page and a name's
shapes (nil where nothing applies, `{}` for no reasons); a missing one refuses, named, with
every other problem at once. No function fills in an argument. What stays
fixed, and why: [the decision](decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

```clojure
(require '[groundwork-logs.api :as logs]
         '[groundwork-bridge.api :as bridge]
         '[groundwork-steps.api :as steps]
         '[groundwork-metrics.api :as metrics]
         '[groundwork-shaped-json.api :as shaped-json])

;; the app's shaper, made once (groundwork-shaped-json's settings, every key written out)
(def shaper
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

;; the app's log, made once (groundwork-logs's settings, every key written out)
(def log (logs/log-function (logs/make-logger {:lowest-level :info :format :json
                                                      :errors-kept 1000 :write-to :stdout})))

;; the app's meter, made once (groundwork-metrics's settings, every key written out)
(def meter (metrics/make-meter {:jvm-metrics true :most-series-per-metric 5000} {:log log}))

(def settings
  {:ip "0.0.0.0" :port 8080
   :proxy-protocol :disable                            ; :enable behind a load balancer speaking PROXY
   :client-address-header "x-forwarded-for"            ; nil when no proxy is in front
   :server-header nil                                  ; the Server header, nil for none
   :max-body-bytes 8388608                             ; a bigger request body is refused, 413
   :max-request-head-bytes 8192                        ; a bigger request line and headers, 431 (a longer address, 414)
   :max-ws-message-bytes 4194304                       ; the largest message a browser sends on ws
   :idle-timeout-ms 60000                              ; a connection silent this long is closed; longer than :ping-every-ms
   :threads {:min 8 :max 50 :idle-timeout-ms 60000}    ; Jetty's own platform threads; requests run on virtual ones
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000                                ; so proxies don't cut idle lines
   :sse-reconnect-after-ms 3000                        ; the browser reopens a broken sse line after this (sent as retry:)
   :ws-reopen-after-ms 1000                            ; the browser reopens a broken ws line after this
   :stop-wait-ms 10000                                 ; a stop lets running requests finish this long
   :stop-wait-after-cut-ms 1000                        ; then waits this long for the cut ones to end
   :rate-limit-buckets-before-cleanup 10000            ; above this many buckets, the full ones are dropped
   :metric-buckets {:answer-seconds [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}})   ; seconds to answer a name

(def find-orders-of-shop
  {:name  :find-orders-of-shop
   :enter (fn [{:groundwork-steps/keys [log message-data] :as context}]   ; log: the message's, with its request id
            (log :debug :orders/finding {:shop/id (:shop/id message-data)})
            (assoc context :orders (find-orders db (:shop/id message-data))))})

(def add-orders-answer
  {:name  :add-orders-answer
   :enter #(steps/add-answer-to-context % (:orders %))})

(def handlers
  {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]          ; nil: the data passes as it is
                    :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                    :refusals     {:shop/closed nil}               ; reasons a step may refuse with; {} for none
                    :rate-limit   {:max 10 :per-ms 60000}          ; nil for none
                    :run-steps-in nil                              ; (fn [run context]), e.g. a transaction
                    :steps        [find-orders-of-shop add-orders-answer]}})

(def pages
  {:orders/page {:address "/orders/:order-id" :title "Order"}})   ; :title nil: the app sets it

(def server
  (bridge/start-server!
   (bridge/make-ring-handler-from-routes
    (into (bridge/make-routes-for-messages
           handlers settings
           {:shaper                shaper
            :log                 log            ; the app's (groundwork-logs's): each message's steps get one with a request id
            :meter               meter          ; the app's (groundwork-metrics's): bridge's metrics are declared on it here
            :find-rate-limit-key #(bridge/find-client-address % (:client-address-header settings))
            :on-sse-open         remember-line!
            :on-sse-close        forget-line!   ; once a line; a browser gone is seen when its socket ends
            :on-ws-open          nil
            :on-ws-close         nil})
          (bridge/make-server-routes-for-pages pages page-handler))
    {:files {:folder                     "target/public"
             :hashed-name-min-hex-digits 8           ; main.3F9A0C1D.js is hashed
             :hashed-file-cache-seconds  31536000}   ; and kept a year; any other file: no-cache
     :not-found nil})                             ; or (fn [request]): what no route and no file answers
   settings
   {:log log}))                                   ; failures no step caught

;; the browser, with the same shapes from a .cljc file, both written out for
;; every name it sends or gets (an answer told from a refusal by groundwork-steps); the addresses, :sse-reconnect-after-ms and
;; :ws-reopen-after-ms come from the server, in the page; the browser's own shaper
(def shaper
  (shaped-json/make-shaper-from-settings {:most-shapes-kept 1000
                                 :most-keys-kept   10000
                                 :exact-decimals   groundwork-shaped-json.big/big}))   ; or nil: a decimal stays its text
(def shapes-by-name
  {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                    :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                    :refusals     {:shop/closed nil}}
   :chat/said      {:params-shape nil :answer-shape nil :refusals {}}})

(-> (bridge/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id shop-id}} {:shaper shaper :headers {} :keep-alive? false})
    (.then (fn [{:orders/keys [of-shop]}]
             (if (steps/is-error-answer? of-shop)
               (show-refusal! (:groundwork-steps/refused of-shop) (:groundwork-steps/data of-shop))   ; the reason is the text's key
               (show-orders! of-shop)))))

(bridge/open-sse-line! (:sse addresses) shapes-by-name {:chat/said show-said!}
                       {:shaper               shaper
                        :on-open            nil            ; or (fn [again?]), again? after a break: ask what you missed
                        :on-break           nil            ; or (fn []), once per break: show the line is down
                        :reconnect-after-ms sse-reconnect-after-ms})
(bridge/open-ws-line! (:ws addresses) shapes-by-name {:chat/said show-said!}
                      {:shaper shaper :reopen-after-ms ws-reopen-after-ms})
```

## Tests

```sh
clojure -X:test                              # the server, on the JVM
npm install && npx shadow-cljs compile test  # the browser, on node
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
