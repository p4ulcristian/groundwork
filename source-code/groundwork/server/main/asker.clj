(ns groundwork.server.main.asker
  "Who is asking, off a message: on the app's own routes, a page, an
   upload. groundwork-access's session step finds the asker in the request (the
   session's cookie, or a token groundwork-access made or an outside issuer
   signed); groundwork reads what it found, runs a transaction as the asker
   groundwork-access finds in the same request, and answers a request the step
   refuses. No code here names a person."
  (:require
   [clojure.string :as str]
   [groundwork-access.api :as access]
   [groundwork-languages.api :as languages]))

(defn read-asker
  "The asker groundwork-access's session step put in the context, to read:
   {:person-id :visitor-id :session-flags :token-id :outside-issuer}, the
   last two what they asked by (the id of a token of groundwork-access's, the
   name of an outside issuer), nil for neither."
  [context]
  {:person-id      (:groundwork-access/person-id context)
   :visitor-id     (:groundwork-access/visitor-id context)
   :session-flags  (:groundwork-access/session-flags context)
   :token-id       (:groundwork-access/token-id context)
   :outside-issuer (:groundwork-access/outside-issuer context)})

(defn make-run-as-asker
  "(fn [{:keys [read-only?]} f]): (f transaction) in a transaction as the
   asker groundwork-access finds in the request (groundwork-access's
   run-as-asker-of-request!), held by the gates, carrying log, the
   request's (groundwork-postgres's :log: work kept with run-after-commit!
   that fails is written there, saying who asked)."
  [pool request settings scope-kinds log]
  (fn [{:keys [read-only?]} f]
    (access/run-as-asker-of-request! pool request {:settings    (:access settings)
                                                   :read-only?  read-only?
                                                   :scope-kinds scope-kinds
                                                   :log         log}
                                     (fn [transaction _asker] (f transaction)))))

(def bearer-challenge-shape
  "The auth-params a 401's WWW-Authenticate: Bearer carries beside
   error=\"invalid_token\" ({\"resource_metadata\" \"https://shop.example/.well-known/oauth-protected-resource\"}
   for an MCP server), {} for none: each name a token (RFC 7235), each
   value written in quotes as it is, so one with a quote, a backslash or a
   control character is refused."
  [:map-of
   [:re {:error/message "should be an auth-param's name: letters, digits, _ and -"} #"^[A-Za-z][A-Za-z0-9_-]*$"]
   [:re {:error/message "should hold no quote, backslash or control character"} #"^[^\"\\\x00-\x1f\x7f]*$"]])

(defn- write-bearer-challenge
  "WWW-Authenticate's value for a refused bearer token (RFC 6750):
   Bearer, the challenge's params by name, then error=\"invalid_token\"."
  [bearer-challenge]
  (str "Bearer "
       (str/join ", " (concat (map (fn [[param-name value]] (str param-name "=\"" value "\"")) (sort-by key bearer-challenge))
                              ["error=\"invalid_token\""]))))

(defn answer-refused-request
  "A request groundwork-access's find-session step refused, answered: an outside
   issuer's token that failed its check (:groundwork-access/token-refused) 401,
   WWW-Authenticate: Bearer with bearer-challenge's params and
   error=\"invalid_token\" (RFC 6750), so a client knows to get a new one;
   any other reason (:groundwork-access/not-allowed: another site's request, a
   cookie and a token at once) 403. The body is the reason's text in the
   request's language (bound around every request); never cached."
  [reason bearer-challenge]
  (let [token-refused? (= :groundwork-access/token-refused reason)]
    {:status  (if token-refused? 401 403)
     :headers (cond-> {"content-type" "text/plain; charset=utf-8" "cache-control" "no-store"}
                token-refused? (assoc "www-authenticate" (write-bearer-challenge bearer-challenge)))
     :body    (languages/tr reason {})}))
