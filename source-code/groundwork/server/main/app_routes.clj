(ns groundwork.server.main.app-routes
  "The app's own server routes (the app map's :routes): what isn't a
   message or a page, a provider's webhook, a login's callback, a file
   handed out, an API for machines. Each is a record with a kind, served
   beside groundwork's own routes and through the same steps (the
   request's language first, for every route):

     {:stripe/webhook {:address \"/stripe-webhook\" :method :post :kind :server-to-server
                       :rate-limit {:max 30 :per-ms 60000} :handler stripe/process}
      :flex/authorize {:address \"/flex/authorize\" :method :get :kind :browser
                       :rate-limit nil :handler flex-authorize}}

   :browser: a person calls it, from their browser (the session's cookie)
   or a script (a token doorman made for them, Authorization: Bearer).
   doorman's session step runs first and finds who asks: a request that
   changes something by a cookie (or by nothing) must come from the app's
   own pages (its Origin), a request with both a cookie and a token is
   refused, else 403; the handler gets the asker. :server-to-server:
   another server calls it; no session or token is read, and the handler
   proves the caller itself (a signature, a provider's secret).

   A handler is (fn [request tools]) and answers a Ring response. Its
   request's body is not read (a webhook checks its signature over the
   raw bytes). tools, for :browser:

     {:asker          {:person-id … :visitor-id … :session-flags #{…}}   ; what doorman found, to read
      :log            the app's log, saying who asked
      :run-as-asker!  (fn [{:keys [read-only?]} f]) f in a transaction as the request's asker,
                      held by the gates (doorman's run-as-asker-of-request!)
      :run-as-system! (fn [system-name {:keys [read-only?]} f]) doorman's
      :answer-as      (fn [message request]) a message answered as the request's asker, through
                      every check (groundwork.server.main.answer-as): a route that speaks
                      another protocol (MCP's JSON-RPC) turns a call into a message
      :sign-in!       (fn [person-id session-flags]) {:set-cookie …} for the answer, once the
                      app's own login has proven the person
      :sign-out!      (fn []) {:set-cookie …}}

   for :server-to-server, {:log :run-as-system!}: no asker, so nothing
   runs as one.

   :rate-limit, {:max :per-ms} or nil for none: one bucket per asker (a
   :browser route's by its session, else its address; a
   :server-to-server route's by its address), 429 over it. An address
   another route has (groundwork's own, or the app's) refuses the start."
  (:require
   [bridge.api :as bridge]
   [doorman.api :as doorman]
   [groundwork.server.main.asker :as asker]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def route-shape
  "One route, every key required."
  [:map {:closed true}
   [:address [:re {:error/message "should be a path, starting with /"} #"^/.*"]]
   [:method [:enum :get :post :put :patch :delete]]
   [:kind [:enum :browser :server-to-server]]
   [:rate-limit [:maybe [:map {:closed true} [:max pos-int?] [:per-ms pos-int?]]]]
   [:handler fn?]])

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

(defn- turn-refusal-into-response
  "doorman's step's refusal (another site's request, a cookie and a token
   at once), as a 403."
  [{:keys [error]}]
  {:status 403 :headers {"content-type" "text/plain" "cache-control" "no-store"} :body error})

(defn- make-browser-tools
  [request context {:keys [pool scope-kinds tables settings answer-as]}]
  (let [asker (asker/read-asker context)]
    {:asker          asker
     :log            (:bridge/log context)
     :run-as-asker!  (asker/make-run-as-asker pool request settings scope-kinds)
     :run-as-system! (fn [system-name options f] (doorman/run-as-system! pool system-name options f))
     :answer-as      answer-as
     :sign-in!       (fn [person-id session-flags]
                       (doorman/sign-in! pool request person-id {:settings      (:sessions settings)
                                                                 :tables        tables
                                                                 :session-flags session-flags}))
     :sign-out!      (fn [] (doorman/sign-out! pool request (:sessions settings)))}))

(defn- make-browser-handler [handler {:keys [log find-session-step] :as parts}]
  (fn [request]
    (let [context ((:enter find-session-step) {:bridge/request request :bridge/log log})]
      (if (bridge/does-context-have-answer? context)
        (turn-refusal-into-response (:bridge/answer context))
        (handler request (make-browser-tools request context parts))))))

(defn- make-server-to-server-handler [handler {:keys [log pool]}]
  (fn [request]
    (handler request {:log            log
                      :run-as-system! (fn [system-name options f] (doorman/run-as-system! pool system-name options f))})))

(defn- add-rate-limit [ring-handler rate-limit find-rate-limit-key {:keys [settings]}]
  (if rate-limit
    (bridge/make-rate-limited-ring-handler ring-handler
                                           {:rate-limit             rate-limit
                                            :find-rate-limit-key    find-rate-limit-key
                                            :buckets-before-cleanup (get-in settings [:server :rate-limit-buckets-before-cleanup])})
    ring-handler))

(defn make-app-routes
  "The app's routes as reitit data, one entry per address, each method's
   handler behind its kind's steps and its rate limit. parts: {:pool :log
   :settings :tables :scope-kinds :find-session-step
   :find-browser-rate-limit-key :answer-as}."
  [routes {:keys [settings find-browser-rate-limit-key] :as parts}]
  (let [find-address #(bridge/find-client-address % (get-in settings [:server :client-address-header]))]
    (->> (check-routes routes)
         (reduce-kv (fn [by-address _route-name {:keys [address method kind rate-limit handler]}]
                      (let [ring-handler (case kind
                                           :browser          (make-browser-handler handler parts)
                                           :server-to-server (make-server-to-server-handler handler parts))
                            limited      (add-rate-limit ring-handler rate-limit
                                                         (case kind :browser find-browser-rate-limit-key :server-to-server find-address)
                                                         parts)]
                        (assoc-in by-address [address method] {:handler limited})))
                    (sorted-map))
         (mapv (fn [[address methods]] [address methods])))))
