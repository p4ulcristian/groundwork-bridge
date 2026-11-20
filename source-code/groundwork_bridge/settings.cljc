(ns groundwork-bridge.settings
  "bridge's settings, and the data its functions take, as malli shapes,
   checked before anything starts. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once.

   The server's settings and its functions' options are closed maps. A
   handler, a page and a name's shapes are open: the app and the libraries
   on top keep their own keys in them (an access rule beside a handler's
   steps), and bridge requires only its own.

   They are checked by malli itself, not through groundwork-shaped-json: they are values the
   app hands in, not data crossing an edge. The functions that do send or
   read data across an edge take the app's shaper under :shaper in their
   options; bridge never makes one. On the server, the functions that
   answer messages or run the server take the app's log under :log;
   bridge never decides how a line is written."
  (:require
   [groundwork-bridge.answers :as answers]
   #?(:clj [groundwork-bridge.limits :as limits])
   [groundwork-steps.api :as steps]
   [malli.core :as malli]
   [malli.error :as malli-error]))

;; -----------------------------------------------------------------------------
;; The server's settings

(def metric-buckets-entry
  "The upper bounds of bridge's histograms, in seconds, ascending (meter
   checks them): {:answer-seconds [0.005 … 10]}, the time to answer one
   named message. The settings' and answer-message's, the same."
  [:metric-buckets [:map {:closed true}
                    [:answer-seconds [:vector {:min 1} number?]]]])

(def settings-shape
  "The settings, every key required.
   :ip, :port: where the server listens; port 0 picks a free one.
   :proxy-protocol: :disable when browsers or an http proxy connect; :enable
   behind a load balancer that speaks the PROXY protocol (the asker's
   address then comes from it); :optional takes both.
   :client-address-header: behind a proxy, the header it puts the asker's
   address in (\"cf-connecting-ip\", \"x-forwarded-for\"); nil when the
   server faces browsers itself.
   :server-header: what the Server header says, nil for none.
   :max-body-bytes: the largest request body; a bigger one is refused (413).
   :max-request-head-bytes: the largest request line and headers together;
   bigger ones are refused (431; an address too long for it, 414).
   :max-ws-message-bytes: the largest message a browser may send on ws.
   :idle-timeout-ms: how long a connection may pass nothing before it is
   closed, an open sse or ws line too; longer than :ping-every-ms, so a
   line the server pings is never cut.
   :threads: Jetty's own platform threads, which accept connections and
   read and write sockets: {:min … :max … :idle-timeout-ms …}, a thread
   idle that long is ended (down to :min). Every request's work, and every
   ws message's answer, runs on a virtual thread of its own, not on these.
   :addresses: where the three pipes are, {:http … :sse … :ws …}.
   :ping-every-ms: how often an idle sse line gets a comment and a ws line
   {:groundwork-bridge/ping {}}, so proxies don't cut them.
   :sse-reconnect-after-ms: how long the browser waits before it reopens a
   broken sse line; sent on the line as its retry: field when it opens, and
   handed to the browser by the app (open-sse-line!'s :reconnect-after-ms)
   for a line the server answered with an HTTP error, which the browser
   doesn't reopen by itself.
   :ws-reopen-after-ms: how long the browser waits before it reopens a
   broken ws line; the server doesn't read it, the app hands it to the
   browser with the addresses (open-ws-line!'s :reopen-after-ms).
   :stop-wait-ms: how long stop-server! lets running requests finish
   before it cuts them (a slow report needs longer).
   :stop-wait-after-cut-ms: after the cut, how long stop-server! waits for
   the cut requests' threads to end before it returns.
   :rate-limit-buckets-before-cleanup: rate limits keep a bucket per message
   name and asker in memory; above this many, each message first drops the
   buckets that have refilled full.
   :metric-buckets: the upper bounds of bridge's histograms, in seconds,
   ascending (meter checks them): {:answer-seconds [0.005 … 10]}, the time
   to answer one named message. No default: what a handler takes is the
   app's to say."
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:proxy-protocol [:enum :disable :enable :optional]]
   [:client-address-header [:maybe :string]]
   [:server-header [:maybe :string]]
   [:max-body-bytes [:int {:min 1}]]
   [:max-request-head-bytes [:int {:min 1}]]
   [:max-ws-message-bytes [:int {:min 1}]]
   [:idle-timeout-ms [:int {:min 1}]]
   [:threads [:and
              [:map {:closed true}
               [:min [:int {:min 1}]]
               [:max [:int {:min 1}]]
               [:idle-timeout-ms [:int {:min 1}]]]
              [:fn {:error/message "should have :min at most :max"}
               (fn [{:keys [min max]}] (or (not (int? min)) (not (int? max)) (<= min max)))]]]
   [:addresses [:map {:closed true}
                [:http :string]
                [:sse :string]
                [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]
   [:sse-reconnect-after-ms [:int {:min 1}]]
   [:ws-reopen-after-ms [:int {:min 1}]]
   [:stop-wait-ms [:int {:min 0}]]
   [:stop-wait-after-cut-ms [:int {:min 0}]]
   [:rate-limit-buckets-before-cleanup [:int {:min 1}]]
   metric-buckets-entry])

(defn- pings-before-idle-timeout? [{:keys [idle-timeout-ms ping-every-ms]}]
  (or (not (int? idle-timeout-ms)) (not (int? ping-every-ms)) (> idle-timeout-ms ping-every-ms)))

(def ^:private checked-settings-shape
  "The settings, and that a pinged line outlives the idle timeout."
  [:and settings-shape
   [:fn {:error/message "should have :idle-timeout-ms longer than :ping-every-ms, so a pinged line is never cut"
         :error/path    [:idle-timeout-ms]}
    pings-before-idle-timeout?]])

;; -----------------------------------------------------------------------------
;; The server's options and data

(def shaper-entry
  "The app's shaper (groundwork-shaped-json.api's make-shaper-from-settings), made once by the
   app and handed to every function that sends or reads a message."
  [:shaper [some? {:error/message "should be the app's shaper, made by groundwork-shaped-json.api/make-shaper-from-settings"}]])

(def log-entry
  "The app's log function, (log level event data), made by groundwork-logs's
   log-function or with-context: each message gets one with its request id
   as context, and a handler's failure and the server's errors are written
   to it."
  [:log [fn? {:error/message "should be the app's log function, made by groundwork-logs"}]])

(def meter-entry
  "The app's meter, made by groundwork-metrics's make-meter: bridge's metrics are
   declared on it and counted into it. A value make-meter didn't make is
   refused by meter (:groundwork-metrics/not-a-meter)."
  [:meter [some? {:error/message "should be the app's meter, made by groundwork-metrics.api/make-meter"}]])

(def routes-options-shape
  "make-routes-for-messages's options, every key required.
   :shaper: the app's shaper, for every message the three routes read and
   write; each line they open carries it, for send-message-on-sse-line!
   and send-message-on-ws-line!.
   :log: the app's log; each message's steps get one with a new request id
   as context (:groundwork-bridge/log), and a handler's failure is written to it.
   :meter: the app's meter; bridge's metrics are declared on it and
   counted into it.
   :find-rate-limit-key: (fn [request]) telling askers apart for the
   handlers' :rate-limit. The four hooks: each (fn [line]), or nil written
   out when nothing runs then."
  [:map {:closed true}
   shaper-entry
   log-entry
   meter-entry
   [:find-rate-limit-key fn?]
   [:on-sse-open [:maybe fn?]]
   [:on-sse-close [:maybe fn?]]
   [:on-ws-open [:maybe fn?]]
   [:on-ws-close [:maybe fn?]]])

(def rate-limited-options-shape
  "make-rate-limited-ring-handler's options, every key required.
   :rate-limit: how often one asker may ask, {:max 10 :per-ms 60000}.
   :find-rate-limit-key: (fn [request]) telling askers apart, such as
   find-client-address with the settings' :client-address-header.
   :buckets-before-cleanup: above this many askers' buckets in memory, the
   full ones are dropped (the settings' :rate-limit-buckets-before-cleanup,
   say)."
  [:map {:closed true}
   [:rate-limit [:map {:closed true} [:max [:int {:min 1}]] [:per-ms [:int {:min 1}]]]]
   [:find-rate-limit-key fn?]
   [:buckets-before-cleanup [:int {:min 1}]]])

(def ring-handler-options-shape
  "make-ring-handler-from-routes's options. :files: nil for none, or where
   the files a GET no route matches is answered from:
   :folder, the folder on disk;
   :hashed-name-min-hex-digits, how many hex digits a name needs before its
   extension to count as hashed (main.3F9A0C1D.js), a release build's file
   whose name changes with its content;
   :hashed-file-cache-seconds, how long a browser keeps a hashed file
   without asking again.
   :not-found: nil, a request no route (and no file) matches is answered
   404 with no body; or (fn [request]) answering it (the app's not-found
   page)."
  [:map {:closed true}
   [:files [:maybe [:map {:closed true}
                    [:folder :string]
                    [:hashed-name-min-hex-digits [:int {:min 1}]]
                    [:hashed-file-cache-seconds [:int {:min 0}]]]]]
   [:not-found [:maybe fn?]]])

(def answer-shape-entry
  "A handler's or a name's :answer-shape: any malli shape, or nil, that
   doesn't name :groundwork-steps/refused (data that could read as a refusal)."
  [:answer-shape [:fn {:error/message "may not name :groundwork-steps/refused: a refusal is made by groundwork-steps's refuse, and declared in :refusals"}
                  (complement answers/does-shape-name-refused?)]])

(def handler-shape
  "One handler, every key bridge reads required, nil written out where
   nothing applies. :steps, its work, each a step of groundwork-steps's
   (step-shape). :params-shape and :answer-shape, the
   malli shapes its data comes in by and its answer leaves by; nil passes
   it as it is; an :answer-shape may not name :groundwork-steps/refused.
   :refusals, the reasons its steps may refuse with (groundwork-steps's
   refuse), each with the shape of its data, nil for none, {} for no
   reasons; the ones every answer may carry (groundwork-steps's refusals)
   are never declared. :rate-limit, {:max 10
   :per-ms 60000} per asker, nil for none. :run-steps-in, (fn [run
   context]) the steps run inside (a transaction), nil to run them
   plainly."
  [:map
   [:steps [:sequential steps/step-shape]]
   [:params-shape :any]
   answer-shape-entry
   [:refusals answers/refusals-shape]
   [:rate-limit [:maybe [:map {:closed true}
                         [:max [:int {:min 1}]]
                         [:per-ms [:int {:min 1}]]]]]
   [:run-steps-in [:maybe fn?]]])

(def handlers-shape
  "The handler table: message name to handler."
  [:map-of :keyword handler-shape])

#?(:clj
   (def message-limiter-shape
     "answer-message's limiter: :no-rate-limits, written out, for a test or
      the REPL; or a limiter of the caller's (make-message-limiter), which
      counts each handler's :rate-limit as the server's does, in buckets of
      its own."
     [:or
      [:= :no-rate-limits]
      [:fn {:error/message "should be a limiter from make-message-limiter"} limits/is-limiter?]]))

(def message-limiter-options-shape
  "make-message-limiter's options, every key required.
   :find-rate-limit-key: (fn [request]) telling askers apart.
   :buckets-before-cleanup: above this many askers' buckets in memory, the
   full ones are dropped."
  [:map {:closed true}
   [:find-rate-limit-key fn?]
   [:buckets-before-cleanup [:int {:min 1}]]])

(def answer-options-shape
  "answer-message's options, every key required. :shaper: the app's shaper,
   which turns the message's data into shaped data and the answer into
   JSON data, as over http. :log: the app's log, as the routes take it.
   :meter: the app's meter (a test's own in a test), which each named
   message is counted into as the server's http address counts it.
   :metric-buckets: the settings' (the routes' on the same meter), as
   bridge's metrics are declared with them."
  [:map {:closed true}
   shaper-entry
   log-entry
   meter-entry
   metric-buckets-entry])

(def server-options-shape
  "start-server!'s options, every key required. :log: the app's log; a
   failure no step caught is written to it."
  [:map {:closed true}
   log-entry])

(def pages-shape
  "The app's pages by namespaced name. :address, its path with its params
   (\"/orders/:order-id\"); :title, the tab's title while it shows, nil
   when the app sets the title itself."
  [:map-of :qualified-keyword [:map
                               [:address :string]
                               [:title [:maybe :string]]]])

;; -----------------------------------------------------------------------------
;; The browser's options and data

(def shapes-by-name-shape
  "The browser's shapes, by message name: the same three a handler says,
   each written out: :params-shape and :answer-shape, nil where the data
   passes as it is; :refusals, its reasons with their data's shapes, {}
   for none."
  [:map-of :keyword [:map
                     [:params-shape :any]
                     answer-shape-entry
                     [:refusals answers/refusals-shape]]])

(def ^:private headers-bridge-sets
  "The request headers send-message! writes itself: what the body is and
   what the answer must be."
  #{"accept" "content-type"})

(defn- leaves-bridge-headers-alone? [headers]
  (not-any? headers-bridge-sets (keys headers)))

(defn- has-lowercase-names? [headers]
  (every? #(= % (.toLowerCase ^String %)) (keys headers)))

(def ^:private headers-entry
  "More request headers, by lowercase name, {} for none (a write's one-time
   key, say); accept and content-type are bridge's own and refused."
  [:headers [:and
             [:map-of :string :string]
             [:fn {:error/message "header names are written in lowercase"} has-lowercase-names?]
             [:fn {:error/message "accept and content-type are bridge's own"} leaves-bridge-headers-alone?]]])

(def ^:private keep-alive-entry
  "true, the request finishes even when the page closes (fetch's keepalive;
   the browser refuses one whose body, with the other keepalive requests in
   flight, passes 64 KB, which is :groundwork-steps/no-answer); false, a
   plain request."
  [:keep-alive? :boolean])

(def send-options-shape
  "send-message!'s options, every key required. :shaper: the app's shaper,
   made from the browser's settings. :headers and :keep-alive?: as a
   request's options (request-options-shape)."
  [:map {:closed true}
   shaper-entry
   headers-entry
   keep-alive-entry])

(def http-sender-options-shape
  "make-http-sender's options, every key required: what stays the same for
   every request the sender sends. :shaper: the app's shaper, made from the
   browser's settings."
  [:map {:closed true}
   shaper-entry])

(def request-options-shape
  "A request's options, given to an http sender with each message, every key
   required: what may differ from one request to the next. :headers: more
   request headers, by lowercase name, {} for none (a write's one-time key,
   say); accept and content-type are bridge's own and refused.
   :keep-alive?: true, the request finishes even when the page closes
   (fetch's keepalive; the browser refuses one whose body, with the other
   keepalive requests in flight, passes 64 KB, which is
   :groundwork-steps/no-answer); false, a plain request."
  [:map {:closed true}
   headers-entry
   keep-alive-entry])

(def sse-options-shape
  "open-sse-line!'s options, every key required. :shaper: the app's shaper.
   :on-open: (on-open again?)
   each time the line opens, again? true after a break; nil when nothing
   runs then. :on-break: (on-break) once per break, at the first error
   after an open (the network, the server down, a proxy's error), before
   it is back; nil when nothing runs then. :reconnect-after-ms: how long it waits before it opens a new
   line when the server answered with an HTTP error, which the browser
   doesn't reopen by itself (the server's :sse-reconnect-after-ms setting,
   which the app hands to the browser)."
  [:map {:closed true}
   shaper-entry
   [:on-open [:maybe fn?]]
   [:on-break [:maybe fn?]]
   [:reconnect-after-ms [:int {:min 1}]]])

(def ws-options-shape
  "open-ws-line!'s options, every key required. :shaper: the app's shaper; the
   line carries it, for send-message-on-ws-line!. :reopen-after-ms: how
   long it waits before it reopens a broken line."
  [:map {:closed true}
   shaper-entry
   [:reopen-after-ms [:int {:min 1}]]])

;; -----------------------------------------------------------------------------
;; Checking

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn check-settings [settings]
  (check-data-for-shape "The settings" :groundwork-bridge/bad-settings checked-settings-shape settings))

(defn check-routes-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options routes-options-shape options))

(defn check-rate-limited-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options rate-limited-options-shape options))

(defn check-ring-handler-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options ring-handler-options-shape options))

(defn check-handlers [handlers]
  (check-data-for-shape "The handlers" :groundwork-bridge/bad-handlers handlers-shape handlers))

#?(:clj
   (defn check-message-limiter [limiter]
     (check-data-for-shape "The limiter" :groundwork-bridge/bad-options message-limiter-shape limiter)))

(defn check-message-limiter-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options message-limiter-options-shape options))

(defn check-server-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options server-options-shape options))

(defn check-answer-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options answer-options-shape options))

(defn check-pages [pages]
  (check-data-for-shape "The pages" :groundwork-bridge/bad-pages pages-shape pages))

(defn check-shapes-by-name [shapes-by-name]
  (check-data-for-shape "The shapes" :groundwork-bridge/bad-shapes shapes-by-name-shape shapes-by-name))

(defn check-http-sender-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options http-sender-options-shape options))

(defn check-request-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options request-options-shape options))

(defn check-sse-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options sse-options-shape options))

(defn check-send-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options send-options-shape options))

(defn check-ws-options [options]
  (check-data-for-shape "The options" :groundwork-bridge/bad-options ws-options-shape options))
