(ns groundwork.server.main.app-routes
  "The app's own server routes (the app map's :routes): what isn't a
   message or a page, a provider's webhook, a login's callback, a file
   handed out, an API for machines. Each is a record with a kind, served
   beside groundwork's own routes and through the same steps (the
   request's language first, for every route):

     {:stripe/webhook {:address \"/stripe-webhook\" :method :post :kind :provider
                       :rate-limit {:max 30 :per-ms 60000} :handler stripe/process}
      :flex/authorize {:address \"/flex/authorize\" :method :get :kind :person
                       :rate-limit nil :bearer-challenge {} :handler flex-authorize}
      :app/mcp        {:address \"/mcp\" :method :post :kind :person :rate-limit nil
                       :bearer-challenge {\"resource_metadata\" \"https://shop.example/.well-known/oauth-protected-resource\"}
                       :handler mcp/answer}}

   :person: a person calls it, from their browser (the session's cookie)
   or a script (a token groundwork-access made for them, or an outside
   issuer's the :access settings name, Authorization: Bearer).
   groundwork-access's session step runs first and finds who asks: a request that
   changes something by a cookie (or by nothing) must come from the app's
   own pages (its Origin), a request with both a cookie and a token is
   refused, else 403, its body the text of groundwork-access's reason,
   :groundwork-access/not-allowed, in the request's language; an outside
   token that failed its check is refused 401, :groundwork-access/token-refused,
   WWW-Authenticate: Bearer with the route's :bearer-challenge params and
   error=\"invalid_token\" (RFC 6750; an MCP server names its
   resource_metadata there), {} for none; the handler gets the asker.
   :provider: another server calls it; no session or token is read, and
   the handler proves the caller itself (a signature, a provider's
   secret).

   A handler is (fn [request tools]) and answers a Ring response. Its
   request's body is not read (a webhook checks its signature over the
   raw bytes). tools, for :person:

     {:asker          {:person-id … :visitor-id … :session-flags #{…}    ; what groundwork-access found, to read,
                       :token-id … :outside-issuer …}                     ; and what they asked by
      :log            the app's log, saying who asked
      :run-as-asker!  (fn [{:keys [read-only?]} f]) f in a transaction as the request's asker,
                      held by the gates (groundwork-access's run-as-asker-of-request!), carrying
                      :log (groundwork-postgres's: work kept with run-after-commit! that fails is written there)
      :run-as-system! (fn [system-name {:keys [read-only?]} f]) groundwork-access's, carrying :log too
      :answer-as      (fn [message request]) a message answered as the request's asker, through
                      every check (groundwork.server.main.answer-as): a route that speaks
                      another protocol (MCP's JSON-RPC) turns a call into a message
      :sign-in!       (fn [person-id {:keys [session-flags session-lasts outside-subjects]}])
                      {:set-cookie …} for the answer, once the app's own login has proven
                      the person: groundwork-access's sign-in!, every option written out
                      (:session-lasts :session-days or {:minutes 60}; :outside-subjects
                      {issuer-name subject}, {} for none)
      :sign-out!      (fn []) {:set-cookie …}}

   for :provider, {:log :run-as-system!}: no asker, so nothing
   runs as one.

   :rate-limit, {:max :per-ms} or nil for none: one bucket per asker (a
   :person route's by its session, else its address; a
   :provider route's by its address), 429 over it. An address
   another route has (groundwork's own, or the app's) refuses the start."
  (:require
   [groundwork-bridge.api :as bridge]
   [groundwork-steps.api :as steps]
   [groundwork-access.api :as access]
   [groundwork.server.main.asker :as asker]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def ^:private route-entries
  [[:address [:re {:error/message "should be a path, starting with /"} #"^/.*"]]
   [:method [:enum :get :post :put :patch :delete]]
   [:rate-limit [:maybe [:map {:closed true} [:max pos-int?] [:per-ms pos-int?]]]]
   [:handler fn?]])

(def route-shape
  "One route, by :kind, every key required; a :person route's
   :bearer-challenge too, the params a refused outside token's 401 carries
   (asker's bearer-challenge-shape), {} for none."
  [:multi {:dispatch :kind :error/message "should say its :kind, :person or :provider"}
   [:person (into [:map {:closed true} [:kind [:= :person]] [:bearer-challenge asker/bearer-challenge-shape]] route-entries)]
   [:provider (into [:map {:closed true} [:kind [:= :provider]]] route-entries)]])

(def routes-shape
  "The app's routes by name, {} for none."
  [:map-of :qualified-keyword route-shape])

(defn- find-routes-sharing-a-method
  "Names of routes with the same address and method as another, by
   [address method]."
  [routes]
  (into {}
        (keep (fn [[address-and-method named]] (when (< 1 (count named)) [address-and-method (sort (map first named))])))
        (group-by (fn [[_ {:keys [address method]}]] [address method]) routes)))

(defn check-routes
  "Returns the routes; throws :groundwork.server.main/bad-routes with every
   problem at once, and for two routes at one address and method."
  [routes]
  (when-let [explanation (malli/explain routes-shape routes)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The routes have problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-routes :errors errors}))))
  (when-let [shared (not-empty (find-routes-sharing-a-method routes))]
    (throw (ex-info (str "Routes share an address and a method: " (pr-str shared))
                    {:type :groundwork.server.main/bad-routes :errors {:shared shared}})))
  routes)

(defn- make-run-as-system
  "(fn [system-name {:keys [read-only?]} f]): groundwork-access's run-as-system!, its
   transaction carrying log, the route's (groundwork-postgres's :log: work kept with
   run-after-commit! that fails is written there)."
  [pool log]
  (fn [system-name {:keys [read-only?]} f]
    (access/run-as-system! pool system-name {:read-only? read-only? :log log} f)))

(defn- make-person-tools
  [request context {:keys [pool scope-kinds tables settings answer-as]}]
  (let [asker (asker/read-asker context)]
    {:asker          asker
     :log            (:groundwork-steps/log context)
     :run-as-asker!  (asker/make-run-as-asker pool request settings scope-kinds (:groundwork-steps/log context))
     :run-as-system! (make-run-as-system pool (:groundwork-steps/log context))
     :answer-as      answer-as
     :sign-in!       (fn [person-id options]
                       (access/sign-in! pool request person-id (assoc options :settings (:access settings) :tables tables)))
     :sign-out!      (fn [] (access/sign-out! pool request (:access settings)))}))

(defn- make-person-handler [handler bearer-challenge {:keys [log find-session-step] :as parts}]
  (fn [request]
    (let [context ((:enter find-session-step) {:groundwork-steps/request request :groundwork-steps/log log})]
      (if (steps/does-context-have-answer? context)
        (asker/answer-refused-request (get-in context [:groundwork-steps/answer :groundwork-steps/refused]) bearer-challenge)
        (handler request (make-person-tools request context parts))))))

(defn- make-provider-handler [handler {:keys [log pool]}]
  (fn [request]
    (handler request {:log            log
                      :run-as-system! (make-run-as-system pool log)})))

(defn- add-rate-limit [ring-handler rate-limit find-rate-limit-key {:keys [settings]}]
  (if rate-limit
    (bridge/make-rate-limited-ring-handler ring-handler
                                           {:rate-limit             rate-limit
                                            :find-rate-limit-key    find-rate-limit-key
                                            :buckets-before-cleanup (get-in settings [:bridge :rate-limit-buckets-before-cleanup])})
    ring-handler))

(defn make-app-routes
  "The app's routes as reitit data, one entry per address, each method's
   handler behind its kind's steps and its rate limit. parts: {:pool :log
   :settings :tables :scope-kinds :find-session-step
   :find-person-rate-limit-key :answer-as}."
  [routes {:keys [settings find-person-rate-limit-key] :as parts}]
  (let [find-address #(bridge/find-client-address % (get-in settings [:bridge :client-address-header]))]
    (->> (check-routes routes)
         (reduce-kv (fn [by-address _route-name {:keys [address method kind rate-limit handler bearer-challenge]}]
                      (let [ring-handler (case kind
                                           :person           (make-person-handler handler bearer-challenge parts)
                                           :provider         (make-provider-handler handler parts))
                            limited      (add-rate-limit ring-handler rate-limit
                                                         (case kind :person find-person-rate-limit-key :provider find-address)
                                                         parts)]
                        (assoc-in by-address [address method] {:handler limited})))
                    (sorted-map))
         (mapv (fn [[address methods]] [address methods])))))
