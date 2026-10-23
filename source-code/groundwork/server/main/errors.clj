(ns groundwork.server.main.errors
  "The errors, for a monitor to pull, and the browser's, sent to be logged.

   The errors route: a GET at the :errors settings' :path, outside
   sessions, access and rate limits, answered only with the settings'
   :token, a secret of safe's, as a bearer token (Authorization: Bearer …,
   compared in constant time by safe/equal?; else 401). ?since=N answers, as a JSON array, the :error lines
   logbook keeps whose number is after N, oldest first, each as written
   with its \"seq\" (logbook's list-errors-since):

     GET /groundwork/errors?since=41
     Authorization: Bearer …
     200 [{\"seq\":42,\"time\":\"…\",\"level\":\"error\",\"event\":\"…\",\"context\":{…},\"data\":{…},\"error\":{…}}]

   A since that isn't a whole number from 0 is 400. Never cached.

   The browser's errors: groundwork's browser start sends each uncaught error
   and unhandled rejection as a message, {:groundwork/browser-error {:message …
   :stack … :url …}}; its handler finds the session (the Origin checked,
   the person added to the message's log), then writes it at :error,
   :browser/uncaught, so it is kept with the server's errors, with the
   request's id and the person. Its rate limit, per asker, is the :errors
   settings' :browser-rate-limit, so a page failing in a loop can't flood
   the log."
  (:require
   [clojure.string :as str]
   [logbook.api :as logbook]
   [mailroom.api :as mailroom]
   [safe.api :as safe]
   [wire.api :as wire]))

;; -----------------------------------------------------------------------------
;; The errors route

(defn- has-token?
  "Whether the request carries the token (a secret) as its bearer token,
   Authorization: Bearer <token>. The token is compared by safe/equal?, in
   constant time whatever its length and wherever the two first differ."
  [request token]
  (let [given (get-in request [:headers "authorization"])]
    (and (string? given)
         (str/starts-with? given "Bearer ")
         (safe/equal? token (subs given (count "Bearer "))))))

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
  {:name  :groundwork/write-browser-error
   :enter (fn [{:mailroom/keys [log message-data] :as context}]
            (log :error :browser/uncaught {:browser/message (:message message-data)
                                           :browser/stack   (:stack message-data)
                                           :browser/url     (:url message-data)})
            (mailroom/add-answer-to-context context true))})

(defn make-browser-error-handler
  "The :groundwork/browser-error handler: doorman's find-session step, then the
   error written to the message's log at :error, answered true; limited
   per asker by rate-limit, the :errors settings' :browser-rate-limit."
  [find-session-step rate-limit]
  {:steps        [find-session-step write-browser-error]
   :params-shape browser-error-params-shape
   :answer-shape :boolean
   :rate-limit   rate-limit
   :run-steps-in nil})

(defn add-browser-error-handler
  "The handlers with groundwork's :groundwork/browser-error; an app's own handler of
   that name is refused."
  [handlers find-session-step rate-limit]
  (when (contains? handlers :groundwork/browser-error)
    (throw (ex-info "The handler name :groundwork/browser-error is groundwork's own; the app's handlers use it"
                    {:type :groundwork.server.main/handler-name-taken :handler :groundwork/browser-error})))
  (assoc handlers :groundwork/browser-error (make-browser-error-handler find-session-step rate-limit)))
