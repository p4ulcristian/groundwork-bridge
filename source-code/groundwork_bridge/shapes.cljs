(ns groundwork-bridge.shapes
  "The same shapes as the server's handlers, by name, in the browser:
   {:orders/of-shop {:params-shape … :answer-shape … :refusals {…}}}, each
   written out, nil where the data passes as it is, {} for no reasons. A
   message's data goes out as JSON data by its :params-shape; an answer
   comes back as shaped data by its :answer-shape, a refusal with its data
   by its reason's shape (the name's :refusals, or groundwork-steps's refusals). A name
   the table doesn't have is a mistake: going out it throws, nothing is
   sent; coming in it is refused :groundwork-steps/unfit, as an answer or a refusal
   that doesn't fit. groundwork-shaped-json is the app's, which every shape is used by."
  (:require
   [groundwork-bridge.answers :as answers]
   [groundwork-shaped-json.api :as shaped-json]
   [groundwork-steps.api :as steps]))

(defn- turn-each-name [message turn]
  (into {} (map (fn [[message-name data]] [message-name (turn message-name data)])) message))

(defn- find-shapes-of-name
  "A name's shapes; a name the table doesn't have throws: nothing is sent."
  [shapes message-name]
  (when-not (contains? shapes message-name)
    (throw (ex-info (str "No shapes named " message-name)
                    {:type :groundwork-bridge/no-shapes :message-name message-name})))
  (get shapes message-name))

(defn turn-message-into-json-data-or-errors
  "Each name's data as JSON data, by its :params-shape: {:json-data
   message :errors errors}, the message of the names whose data fits
   (a name whose shape is nil passes as it is) and, by name, why each
   other's doesn't (a key its shape doesn't name among them), {} when
   every name's fits. A name without shapes throws
   (:groundwork-bridge/no-shapes): nothing is sent."
  [shaper shapes message]
  (reduce (fn [turned [message-name data]]
            (if-let [shape (:params-shape (find-shapes-of-name shapes message-name))]
              (let [{:keys [json-data errors]} (shaped-json/turn-shaped-data-into-json-data-or-errors shaper shape data)]
                (if errors
                  (assoc-in turned [:errors message-name] errors)
                  (assoc-in turned [:json-data message-name] json-data)))
              (assoc-in turned [:json-data message-name] data)))
          {:json-data {} :errors {}}
          message))

(defn turn-message-into-json-data
  "Each name's data as JSON data, by its :params-shape. A name without
   shapes (:groundwork-bridge/no-shapes), or data that doesn't fit its
   shape or carries a key it doesn't name (:groundwork-bridge/bad-params,
   with the first such name and why), throws: nothing is sent. The ws
   line's send; http's answers a misfit as a refusal instead
   (make-bad-params-refusals)."
  [shaper shapes message]
  (let [{:keys [json-data errors]} (turn-message-into-json-data-or-errors shaper shapes message)]
    (when-let [[message-name name-errors] (first errors)]
      (throw (ex-info (str message-name "'s params don't fit its shape: " (pr-str name-errors))
                      {:type         :groundwork-bridge/bad-params
                       :message-name message-name
                       :errors       name-errors})))
    json-data))

(defn make-bad-params-refusals
  "The browser's own answer to each name whose params don't fit its
   :params-shape, as the server would have answered it, so nothing waits
   for an answer that can't come: :groundwork-steps/invalid {:errors …},
   with no request id (nothing was sent). errors: {name errors}."
  [errors]
  (update-vals errors #(steps/make-refusal :groundwork-steps/invalid {:errors %})))

(defn report-bad-params!
  "A name whose params don't fit is the page's mistake: each is reported
   as the browser reports an uncaught error (reportError: the console
   shows it, and the page's error listeners hear it, an app's error list
   among them), though nothing throws; the console's error where the
   browser has no reportError. errors: {name errors}."
  [errors]
  (doseq [[message-name name-errors] errors]
    (let [error (ex-info (str message-name "'s params don't fit its shape: " (pr-str name-errors))
                         {:type :groundwork-bridge/bad-params :message-name message-name :errors name-errors})]
      (if (exists? js/reportError)
        (js/reportError error)
        (js/console.error error)))))

(defn- make-unfit
  "The browser's own refusal of an answer that doesn't fit: :groundwork-steps/unfit
   with why, and the server's request id when it sent one."
  [errors request-id]
  (cond-> (steps/make-refusal :groundwork-steps/unfit {:errors errors})
    request-id (assoc :groundwork-steps/request-id request-id)))

(defn- turn-refusal-into-shaped-data
  "A refusal from the server, its reason a keyword again and its data
   shaped by the reason's shape: the name's :refusals, or groundwork-steps's refusals. A
   reason neither declares, or data that doesn't fit, :groundwork-steps/unfit."
  [shaper name-refusals {reason :groundwork-steps/refused data :groundwork-steps/data request-id :groundwork-steps/request-id}]
  (let [reason   (if (string? reason) (keyword reason) reason)
        refusals (merge steps/refusals name-refusals)]
    (if-not (and (keyword? reason) (contains? refusals reason))
      (make-unfit [(str "No refusal named " reason)] request-id)
      (let [turned (answers/turn-refusal-data-in shaper (get refusals reason) data)]
        (if (contains? turned :errors)
          (make-unfit (:errors turned) request-id)
          (cond-> (steps/make-refusal reason (:data turned))
            request-id (assoc :groundwork-steps/request-id request-id)))))))

(defn turn-answer-into-shaped-data
  "Each name's answer as shaped data, by its :answer-shape. A refusal
   (:groundwork-steps/refused) is shaped by its reason's shape instead: the server
   sends it past the :answer-shape. An answer or a refusal that doesn't
   fit, or whose name has no shapes, is refused :groundwork-steps/unfit, with why.
   :groundwork-steps/notes (groundwork-steps's, beside the answers) passes as it is."
  [shaper shapes answer]
  (turn-each-name answer (fn [message-name data]
                           (cond
                             (= :groundwork-steps/notes message-name)
                             data

                             (not (contains? shapes message-name))
                             (make-unfit [(str "No shapes named " message-name)] nil)

                             (steps/is-error-answer? data)
                             (turn-refusal-into-shaped-data shaper (get-in shapes [message-name :refusals]) data)

                             :else
                             (if-let [shape (get-in shapes [message-name :answer-shape])]
                               (let [{:keys [shaped-data errors]} (shaped-json/turn-json-data-into-shaped-data-or-errors shaper shape data)]
                                 (if errors (make-unfit errors nil) shaped-data))
                               data)))))

(defn make-no-answer
  "The answer to a message that got none, the browser's own refusal under
   each of its names: :groundwork-steps/no-answer with the HTTP status, nil when no
   response came."
  [message http-status]
  (update-vals message (fn [_] (steps/make-refusal :groundwork-steps/no-answer {:http-status http-status}))))
