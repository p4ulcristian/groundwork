(ns groundwork.web
  "The router: the app's routes, as reitit data, next to Groundwork's own;
   files from resources/public; the page shell for every other page a browser
   asks for, so the app's own routing in the browser takes over.

   Route data Groundwork reads:

     [\"/customize\" {:get web/page :embeddable? true}]
         a page other sites may show in a frame; true for any https site, a
         list of origins: [\"https://shop.example\"], or :same-origin for
         only the app's own pages (an editor inside another page)
     [\"/payments/webhook\" {:post handle :server-to-server? true}]
         called by another server (a payment provider's webhook), never by a
         browser: no session is read or made and the Origin check doesn't
         apply, so the handler authenticates the call itself (a signature)
     [\"/shops/:slug\" {:get web/page :page (fn [request] {:title …})}]
         the page shell with the route's own title, description, links,
         scripts and server-written body; see groundwork.web.page

   Every other route is a browser route: it gets the session, and a request
   that changes something must come from the app's own pages.

   Every route declares who may use it, or the app doesn't start. :access
   has an endpoint's shape (see groundwork.endpoints); a route checks these:

     :access :public       anyone
     :access :signed-in    someone signed in; a signed-out browser asking for
                           a page goes to :login-path?next=…, anything else 401
     :access {:action :orders/see :record [:order :order-id]}
                           the action on the record whose id is the path
                           parameter (or form/query parameter) :order-id;
                           not allowed answers 404, like a record that isn't there
     :server-to-server? true   instead of :access: the handler checks the call

   An action on a :scope or a :list is an endpoint's: a route naming one
   stops the start.

   Hooks, for logs and metrics (nil for none):

     :on-request (fn [{:keys [method route status ms person flags request-id client-ip]}] …)
         after every answer; route is the matched template, like \"/orders/:order-id\"
     :on-error   (fn [{:keys [error method route person request-id client-ip …]}] …)
         an exception no handler caught; the browser gets a plain 500

   Every answer carries X-Request-Id. Behind a proxy, :proxies says which
   addresses are trusted, and only a request from one of them is believed
   about the visitor's address and its request id:

     :proxies {:trusted           [\"10.0.0.0/8\" \"::1\"]
               :client-ip-header  \"cf-connecting-ip\"   ; or nil
               :request-id-header \"x-request-id\"}      ; or nil

   Languages, the one place a language is set:

     :languages {:supported [\"en\" \"de\"] :default \"en\" :cookie \"lang\"}
     :languages {:supported [\"en\"] :default \"en\" :cookie nil}   ; one language

   With more than one, a /de/… prefix is taken off before routing; the
   request's language comes from the address, then the cookie (when named),
   then the browser's Accept-Language, then the default (see language). The
   page shell's lang and its alternate links follow it. With one, every
   request is in it: no prefix, no alternate links.

   GET /health answers 200 when the database answers, 503 when it doesn't,
   for a load balancer or a monitor.

   Automated tests can sign in without the real login (never in production;
   a setting there stops the start):

     :test-sign-in {:secret \"at least 16 characters\"}   ; or nil
     POST /groundwork/test-sign-in?person=<uuid>&flags=impersonated&hours=1
          with X-Test-Secret: <secret>   → the session cookie

   Rate limits: a route's own :rate-limit, or for a :public route the app's
   :public limit (see groundwork.limits); over it, 429 with Retry-After.

   Request bodies: none may be larger than :max-body-bytes, or a route's own
   [\"/uploads\" {:post upload :max-body-bytes 104857600}]; a larger one is
   refused with 413. A browser route's form body is parsed into :form-params,
   a multipart one into :multipart-params, its files kept as temporary files
   (deleted after an hour) with the type their bytes really are under
   :real-type (see groundwork.web.files/sniff-type), whatever the browser
   claimed. Other bodies, like JSON, and every server-to-server route's body
   stay untouched, so a webhook can check a signature over the exact bytes.

   A file whose name carries its content's hash (it matches the regular
   expression :hashed-files, like \"\\\\.[0-9a-f]{8,}\\\\.\", or nil) is cached a year;
   every other file is revalidated on each load.

   Text-like answers of at least :gzip-min-bytes (or nil for none) go
   gzipped to a browser that accepts it.

   Files answer byte ranges (206), so video plays and seeks everywhere. HEAD
   is answered for every GET route, like GET without the body.

   Every answer carries the standard security headers, and no page may be
   framed by another site unless its route is :embeddable?. In production also
   Strict-Transport-Security. The Content-Security-Policy is strict: scripts
   and styles only from the app's own origin or carrying this request's
   nonce (the page shell stamps it on its own tags), plus what the app adds
   per directive in :content-security-policy, like
   {:script-src [\"https://js.payments.example\"] :frame-src […]}. Development
   also allows eval, which its build needs. Files are revalidated on every load (a 304 when
   unchanged), so a deploy is never hidden behind a stale cache.

   The machinery, one job each: groundwork.web.http (headers, CSP, the
   request's id and address, hooks, gzip, HEAD, no indexing), .files,
   .body, .languages, .page (the page shell) and .server (http-kit)."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.limits :as limits]
   [groundwork.permissions.check :as check]
   [groundwork.postgres :as pg]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [groundwork.web.body :as body]
   [groundwork.web.files :as files]
   [groundwork.web.http :as http]
   [groundwork.web.languages :as languages]
   [groundwork.web.page :as page]
   [reitit.ring :as ring]
   [ring.util.codec :as codec]))

;; =============================================================================
;; What a request carries
;; =============================================================================

(defn nonce
  "This request's nonce, for a handler that writes its own HTML:
   <script nonce=…>."
  [request]
  (::page/nonce request))

(defn request-id
  "This request's id, also sent back as X-Request-Id."
  [request]
  (::http/request-id request))

(defn client-ip
  "The visitor's address: the proxy's header when the request came through a
   trusted proxy, else the connection's."
  [request]
  (::http/client-ip request))

(defn language
  "The request's language: one of the app's :languages."
  [request]
  (::page/language request))

;; =============================================================================
;; The page shell and sessions per route
;; =============================================================================

(def ^:private page-middleware
  {:name    ::page
   :compile (fn [{:keys [page]} {::keys [path]}]
              (when (some? page)
                (page/check-route-page path page)
                (fn [handler]
                  (fn [request]
                    (handler (assoc request ::page/route-page page))))))})

(defn- sessions-middleware
  "Every browser route gets the session and the Origin check; a
   :server-to-server? route gets neither."
  [sessions]
  {:name    ::sessions
   :compile (fn [{:keys [server-to-server? embeddable?]} {::keys [path]}]
              (when-not (or (nil? server-to-server?) (boolean? server-to-server?))
                (throw (ex-info (str "Route " path ": :server-to-server? must be true or false")
                                {:type ::bad-route :path path :server-to-server? server-to-server?})))
              (when-not server-to-server?
                ;; only another site's frame needs the embed cookie
                #(sessions/wrap-sessions % sessions (and (some? embeddable?) (not= :same-origin embeddable?)))))})

;; =============================================================================
;; Access
;; =============================================================================

(defn- wants-page?
  "A browser asking for a page: GET or HEAD that accepts HTML."
  [{:keys [request-method headers]}]
  (and (#{:get :head} request-method)
       (str/includes? (get headers "accept" "") "text/html")))

(defn- signed-out
  "No one is signed in: a browser asking for a page goes to the login page,
   with where it was going; anything else gets 401."
  [{:keys [uri query-string] :as request} login-path]
  (if (wants-page? request)
    {:status  302
     :headers {"Location" (str login-path "?next="
                               (java.net.URLEncoder/encode (str uri (when query-string (str "?" query-string)))
                                                           "UTF-8"))}
     :body    ""}
    (http/plain 401 "Sign in first")))

(defn- param-value
  "A request's value for a declared parameter: the path's, then the form's
   or query's."
  [request k]
  (or (get-in request [:path-params k])
      (get-in request [:params (subs (str k) 1)])))

(defn- record-id
  "An id as the database has it: a UUID, a number, or the text itself."
  [s]
  (when (string? s)
    (or (parse-uuid s) (parse-long s) s)))

(defn- check-access [path access permissions]
  (cond
    (#{:public :signed-in} access)
    access

    (and (map? access) (= #{:action :record} (set (keys access)))
         (keyword? (:action access))
         (vector? (:record access)) (= 2 (count (:record access)))
         (every? keyword? (:record access)))
    (let [{:keys [action] [kind _] :record} access]
      (when-not (check/declared? permissions action kind)
        (throw (ex-info (str "Route " path ": no sentence gives " action " on " kind)
                        {:type ::bad-route :path path :access access})))
      access)

    (and (map? access) (keyword? (:action access)) (some #{:scope :list} (keys access)))
    (throw (ex-info (str "Route " path ": a route checks an action on a :record; "
                         "an action on a :scope or a :list is an endpoint's")
                    {:type ::bad-route :path path :access access}))

    :else
    (throw (ex-info (str "Route " path " must declare :access: :public, :signed-in, "
                         "or {:action … :record [kind param]}")
                    {:type ::undeclared-route :path path :access access}))))

(defn- access-middleware
  "Every route says who may use it; a route without a declaration stops the
   start."
  [permissions login-path]
  {:name    ::access
   :compile (fn [{:keys [access server-to-server?]} {::keys [path]}]
              (if server-to-server?
                (when (some? access)
                  (throw (ex-info (str "Route " path ": a server-to-server route checks the call itself; "
                                       "it has no :access")
                                  {:type ::bad-route :path path :access access})))
                (let [access (check-access path access permissions)]
                  (when-not (= :public access)
                    (fn [handler]
                      (fn [request]
                        (let [person (sessions/person request)]
                          (cond
                            (nil? person)
                            (signed-out request login-path)

                            (= :signed-in access)
                            (handler request)

                            :else
                            (let [{:keys [action] [kind param] :record} access
                                  id (record-id (param-value request param))]
                              (if (and (some? id)
                                       (check/allowed? permissions
                                                             {:person person
                                                              :flags  (sessions/flags request)
                                                              :action action
                                                              :record [kind id]}))
                                (handler request)
                                (http/plain 404 "Not found")))))))))))})

;; =============================================================================
;; Rate limits
;; =============================================================================

(defn- rate-limit-middleware
  "A route's own :rate-limit, or the app's :public one for a public route;
   counted after access, before the handler (see groundwork.limits)."
  [limits]
  {:name    ::rate-limit
   :compile (fn [{:keys [rate-limit access] ::keys [limited-inside?]} {::keys [path]}]
              (when (= :scope (:by rate-limit))
                (throw (ex-info (str "Route " path ": a route's limit counts :person or :client-ip, not :scope")
                                {:type ::bad-route :path path :rate-limit rate-limit})))
              ;; /parquery carries every ask of every screen: each endpoint is
              ;; limited on its own, so the address itself has no default
              (when (and limits (or rate-limit (and (= :public access) (not limited-inside?))))
                (fn [handler]
                  (fn [request]
                    (let [read? (#{:get :head :options} (:request-method request))
                          limit (limits/limit-of limits {:own rate-limit :public? (= :public access) :read? read?})
                          hit   (when limit
                                  (limits/take! limits {:id    (str (name (:request-method request)) " " path)
                                                        :route path
                                                        :limit limit
                                                        :about {:person    (sessions/person request)
                                                                :client-ip (client-ip request)}}))]
                      (if-let [{:keys [retry-after-seconds limit]} hit]
                        (-> (http/plain 429 (str "Too many requests: try again in " retry-after-seconds " seconds"))
                            (assoc-in [:headers "Retry-After"] (str retry-after-seconds))
                            (assoc-in [:headers "X-Rate-Limit"] (name limit)))
                        (handler request)))))))})

;; =============================================================================
;; Pages, Groundwork's own routes, the handler
;; =============================================================================

(defn page
  "A Ring handler answering with the page shell, for a route of the app's own:
   [\"/customize\" {:get web/page :embeddable? true}].

   With a page (a map of a route's :page keys), the shell with that page,
   for a handler that has to look before it knows what to show; the answer
   is a Ring response the handler may change:
   (assoc (web/page request {:title \"No such shop\"}) :status 404)."
  ([request]
   ((::page request) request))
  ([request page]
   ((::page request) (assoc request ::page/route-page page))))

(def ^:private browser-only
  "What only the browser reads in a route record (see groundwork.browser.routing)."
  #{:view :reads :load :placeholder})

(defn page-routes
  "The server's routes for route records the browser shares. An app writes
   its pages once, in a .cljc file both sides read:

     [[\"/orders/:order-id\" {:name   :order
                              :access {:action :orders/see :record [:order :order-id]}
                              :title  \"Order\"
                              :page   {:module :orders}
                              #?@(:cljs [:view views/order :reads views/order-reads])}]]

   and the server's settings take them, with the routes only the server has:

     :routes (into (web/page-routes app.routes/routes)
                   [[\"/webhooks/payments\" {:post payments/webhook :server-to-server? true}]])

   Each record becomes a page: the page shell under the record's :access,
   with its :page details; a :title that is text is the page's own title
   on the first load too. What only the browser reads (:view, :reads, :load,
   :placeholder, a :title that is a function) is left out. Records in a
   group keep their group."
  [records]
  (walk/postwalk
   (fn [x]
     (if (and (vector? x) (string? (first x)) (map? (second x)))
       (update x 1 (fn [{:keys [title] page-details :page :as data}]
                     (cond-> (assoc (apply dissoc data :title browser-only) :get page)
                       (and (string? title) (or (nil? page-details) (map? page-details)))
                       (assoc :page (merge {:title title} page-details)))))
       x))
   records))

(defn- not-found [_request]
  (http/plain 404 "Not found"))

(defn- test-sign-in-route
  "Signs a test in as a person, given the secret; unknown to anyone without it."
  [{:keys [secret]} sessions]
  (let [secret-bytes (.getBytes ^String secret "UTF-8")]
    ["/groundwork/test-sign-in"
     {:server-to-server? true
      :post             (fn [request]
                          (let [given  (get-in request [:headers "x-test-secret"] "")
                                params (let [p (codec/form-decode (or (:query-string request) "") "UTF-8")]
                                         (if (map? p) p {}))
                                person (some-> (get params "person") parse-uuid)]
                            (if (and person
                                     (java.security.MessageDigest/isEqual secret-bytes (.getBytes ^String given "UTF-8")))
                              (sessions/sign-in (assoc request ::sessions/sessions sessions)
                                                {:status 200 :headers {"Content-Type" "text/plain"} :body "signed in"}
                                                person
                                                (cond-> {:flags (into #{} (comp (remove str/blank?) (map keyword))
                                                                      (str/split (get params "flags" "") #","))}
                                                  (get params "hours") (assoc :hours (parse-long (get params "hours")))))
                              (http/plain 404 "Not found"))))}]))

(pg/defsql health-check "groundwork/web/health.sql")

(defn- health-route
  "GET /health: 200 when the database answers, 503 when it doesn't."
  [db]
  ["/health"
   {:access :public
    :get    (fn [_]
              (if (try (pg/row db health-check) (catch Exception _ nil))
                (http/plain 200 "ok")
                (http/plain 503 "database unreachable")))}])

(defn groundwork-routes
  "Groundwork's own routes, mounted next to the app's: /health, the
   endpoints' /parquery, and the test sign-in when it's on."
  [{:keys [test-sign-in sessions endpoints-transport]}]
  (cond-> (into [(health-route (:db sessions))] (:routes endpoints-transport))
    test-sign-in (conj (test-sign-in-route test-sign-in sessions))))

(def ^:private settings-keys
  [:routes :max-body-bytes :content-security-policy :login-path :on-request :on-error :proxies :languages
   :test-sign-in :hashed-files :gzip-min-bytes :unknown-page])

(defn handler
  "The app's Ring handler, for settings already checked."
  [{:keys [routes max-body-bytes content-security-policy login-path languages page sessions permissions
           environment limits]
    :as   settings}]
  (let [files  (files/handler (:hashed-files settings))
        router (ring/router (into (groundwork-routes settings) routes)
                            {:data    {:middleware [page-middleware
                                                    http/embeddable-middleware
                                                    (body/body-middleware max-body-bytes)
                                                    (sessions-middleware sessions)
                                                    http/context-middleware
                                                    (access-middleware permissions login-path)
                                                    (rate-limit-middleware limits)]}
                             ;; the path, for errors in a route's data
                             :compile (fn [[path data] opts]
                                        (ring/compile-result [path data] (assoc opts ::path path)))})
        app    (ring/ring-handler
                router
                (ring/routes files
                             (let [unknown (or (:unknown-page settings) page)]
                               (fn [request] (when (wants-page? request) (unknown request))))
                             not-found))]
    (-> (fn [request] (app (assoc request ::page page)))
        http/wrap-head
        (http/wrap-no-indexing environment)
        (languages/wrap-languages languages (get-in sessions [:settings :origin]))
        (http/wrap-security-headers environment content-security-policy)
        (http/wrap-gzip (:gzip-min-bytes settings))
        (http/wrap-context settings))))

(defn start
  "The router part: {:handler … :max-body-bytes …}, the most any route
   accepts, which the server reads at most. The handler can be swapped for
   one with new routes while the server runs (swap-routes!)."
  [{:keys [settings environment page sessions permissions endpoints-transport limits]}]
  (settings/check-part! :web settings environment)
  (let [inputs  (assoc settings :page page :sessions sessions :permissions permissions :environment environment
                       :endpoints-transport endpoints-transport :limits limits)
        current (atom (handler inputs))
        largest (body/largest-body (:routes settings) (:max-body-bytes settings))]
    {:handler        (fn [request] (@current request))
     :max-body-bytes largest
     ::current       current
     ::inputs        inputs}))

(defn swap-routes!
  "Serve new routes from now on, without restarting the server: for the
   REPL. Checked like at start; when they're wrong, the old routes stay. A
   route taking a bigger body than the server reads needs a restart."
  [{::keys [current inputs] :keys [max-body-bytes]} routes]
  (let [inputs (assoc inputs :routes routes)]
    (settings/check-part! :web (select-keys inputs settings-keys) (:environment inputs))
    (when (> (body/largest-body routes (:max-body-bytes inputs)) max-body-bytes)
      (throw (ex-info "A route takes a bigger body than the running server reads; restart the app"
                      {:type ::restart-needed})))
    (reset! current (handler inputs))
    routes))
