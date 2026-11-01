(ns groundwork-bridge.shapes-test
  "The browser's side, on node: a message goes out by its :params-shape, an
   answer comes back by its :answer-shape, and a named message finds its
   handler. Requiring groundwork-bridge.api proves the whole browser side compiles."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [groundwork-bridge.api]
   [groundwork-bridge.messages :as messages]
   [groundwork-bridge.shapes :as shapes]
   [groundwork-bridge.test-shaper :refer [shaper]]
   [groundwork-shaped-json.time]
   [groundwork-steps.api :as steps]))

(def ^:private shapes-by-name
  {:shops/total     {:params-shape [:map [:shop/id :uuid]]
                     :answer-shape [:map [:shop/id :uuid] [:total :decimal]]
                     :refusals     {:shop/closed [:map [:shop/opens-at :time/instant]]
                                    :shop/gone   nil}}
   :greetings/hello {:params-shape nil :answer-shape nil :refusals {}}})

(defn- find-problems [f]
  (try (f) nil
       (catch :default e (ex-data e))))

(def ^:private shop-id "1d000000-0000-4000-8000-000000000001")

(deftest a-message-goes-out-by-its-shape
  (is (= {:shops/total {:shop/id shop-id}}
         (shapes/turn-message-into-json-data shaper shapes-by-name {:shops/total {:shop/id shop-id}})))
  (testing "data that doesn't fit: nothing is sent"
    (is (= :groundwork-bridge/bad-params
           (try (shapes/turn-message-into-json-data shaper shapes-by-name {:shops/total {:shop/id shop-id :extra 1}})
                (catch :default e (:type (ex-data e)))))))
  (testing "a name whose shape is nil, written out, passes as it is"
    (is (= {:greetings/hello {:name "Anna"}}
           (shapes/turn-message-into-json-data shaper shapes-by-name {:greetings/hello {:name "Anna"}}))))
  (testing "a name the shapes don't have: nothing is sent"
    (is (= :groundwork-bridge/no-shapes
           (:type (find-problems #(shapes/turn-message-into-json-data shaper shapes-by-name {:nobody/home {}})))))))

(deftest an-answer-comes-back-by-its-shape
  (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id shop-id :total "120.50"}})]
    (is (= {:shops/total {:shop/id shop-id :total "120.50"}} answer)
        "fits its shape; in the browser an id and a decimal are their text (groundwork-shaped-json)"))
  (testing "an answer that doesn't fit: :groundwork-steps/unfit, with why"
    (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id "seven" :total "1"}})]
      (is (= :groundwork-steps/unfit (get-in answer [:shops/total :groundwork-steps/refused])))
      (is (contains? (get-in answer [:shops/total :groundwork-steps/data :errors]) :shop/id))))
  (testing "a refusal passes the answer shape: its reason a keyword, its data by its reason's shape"
    (let [{:shops/keys [total]} (shapes/turn-answer-into-shaped-data
                                 shaper shapes-by-name
                                 {:shops/total {:groundwork-steps/refused "shop/closed" :groundwork-steps/data {:shop/opens-at "2026-10-24T08:00:00Z"}
                                                :groundwork-steps/request-id "r-1"}})]
      (is (= [:shop/closed "r-1"] [(:groundwork-steps/refused total) (:groundwork-steps/request-id total)]))
      (is (instance? groundwork-shaped-json.time/Instant (get-in total [:groundwork-steps/data :shop/opens-at])) "shaped, as data is"))
    (is (= {:shops/total {:groundwork-steps/refused :shop/gone :groundwork-steps/data nil :groundwork-steps/request-id "r-2"}}
           (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:groundwork-steps/refused "shop/gone" :groundwork-steps/data nil :groundwork-steps/request-id "r-2"}})))
    (is (= {:shops/total {:groundwork-steps/refused :groundwork-steps/invalid :groundwork-steps/data {:errors {:shop/id ["should be an id"]}} :groundwork-steps/request-id "r-3"}}
           (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:groundwork-steps/refused "groundwork-steps/invalid"
                                                                                   :groundwork-steps/data {:errors {:shop/id ["should be an id"]}}
                                                                                   :groundwork-steps/request-id "r-3"}}))
        "groundwork-steps's reasons, for every name"))
  (testing "a reason the name doesn't declare, or data that doesn't fit: :groundwork-steps/unfit, the server's request id kept"
    (doseq [refusal [{:groundwork-steps/refused "shop/burned" :groundwork-steps/data nil :groundwork-steps/request-id "r-4"}
                     {:groundwork-steps/refused "shop/closed" :groundwork-steps/data {:shop/opens-at "soon"} :groundwork-steps/request-id "r-4"}
                     {:groundwork-steps/refused "shop/gone" :groundwork-steps/data {:why "x"} :groundwork-steps/request-id "r-4"}]]
      (is (= [:groundwork-steps/unfit "r-4"]
             ((juxt :groundwork-steps/refused :groundwork-steps/request-id) (:shops/total (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total refusal}))))
          (pr-str refusal))))
  (testing "a name the shapes don't have: :groundwork-steps/unfit; notes pass"
    (let [answer (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:nobody/home {:a 1} :groundwork-steps/notes {:x 1}})]
      (is (= {:groundwork-steps/refused :groundwork-steps/unfit :groundwork-steps/data {:errors ["No shapes named :nobody/home"]}} (:nobody/home answer)))
      (is (= {:x 1} (:groundwork-steps/notes answer))))))

(deftest a-named-message-finds-its-handler
  (let [heard (atom [])]
    (messages/hand-named-messages! {:sync/changed #(swap! heard conj %)} {:sync/changed {:table "orders"}})
    (is (= [{:table "orders"}] @heard))))

(deftest a-ws-line-is-told-when-to-reopen
  (let [problems (find-problems #(groundwork-bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-options (:type problems)) "no :reopen-after-ms: refused before any socket opens")
    (is (contains? (:errors problems) :reopen-after-ms))))

(deftest an-sse-line-says-its-on-open
  (let [problems (find-problems #(groundwork-bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-options (:type problems)) "no :on-open, not even nil: refused before the line opens")
    (is (contains? (:errors problems) :on-open))))

(deftest each-name-says-its-shapes-and-reasons
  (let [problems (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:shops/total {:params-shape nil}} {:shops/total {}} {:shaper shaper}))]
    (is (= :groundwork-bridge/bad-shapes (:type problems)) "refused before anything is sent")
    (is (= #{:answer-shape :refusals} (set (keys (:shops/total (:errors problems)))))))
  (testing "an answer shape naming :groundwork-steps/refused, and groundwork-steps's reasons, are refused"
    (is (= :groundwork-bridge/bad-shapes (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape [:map [:groundwork-steps/refused :string]] :refusals {}}}
                                                                               {:t/x {}} {:shaper shaper :headers {} :keep-alive? false})))))
    (is (= :groundwork-bridge/bad-shapes (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" {:t/x {:params-shape nil :answer-shape nil :refusals {:groundwork-steps/failed nil}}}
                                                                               {:t/x {}} {:shaper shaper :headers {} :keep-alive? false})))))))

(deftest every-line-and-message-takes-the-apps-shaper
  (doseq [[what call] {"send-message!" #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {})
                       "open-sse-line!" #(groundwork-bridge.api/open-sse-line! "/mail/sse" shapes-by-name {} {:on-open nil :reconnect-after-ms 20})
                       "open-ws-line!" #(groundwork-bridge.api/open-ws-line! "/mail/ws" shapes-by-name {} {:reopen-after-ms 20})}]
    (let [problems (find-problems call)]
      (is (= :groundwork-bridge/bad-options (:type problems)) (str what ": refused before anything is sent or opened"))
      (is (contains? (:errors problems) :shaper) (str what ": bridge makes no shaper")))))

(deftest more-headers-go-with-a-message
  (testing ":headers is written out, {} for none; accept and content-type are bridge's"
    (is (= #{:headers :keep-alive?}
           (set (keys (:errors (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper})))))))
    (is (= :groundwork-bridge/bad-options
           (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {"content-type" "text/plain"} :keep-alive? false})))))
    (is (= :groundwork-bridge/bad-options
           (:type (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {"Idempotency-Key" "k"} :keep-alive? false}))))
        "names in lowercase"))
  (testing "they go out beside bridge's own; :keep-alive? is fetch's keepalive"
    (let [sent  (atom [])
          fetch (.-fetch js/globalThis)]
      (set! (.-fetch js/globalThis) (fn [address options]
                                      (swap! sent conj [address (js->clj (.-headers options)) (.-keepalive options)])
                                      (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.resolve "{\"greetings/hello\":1}"))})))
      (try
        (groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper :headers {"idempotency-key" "k-1"} :keep-alive? false})
        (groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}} {:shaper shaper :headers {} :keep-alive? true})
        (is (= [["/mail/http" {"idempotency-key" "k-1" "accept" "application/json" "content-type" "application/json"} false]
                ["/mail/http" {"accept" "application/json" "content-type" "application/json"} true]]
               @sent))
        (finally (set! (.-fetch js/globalThis) fetch)))))
  (testing ":keep-alive? is true or false"
    (is (= {:keep-alive? ["should be a boolean"]}
           (:errors (find-problems #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {}}
                                                              {:shaper shaper :headers {} :keep-alive? nil})))))))

(deftest an-error-answer-is-told-from-data-by-groundwork-steps
  (testing "a refusal: one key, :groundwork-steps/refused"
    (doseq [answer [{:groundwork-steps/refused :shop/gone :groundwork-steps/data nil :groundwork-steps/request-id "r"}
                    {:groundwork-steps/refused :groundwork-steps/no-answer :groundwork-steps/data {:http-status nil}}
                    (:shops/total (shapes/turn-answer-into-shaped-data shaper shapes-by-name {:shops/total {:shop/id "seven" :total "1"}}))]]
      (is (true? (steps/is-error-answer? answer)) (pr-str answer))))
  (testing "data, the old error shapes among it"
    (doseq [answer [{:error "Not found."} {:errors {}} {:groundwork-bridge/errors []} [] nil "Not found." 7 {}]]
      (is (false? (steps/is-error-answer? answer)) (pr-str answer)))))

(defn- with-fetch
  "Runs (f) with fetch answered by (answer address options), a promise."
  [answer f]
  (let [fetch (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) answer)
    (-> (f) (.finally #(set! (.-fetch js/globalThis) fetch)))))

(defn- respond [status text]
  (fn [_ _] (js/Promise.resolve #js {:status status :text (fn [] (js/Promise.resolve text))})))

(deftest no-answer-is-a-named-reason-not-a-failed-promise
  (async done
    (let [send!  #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:greetings/hello {} :shops/total {:shop/id shop-id}}
                                            {:shaper shaper :headers {} :keep-alive? false})
          no     (fn [status] {:greetings/hello {:groundwork-steps/refused :groundwork-steps/no-answer :groundwork-steps/data {:http-status status}}
                               :shops/total     {:groundwork-steps/refused :groundwork-steps/no-answer :groundwork-steps/data {:http-status status}}})]
      (-> (with-fetch (respond 502 "<html>Bad gateway</html>") send!)
          (.then #(is (= (no 502) %) "a proxy's page: each name, with the status"))
          (.then #(with-fetch (respond 413 "Payload Too Large") send!))
          (.then #(is (= (no 413) %)))
          (.then #(with-fetch (respond 200 "[1,2]") send!))
          (.then #(is (= (no 200) %) "JSON, but not the server's answer"))
          (.then #(with-fetch (fn [_ _] (js/Promise.reject (js/TypeError. "Failed to fetch"))) send!))
          (.then #(is (= (no nil) %) "no response at all: no status"))
          (.then #(with-fetch (respond 200 "{\"greetings/hello\":{\"groundwork-steps/refused\":\"groundwork-steps/too-many\",\"groundwork-steps/data\":{\"retry-after-ms\":1500},\"groundwork-steps/request-id\":\"r\"},\"shops/total\":{\"shop/id\":\"1d000000-0000-4000-8000-000000000001\",\"total\":\"2\"}}") send!))
          (.then #(is (= {:greetings/hello {:groundwork-steps/refused :groundwork-steps/too-many :groundwork-steps/data {:retry-after-ms 1500} :groundwork-steps/request-id "r"}
                          :shops/total     {:shop/id shop-id :total "2"}}
                         %)
                      "the server's answer: a refusal and data side by side"))
          (.catch #(is false (str %)))
          (.finally done)))))

(defn- with-reported-errors
  "Runs (f), a promise, with the browser's reportError kept in reported."
  [reported f]
  (let [had? (exists? js/reportError)
        was  (when had? js/reportError)]
    (set! (.-reportError js/globalThis) #(swap! reported conj %))
    (-> (f) (.finally #(if had? (set! (.-reportError js/globalThis) was) (js-delete js/globalThis "reportError"))))))

(deftest params-that-dont-fit-are-answered-invalid-not-thrown
  (async done
    (let [fetched  (atom [])
          reported (atom [])
          fetch    (fn [_ options]
                     (swap! fetched conj (js->clj (js/JSON.parse (.-body options))))
                     (js/Promise.resolve #js {:status 200 :text (fn [] (js/Promise.resolve "{\"greetings/hello\":\"hi\"}"))}))
          send!    #(groundwork-bridge.api/send-message! "/mail/http" shapes-by-name % {:shaper shaper :headers {} :keep-alive? false})
          bad      {:shop/id shop-id :extra 1}]
      (-> (with-reported-errors reported
            #(with-fetch fetch
               (fn [] (-> (let [sent (try (send! {:shops/total bad}) (catch :default e e))]
                            (is (instance? js/Promise sent) "a promise, never a throw")
                            sent)
                          (.then (fn [answer]
                                   (is (= :groundwork-steps/invalid (get-in answer [:shops/total :groundwork-steps/refused])))
                                   (is (contains? (get-in answer [:shops/total :groundwork-steps/data :errors]) :extra) "the shaper's errors, by key")
                                   (is (not (contains? (:shops/total answer) :groundwork-steps/request-id)) "nothing was sent: no request id")
                                   (is (true? (steps/is-error-answer? (:shops/total answer))))
                                   (is (= [] @fetched) "nothing is sent")
                                   (is (= [[:groundwork-bridge/bad-params :shops/total]]
                                          (mapv (comp (juxt :type :message-name) ex-data) @reported))
                                       "reported as an uncaught error is, though nothing throws")))
                          (.then (fn [_] (send! {:shops/total bad :greetings/hello {}})))
                          (.then (fn [answer]
                                   (is (= [{"greetings/hello" {}}] @fetched) "the names that fit are sent")
                                   (is (= "hi" (:greetings/hello answer)))
                                   (is (= :groundwork-steps/invalid (get-in answer [:shops/total :groundwork-steps/refused])))))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest params-that-dont-fit-are-shown-in-the-console-without-report-error
  (async done
    (let [logged  (atom [])
          error!  (.-error js/console)
          had?    (exists? js/reportError)
          was     (when had? js/reportError)]
      (when had? (js-delete js/globalThis "reportError"))
      (set! (.-error js/console) #(swap! logged conj %))
      (-> (groundwork-bridge.api/send-message! "/mail/http" shapes-by-name {:shops/total {:shop/id "seven"}} {:shaper shaper :headers {} :keep-alive? false})
          (.then (fn [answer]
                   (is (= :groundwork-steps/invalid (get-in answer [:shops/total :groundwork-steps/refused])))
                   (is (= [:groundwork-bridge/bad-params] (mapv (comp :type ex-data) @logged)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! (.-error js/console) error!)
                      (when had? (set! (.-reportError js/globalThis) was))
                      (done)))))))

(deftest on-the-ws-line-params-that-dont-fit-throw
  (is (= :groundwork-bridge/bad-params
         (:type (find-problems #(shapes/turn-message-into-json-data shaper shapes-by-name {:shops/total {:shop/id "seven"}}))))))
