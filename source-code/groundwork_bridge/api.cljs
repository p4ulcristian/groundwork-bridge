(ns groundwork-bridge.api
  "Named messages come in, a handler's steps answer them, the answers go
   out: how a browser and a server talk, the browser's side. Named messages
   over http, sse and ws. The server's side is this namespace's .clj half;
   an answer is told from a refusal by groundwork-steps's is-error-answer?.

   The addresses and the sse and ws reopen delays come from the server
   (the app puts them in the page); the browser uses the same shapes as
   the server's handlers, by name, each written out (nil: as it is; {}:
   no reasons), from a .cljc file of the app's:

     (def shapes-by-name {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                           :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                                           :refusals     {:shop/closed [:map [:shop/opens-at :time/instant]]}}
                          :sync/changed   {:params-shape nil :answer-shape nil :refusals {}}})

     ;; the app's shaper, made once by the app from the browser's settings (bridge never makes one)
     (def shaper (groundwork-shaped-json.api/make-shaper-from-settings {:most-shapes-kept 1000 :most-keys-kept 10000
                                                  :exact-decimals   groundwork-shaped-json.big/big}))

     ;; made once: the shapes and the shaper checked here, not on each request
     (def send! (bridge/make-http-sender (:http addresses) shapes-by-name {:shaper shaper}))
     (send! {:orders/of-shop {:shop/id id}} {:headers {} :keep-alive? false})   ; a promise of the shaped answer
     (bridge/open-sse-line! (:sse addresses) shapes-by-name {:sync/changed on-change}
                              {:shaper groundwork-shaped-json :on-open nil :on-break nil :reconnect-after-ms sse-reconnect-after-ms})   ; the server's setting, from the page
     (bridge/open-ws-line! (:ws addresses) shapes-by-name {:chat/said on-said}
                             {:shaper groundwork-shaped-json :reopen-after-ms ws-reopen-after-ms})   ; the server's setting, from the page

   Nothing has a default: the shapes and the options are checked first, and
   a missing key refuses, named (:groundwork-bridge/bad-shapes, :groundwork-bridge/bad-options).
   They are checked when they come in: once for a sender or a line, which
   keeps them; on every call for send-message!, which takes them each time.
   Every function that sends or reads a message takes the app's shaper under
   :shaper; a ws line keeps it for send-message-on-ws-line!."
  (:require
   [groundwork-bridge.http :as http]
   [groundwork-bridge.sse :as sse]
   [groundwork-bridge.ws :as ws]))

(defn send-message!
  "http: POSTs a named message, its data by :params-shape. A promise of the
   answer keyed by the same names, each shaped by its :answer-shape or a
   refusal, its data by its reason's shape, and :groundwork-steps/notes beside them
   when the server sent notes. It never fails for want of an answer: no
   response (the network), or one that isn't the server's JSON (a proxy's
   502, a 413), is {:groundwork-steps/refused :groundwork-steps/no-answer :groundwork-steps/data
   {:http-status n}} under each name, n nil when no response came. Nor
   does it throw for params that don't fit: a name whose params don't fit
   its :params-shape (or carry a key it doesn't name) isn't sent, and is
   answered {:groundwork-steps/refused :groundwork-steps/invalid
   :groundwork-steps/data {:errors …}}, as the server would answer it,
   with no request id; the page's mistake is reported as the browser
   reports an uncaught error (reportError: the console, and the page's
   error listeners). The names that fit are sent. A name the shapes don't
   have throws (:groundwork-bridge/no-shapes); nothing is sent.
   Options, every key required: :shaper, the app's shaper; :headers, more
   request headers by lowercase name, {} for none (accept and
   content-type are bridge's own); :keep-alive?, true for a request that
   finishes after the page closes (fetch's keepalive: the browser refuses
   a body that, with the other keepalive requests in flight, passes 64 KB,
   and that is :groundwork-steps/no-answer), false for a plain one. A missing or
   bad option refuses, :groundwork-bridge/bad-options."
  [address shapes-by-name message options]
  (http/send-message! address shapes-by-name message options))

(defn make-http-sender
  "http: a sender made once, (send! message request-options), sending as
   send-message! does: the same answers, the same refusals, the same report
   of params that don't fit. The address, the shapes and the shaper are
   given here and kept; the shapes and these options are checked here,
   once, where send-message! checks them on every call (the whole shapes
   map, every name's shapes, each time). Each send checks only its own
   request options, every key required: :headers, more request headers by
   lowercase name, {} for none; :keep-alive?, as send-message!'s. Options
   here, every key required: :shaper, the app's shaper. A missing or bad
   option refuses, :groundwork-bridge/bad-options; bad shapes,
   :groundwork-bridge/bad-shapes."
  [address shapes-by-name options]
  (http/make-http-sender address shapes-by-name options))

(defn open-sse-line!
  "sse: opens the stream; each named message, shaped by its :answer-shape,
   goes to its handler by name. It stays open until close-sse-line!: after
   a break in the network the browser reopens it, after the server's
   :sse-reconnect-after-ms (sent on the line); after an HTTP error (a
   proxy's 502 during a deploy), which the browser doesn't reopen, a new
   line opens after :reconnect-after-ms, and again after each failure.
   Options, every key required: :shaper, the app's shaper, which each message
   is read by. :on-open, (on-open again?) each time it
   opens, or nil; again? is true after a break, when messages may have
   been missed. :on-break, (on-break) once per break, at the first error
   after an open, or nil: with :on-open's again?, the line's status (open,
   broken, back) for an offline banner. :reconnect-after-ms, the server's :sse-reconnect-after-ms
   setting, which the app puts in the page with the addresses. A missing
   or bad option refuses, :groundwork-bridge/bad-options."
  [address shapes-by-name handlers options]
  (sse/open-sse-line! address shapes-by-name handlers options))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens the websocket; each named message, answers included, shaped by
   its :answer-shape, goes to its handler by name. Options, every key
   required: :shaper, the app's shaper, which the line keeps for
   send-message-on-ws-line!; :reopen-after-ms, how long it waits before it reopens after a
   break (the server's :ws-reopen-after-ms setting, which the app puts in
   the page with the addresses). A missing or bad option refuses,
   :groundwork-bridge/bad-options."
  [address shapes-by-name handlers options]
  (ws/open-ws-line! address shapes-by-name handlers options))

(defn send-message-on-ws-line!
  "ws: sends a named message on the line, its data by :params-shape,
   written by the line's groundwork-shaped-json. Whether it was open.
   Params that don't fit their shape throw (:groundwork-bridge/bad-params):
   nothing is sent."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))
