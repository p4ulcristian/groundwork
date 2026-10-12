(ns groundwork.web
  "The router: the app's routes, as reitit data, next to Groundwork's own;
   files from resources/public; the page shell for every other page a browser
   asks for, so the app's own routing in the browser takes over.

   Route data Groundwork reads:

     [\"/customize\" {:get web/page :embeddable true}]
         a page other sites may show in a frame; true for any https site, or
         a list of origins: [\"https://shop.example\"]
     [\"/payments/webhook\" {:post handle :server-to-server true}]
         called by another server (a payment provider's webhook), never by a
         browser: no session is read or made and the Origin check doesn't
         apply, so the handler authenticates the call itself (a signature)

   Every other route is a browser route: it gets the session, and a request
   that changes something must come from the app's own pages.

   Every answer carries the standard security headers, and no page may be
   framed by another site unless its route is :embeddable. In production also
   Strict-Transport-Security. Files are revalidated on every load (a 304 when
   unchanged), so a deploy is never hidden behind a stale cache."
  (:require
   [clojure.string :as str]
   [groundwork.sessions :as sessions]
   [reitit.ring :as ring]
   [ring.middleware.not-modified :refer [wrap-not-modified]]))

;; =============================================================================
;; Security headers and frames
;; =============================================================================

(def ^:private csp-rest "base-uri 'self'; object-src 'none'")

(def ^:private security-headers
  {"X-Content-Type-Options"  "nosniff"
   "X-Frame-Options"         "DENY"
   "Referrer-Policy"         "strict-origin-when-cross-origin"
   ;; what can't break an app's own scripts and styles
   "Content-Security-Policy" (str "frame-ancestors 'none'; " csp-rest)})

(defn- wrap-security-headers
  "The headers on every answer. A response that says which sites may frame it
   (::frame-ancestors, from an :embeddable route) gets those instead of
   'none', and no X-Frame-Options, which can't name another site."
  [handler environment]
  (let [headers (cond-> security-headers
                  (= :production environment)
                  (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains"))]
    (fn [request]
      (when-let [response (handler request)]
        (let [ancestors (::frame-ancestors response)
              headers   (if ancestors
                          (-> (dissoc headers "X-Frame-Options")
                              (assoc "Content-Security-Policy"
                                     (str "frame-ancestors " ancestors "; " csp-rest)))
                          headers)]
          (-> (dissoc response ::frame-ancestors)
              (update :headers #(merge headers %))))))))

(defn- frame-ancestors
  "The frame-ancestors value for a route's :embeddable, checked when the
   router is built."
  [path embeddable]
  (cond
    (true? embeddable)
    "https:"

    (and (vector? embeddable) (seq embeddable)
         (every? #(and (string? %) (re-matches #"https?://[^/\s;,]+" %)) embeddable))
    (str/join " " embeddable)

    :else
    (throw (ex-info (str "Route " path ": :embeddable must be true or a list of origins like \"https://shop.example\"")
                    {:type ::bad-route :path path :embeddable embeddable}))))

(def ^:private embeddable-middleware
  {:name    ::embeddable
   :compile (fn [{:keys [embeddable]} {::keys [path]}]
              (when (some? embeddable)
                (let [ancestors (frame-ancestors path embeddable)]
                  (fn [handler]
                    (fn [request]
                      (some-> (handler request) (assoc ::frame-ancestors ancestors)))))))})

;; =============================================================================
;; Sessions per route
;; =============================================================================

(defn- sessions-middleware
  "Every browser route gets the session and the Origin check; a
   :server-to-server route gets neither."
  [sessions]
  {:name    ::sessions
   :compile (fn [{:keys [server-to-server]} {::keys [path]}]
              (when-not (or (nil? server-to-server) (boolean? server-to-server))
                (throw (ex-info (str "Route " path ": :server-to-server must be true or false")
                                {:type ::bad-route :path path :server-to-server server-to-server})))
              (when-not server-to-server
                #(sessions/wrap-sessions % sessions)))})

;; =============================================================================
;; Files, the page, the rest
;; =============================================================================

(defn- wrap-file-caching [handler]
  (fn [request]
    (when-let [response (handler request)]
      (assoc-in response [:headers "Cache-Control"] "no-cache"))))

(defn- wants-page?
  "A browser asking for a page: GET or HEAD that accepts HTML."
  [{:keys [request-method headers]}]
  (and (#{:get :head} request-method)
       (str/includes? (get headers "accept" "") "text/html")))

(defn page
  "A Ring handler answering with the page shell, for a route of the app's own:
   [\"/customize\" {:get web/page :embeddable true}]."
  [request]
  ((::page request) request))

(defn- not-found [_request]
  {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not found"})

(defn groundwork-routes
  "Groundwork's own routes, mounted next to the app's."
  []
  [])

(defn handler
  "The app's Ring handler."
  [{:keys [routes page sessions environment]}]
  (when-not (vector? routes)
    (throw (ex-info ":routes must be a vector of reitit routes"
                    {:type ::bad-setting :setting :routes :value routes})))
  (let [files  (-> (ring/create-resource-handler {:path "/" :root "public"})
                   wrap-file-caching
                   wrap-not-modified)
        router (ring/router (into (groundwork-routes) routes)
                            {:data    {:middleware [embeddable-middleware (sessions-middleware sessions)]}
                             ;; the path, for errors in a route's data
                             :compile (fn [[path data] opts]
                                        (ring/compile-result [path data] (assoc opts ::path path)))})
        app    (ring/ring-handler
                router
                (ring/routes files
                             (fn [request] (when (wants-page? request) (page request)))
                             not-found))]
    (-> (fn [request] (app (assoc request ::page page)))
        (wrap-security-headers environment))))

(defn start
  "The router part."
  [{:keys [settings environment page sessions]}]
  (handler {:routes settings :page page :sessions sessions :environment environment}))
