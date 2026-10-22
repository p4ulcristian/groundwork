(ns house.server.main.errors
  "The errors, for a monitor to pull, and the browser's, sent to be logged.

   The errors route: a GET at the :errors settings' :path, outside
   sessions, access and rate limits, answered only with the settings'
   :token as a bearer token (Authorization: Bearer …, compared in constant
   time; else 401). ?since=N answers, as a JSON array, the :error lines
   logbook keeps whose number is after N, oldest first, each as written
   with its \"seq\" (logbook's list-errors-since):

     GET /house/errors?since=41
     Authorization: Bearer …
     200 [{\"seq\":42,\"time\":\"…\",\"level\":\"error\",\"event\":\"…\",\"context\":{…},\"data\":{…},\"error\":{…}}]

   A since that isn't a whole number from 0 is 400. Never cached.

   The browser's errors: house's browser start sends each uncaught error
   and unhandled rejection as a message, {:house/browser-error {:message …
   :stack … :url …}}; its handler finds the session (the Origin checked,
   the person added to the message's log), then writes it at :error,
   :browser/uncaught, so it is kept with the server's errors, with the
   request's id and the person. Its rate limit, per asker, is the :errors
   settings' :browser-rate-limit, so a page failing in a loop can't flood
   the log."
  (:require
   [logbook.api :as logbook]
   [mailroom.api :as mailroom]
   [wire.api :as wire])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

;; -----------------------------------------------------------------------------
;; The errors route

(defn- hash-text ^bytes [^String text]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text StandardCharsets/UTF_8)))

(defn- has-token?
  "Whether the request carries the token as its bearer token. Both sides
   are hashed first, so the comparison takes the same time whatever the
   token's length and wherever the two first differ."
  [request token]
  (let [given (get-in request [:headers "authorization"])]
    (and (string? given)
         (MessageDigest/isEqual (hash-text given) (hash-text (str "Bearer " token))))))

(defn- read-since
  "?since=N's N, or nil when it is missing or not a whole number from 0."
  [{:keys [query-string]}]
  (some->> (when query-string (re-find #"(?:^|&)since=(\d{1,18})(?:&|$)" query-string))
           second
           parse-long))

(def ^:private not-allowed
  {:status 401 :headers {"content-type" "text/plain" "cache-control" "no-store" "www-authenticate" "Bearer"}
   :body   "This address needs the errors token."})

(def ^:private no-since
  {:status 400 :headers {"content-type" "text/plain" "cache-control" "no-store"}
   :body   "Say since=N: the number of the last error you have, 0 for all."})

(defn make-errors-routes
  "The errors route, at the :errors settings' :path, as reitit data,
   answered as JSON by the app's wire."
  [wire logbook {{:keys [path token]} :errors}]
  [[path {:get {:handler (fn [request]
                           (if-not (has-token? request token)
                             not-allowed
                             (if-let [since (read-since request)]
                               {:status  200
                                :headers {"content-type" "application/json" "cache-control" "no-store"}
                                :body    (wire/turn-data-into-json-text wire (logbook/list-errors-since logbook since))}
                               no-since)))}}]])

;; -----------------------------------------------------------------------------
;; The browser's errors

(def browser-error-params-shape
  "What the browser sends: the error's message, its stack (nil when the
   browser has none) and the page's address."
  [:map {:closed true}
   [:message :string]
   [:stack [:maybe :string]]
   [:url :string]])

(def ^:private write-browser-error
  {:name  :house/write-browser-error
   :enter (fn [{:mailroom/keys [log message-data] :as context}]
            (log :error :browser/uncaught {:browser/message (:message message-data)
                                           :browser/stack   (:stack message-data)
                                           :browser/url     (:url message-data)})
            (mailroom/add-answer-to-context context true))})

(defn make-browser-error-handler
  "The :house/browser-error handler: doorman's find-session step, then the
   error written to the message's log at :error, answered true; limited
   per asker by rate-limit, the :errors settings' :browser-rate-limit."
  [find-session-step rate-limit]
  {:steps        [find-session-step write-browser-error]
   :params-shape browser-error-params-shape
   :answer-shape :boolean
   :rate-limit   rate-limit
   :run-steps-in nil})

(defn add-browser-error-handler
  "The handlers with house's :house/browser-error; an app's own handler of
   that name is refused."
  [handlers find-session-step rate-limit]
  (when (contains? handlers :house/browser-error)
    (throw (ex-info "The handler name :house/browser-error is house's own; the app's handlers use it"
                    {:type :house.server.main/handler-name-taken :handler :house/browser-error})))
  (assoc handlers :house/browser-error (make-browser-error-handler find-session-step rate-limit)))
