(ns groundwork-bridge.routes
  "The server's routes, with reitit: the three addresses, and each page,
   answered by the app's page handler when it is opened fresh; files (the
   browser's code, its styles) from a folder on disk. Any other address is
   answered by the app's :not-found (nil: a 404, empty).

   The app's pages are a map by name, {:orders/page {:address \"/orders/:order-id\" …}};
   the browser gets the same map from the page (the app puts it there), so
   the two sides can't disagree."
  (:require
   [clojure.string :as str]
   [groundwork-bridge.settings :as settings]
   [reitit.ring :as reitit]))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler (the page's HTML and
   the app's JS), so a page opened fresh or reloaded works. The pages are
   checked first (settings/pages-shape): each a namespaced name, an
   :address and a :title (nil when the app sets the title itself)."
  [pages page-handler]
  (settings/check-pages pages)
  (vec (for [[page-name page] pages]
         [(:address page) {:name page-name :get {:handler page-handler}}])))

(defn- make-hashed-name-pattern
  "main.3F9A0C1D2E….js: a release build's file, whose name changes with its
   content; at least min-hex-digits hex digits before the extension."
  [min-hex-digits]
  (re-pattern (str "\\.[0-9A-Fa-f]{" min-hex-digits ",}\\.[a-z]+$")))

;; RFC 9110, 12.5.3: Accept-Encoding = #( codings [ weight ] ), codings a
;; content-coding token, "identity" or "*", weight = OWS ";" OWS "q=" qvalue,
;; qvalue = ( "0" [ "." 0*3DIGIT ] ) / ( "1" [ "." 0*3("0") ] ), all in any case
(def ^:private accepted-coding-pattern
  #"(?i)([!#$%&'*+.^_`|~0-9a-z-]+)(?:[ \t]*;[ \t]*q=(0(?:\.[0-9]{0,3})?|1(?:\.0{0,3})?))?")

(defn read-accepted-codings
  "Accept-Encoding's codings in the header's order, [[coding quality] …],
   the coding in lowercase, the quality a double (1.0 when unsaid): HTTP's
   list split by , (an empty element allowed), the spaces and tabs around
   each taken off; an element that isn't a coding with an optional q (a
   q above 1, a parameter other than q) is left out."
  [header]
  (into []
        (keep (fn [element]
                (when-let [[_ coding quality] (re-matches accepted-coding-pattern (str/trim element))]
                  [(str/lower-case coding) (if quality (parse-double quality) 1.0)])))
        (str/split (or header "") #",")))

(defn does-request-take-gzip?
  "Whether the request's Accept-Encoding takes gzip: gzip (or x-gzip, the
   same, RFC 9110 8.4.1.3) listed with a quality above 0, else * listed
   with one; the first listing of each counts. gzip;q=0, or *;q=0 with gzip
   unlisted, is no; a request without Accept-Encoding gets the file as it
   is."
  [request]
  (let [codings  (read-accepted-codings (get-in request [:headers "accept-encoding"]))
        first-of (fn [names] (some (fn [[coding quality]] (when (contains? names coding) quality)) codings))
        quality  (or (first-of #{"gzip" "x-gzip"}) (first-of #{"*"}))]
    (boolean (and quality (pos? quality)))))

(defn- send-gzipped-copy-when-there
  "A built file with file.gz beside it goes out gzipped to a browser that
   takes gzip: the same file, a tenth of the bytes."
  [file-handler files-folder]
  (fn [request]
    (let [gzipped (when (does-request-take-gzip? request)
                    (java.io.File. (str files-folder (:uri request) ".gz")))]
      (if-let [response (and gzipped (.isFile gzipped) (file-handler request))]
        (-> response
            (assoc :body gzipped)
            (update :headers dissoc "Content-Length" "content-length")
            (assoc-in [:headers "content-encoding"] "gzip")
            (assoc-in [:headers "vary"] "accept-encoding"))
        (file-handler request)))))

(defn- add-cache-header
  "A hashed file never changes, so it is kept :hashed-file-cache-seconds;
   any other file is asked again each time (its name stays when it
   changes)."
  [file-handler {:keys [hashed-name-min-hex-digits hashed-file-cache-seconds]}]
  (let [hashed-name (make-hashed-name-pattern hashed-name-min-hex-digits)
        kept        (str "public, max-age=" hashed-file-cache-seconds ", immutable")]
    (fn [request]
      (when-let [response (file-handler request)]
        (assoc-in response [:headers "cache-control"]
                  (if (re-find hashed-name (:uri request)) kept "no-cache"))))))

(defn- make-file-handler
  "Files from the folder, by their exact names: a folder's address is never
   answered with a file in it nor redirected (a page is answered by name,
   by the page handler), and no link leads out of the folder. Nil when the
   folder has no such file, so the default handler answers."
  [{:keys [folder] :as files}]
  (-> (reitit/create-file-handler {:path               "/"
                                   :root               folder
                                   :index-files        []
                                   :canonicalize-uris? false
                                   :index-redirect?    false
                                   :allow-symlinks?    false})
      (send-gzipped-copy-when-there folder)
      (add-cache-header files)))

(defn- make-default-handler
  "Written out as reitit's own: no route and no file, not-found's answer
   (or 404, empty, without one); a route without the request's method, 405;
   a handler that answered nil, 406."
  [not-found]
  (reitit/create-default-handler {:not-found          (or not-found (constantly {:status 404 :headers {} :body ""}))
                                  :method-not-allowed (constantly {:status 405 :headers {} :body ""})
                                  :not-acceptable     (constantly {:status 406 :headers {} :body ""})}))

(defn make-ring-handler-from-routes
  "The one Ring handler for the server, from reitit routes. With :files
   (nil for none), a GET no route matches is answered from its :folder on
   disk, when it has the file. Otherwise a path that matches no route is
   answered by :not-found (nil: 404, empty), a method the route doesn't
   have 405."
  [routes {:keys [files not-found]}]
  (reitit/ring-handler (reitit/router routes)
                       (if files
                         (reitit/routes (make-file-handler files) (make-default-handler not-found))
                         (make-default-handler not-found))))
