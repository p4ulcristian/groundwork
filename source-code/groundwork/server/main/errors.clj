(ns groundwork.server.main.errors
  "The errors, for a monitor to pull, and the browser's, sent to be logged.

   The errors route: a GET at the :errors settings' :path, outside
   sessions, access and rate limits, answered only with the settings'
   :token, a secret of groundwork-settings's, as a bearer token (Authorization: Bearer …,
   compared in constant time by settings/equal?; else 401). ?since=N answers, as a JSON array, the :error lines
   groundwork-logs keeps whose number is after N, oldest first, each as written
   with its \"seq\" (groundwork-logs's list-errors-since):

     GET /groundwork/errors?since=41
     Authorization: Bearer …
     200 [{\"seq\":42,\"time\":\"…\",\"level\":\"error\",\"event\":\"…\",\"context\":{…},\"data\":{…},\"error\":{…}}]

   A since that isn't a whole number from 0, or is given twice, is 400; the
   query is read as form text (since=%34%31 is 41). Never cached.

   The browser's errors: groundwork's browser start sends each uncaught error
   and unhandled rejection, and each an app's error boundary reports
   (report-browser-error!), as a message, {:groundwork/browser-error {:kind …
   :version … :message … :stack … :url …}}; its handler finds the session
   (the Origin checked, the person added to the message's log; refused
   :groundwork-access/not-allowed or :groundwork-access/token-refused, it
   writes nothing), then writes it at :error, :browser/error, so it is kept with the server's
   errors in the one list a monitor reads, with the request's id and the
   person. Its rate limit, per asker, is the :errors
   settings' :browser-rate-limit, so a page failing in a loop can't flood
   the log."
  (:require
   [clojure.string :as str]
   [groundwork-access.api :as access]
   [groundwork-logs.api :as logs]
   [groundwork-steps.api :as steps]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json]
   [ring.util.codec :as codec]))

;; -----------------------------------------------------------------------------
;; The errors route

(defn- has-token?
  "Whether the request carries the token (a secret) as its bearer token,
   Authorization: Bearer <token>. The token is compared by settings/equal?, in
   constant time whatever its length and wherever the two first differ."
  [request token]
  (let [given (get-in request [:headers "authorization"])]
    (and (string? given)
         (str/starts-with? given "Bearer ")
         (settings/equal? token (subs given (count "Bearer "))))))

(defn- read-since
  "?since=N's N, or nil when it is missing, given twice, or not a whole
   number from 0: the query read as form text by ring.util.codec's
   form-decode (UTF-8; a pair with a bad %-escape left out)."
  [{:keys [query-string]}]
  (let [params (codec/form-decode (or query-string "") "UTF-8")
        since  (when (map? params) (get params "since"))]
    (when (and (string? since) (re-matches #"\d{1,18}" since))
      (parse-long since))))

(def ^:private not-allowed
  {:status 401 :headers {"content-type" "text/plain" "cache-control" "no-store" "www-authenticate" "Bearer"}
   :body   "This address needs the errors token."})

(def ^:private no-since
  {:status 400 :headers {"content-type" "text/plain" "cache-control" "no-store"}
   :body   "Say since=N: the number of the last error you have, 0 for all."})

(defn make-errors-routes
  "The errors route, at the :errors settings' :path, as reitit data,
   answered as JSON by the app's shaper."
  [shaper logger {{:keys [path token]} :errors}]
  [[path {:get {:handler (fn [request]
                           (if-not (has-token? request token)
                             not-allowed
                             (if-let [since (read-since request)]
                               {:status  200
                                :headers {"content-type" "application/json" "cache-control" "no-store"}
                                :body    (shaped-json/turn-data-into-json-text shaper (logs/list-errors-since logger since))}
                               no-since)))}}]])

;; -----------------------------------------------------------------------------
;; The browser's errors

(def browser-error-params-shape
  "What the browser sends, every key written out: :kind, :render (an
   app's error boundary caught it) or :uncaught (the browser start's
   listener); :version, the version of the page's bundle (the version
   setting the page carries); the error's message, its stack (nil when
   the browser has none) and the page's address."
  [:map {:closed true}
   [:kind [:enum :render :uncaught]]
   [:version :string]
   [:message :string]
   [:stack [:maybe :string]]
   [:url :string]])

(def ^:private write-browser-error
  {:name  :groundwork/write-browser-error
   :enter (fn [{:groundwork-steps/keys [log message-data] :as context}]
            (log :error :browser/error {:browser/kind    (:kind message-data)
                                        :browser/version (:version message-data)
                                        :browser/message (:message message-data)
                                        :browser/stack   (:stack message-data)
                                        :browser/url     (:url message-data)})
            (steps/add-answer-to-context context true))})

(def browser-error-refusals
  "The reasons :groundwork/browser-error may refuse with: groundwork-access's
   find-session step's, :groundwork-access/not-allowed (another site's request, a
   cookie and a token at once) and :groundwork-access/token-refused (an outside
   issuer's token that failed its check). Not put through groundwork-access's
   access, so it declares them itself; the browser's shape says the same."
  (select-keys access/refusals [:groundwork-access/not-allowed :groundwork-access/token-refused]))

(defn make-browser-error-handler
  "The :groundwork/browser-error handler: groundwork-access's find-session step, then the
   error written to the message's log at :error, answered true; limited
   per asker by rate-limit, the :errors settings' :browser-rate-limit."
  [find-session-step rate-limit]
  {:steps        [find-session-step write-browser-error]
   :params-shape browser-error-params-shape
   :answer-shape :boolean
   :refusals     browser-error-refusals
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
