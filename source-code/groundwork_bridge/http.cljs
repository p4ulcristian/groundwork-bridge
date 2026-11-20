(ns groundwork-bridge.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names, each turned into shaped data by its shape."
  (:require
   [groundwork-bridge.settings :as settings]
   [groundwork-bridge.shapes :as shapes]
   [groundwork-shaped-json.api :as shaped-json]))

(defn- make-fetch-options
  "The addresses are paths on the page's own origin, and the server sends
   no CORS headers: a request goes there only, with the page's cookie. An
   answer is never kept (the server says no-store too); a redirect in front
   of the server (a proxy) is followed. The options' :headers go beside
   bridge's own two; :keep-alive? is fetch's keepalive, the request
   finishing after the page has closed."
  [shaper headers keep-alive? message]
  #js {:method      "POST"
       :mode        "same-origin"
       :credentials "same-origin"
       :cache       "no-store"
       :redirect    "follow"
       :keepalive   keep-alive?
       :headers     (clj->js (merge headers {"accept" "application/json" "content-type" "application/json"}))
       :body        (shaped-json/turn-data-into-json-text shaper message)})

(defn- read-answer
  "The answer in a response: shaped, when the server answered 200 with a
   JSON object; else :groundwork-steps/no-answer under each name, with the status (a
   proxy's 502, a 413, a page that isn't JSON)."
  [shaper shapes-by-name message status text]
  (let [data (when (= 200 status)
               (try (shaped-json/turn-json-text-into-data shaper text)
                    (catch :default _ nil)))]
    (if (map? data)
      (shapes/turn-answer-into-shaped-data shaper shapes-by-name data)
      (shapes/make-no-answer message status))))

(defn- post-message!
  "POSTs the JSON data of a message; a promise of its answer, shaped, or
   :groundwork-steps/no-answer under each of its names."
  [address shapes-by-name message json-data {:keys [shaper headers keep-alive?]}]
  (-> (js/fetch address (make-fetch-options shaper headers keep-alive? json-data))
      (.then (fn [response]
               (-> (.text response)
                   (.then #(read-answer shaper shapes-by-name message (.-status response) %)
                          (fn [_] (shapes/make-no-answer message (.-status response))))))
             (fn [_] (shapes/make-no-answer message nil)))))

(defn- send-checked-message!
  "send-message!'s work once the shapes and options are known good: the
   params checked against each name's :params-shape, the names that fit
   sent, the misfits answered :groundwork-steps/invalid and reported."
  [address shapes-by-name message options]
  (let [{:keys [json-data errors]} (shapes/turn-message-into-json-data-or-errors (:shaper options) shapes-by-name message)
        refusals                  (shapes/make-bad-params-refusals errors)]
    (shapes/report-bad-params! errors)
    (-> (if (and (empty? json-data) (seq errors))
          (js/Promise.resolve {})
          (post-message! address shapes-by-name (select-keys message (keys json-data)) json-data options))
        (.then #(merge % refusals)))))

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the address. A promise of the answer,
   {:orders/of-shop …}: shaped data by the shapes, or a refusal, through
   the options' :shaper; it never fails for want of an answer: no response
   (the network) or one that isn't the server's JSON is :groundwork-steps/no-answer
   under each name. A name whose params don't fit its :params-shape isn't
   sent: it is answered :groundwork-steps/invalid {:errors …} here, as the
   server would answer it, and reported (shapes/report-bad-params!); the
   names that fit are sent, and when none does nothing is. The shapes and
   the options are checked first, on every call, and a name without shapes
   isn't sent."
  [address shapes-by-name message options]
  (settings/check-shapes-by-name shapes-by-name)
  (send-checked-message! address shapes-by-name message (settings/check-send-options options)))

(defn make-http-sender
  "An http sender: (send! message request-options), send-message! with the
   address, the shapes and the shaper given once. The shapes and the
   options are checked here, once; each send checks only its own
   request-options and its params."
  [address shapes-by-name options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [shaper]} (settings/check-http-sender-options options)]
    (fn send! [message request-options]
      (let [{:keys [headers keep-alive?]} (settings/check-request-options request-options)]
        (send-checked-message! address shapes-by-name message
                               {:shaper shaper :headers headers :keep-alive? keep-alive?})))))
