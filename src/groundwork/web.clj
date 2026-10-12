(ns groundwork.web
  "The router: the app's routes, as reitit data, next to Groundwork's own;
   files from resources/public; the page shell for every other page a browser
   asks for, so the app's own routing in the browser takes over.

   Route data Groundwork reads:

     [\"/customize\" {:get web/page :embeddable true}]
         a page other sites may show in a frame; true for any https site, or
         a list of origins: [\"https://shop.example\"]
     [\"/payments/webhook\" {:post handle :server-to-server true}]
     [\"/coaters/:slug\" {:get web/page :page (fn [request] {:title …})}]
         the page shell with the route's own title, description, links,
         scripts and server-written body; see groundwork.page
         called by another server (a payment provider's webhook), never by a
         browser: no session is read or made and the Origin check doesn't
         apply, so the handler authenticates the call itself (a signature)

   Every other route is a browser route: it gets the session, and a request
   that changes something must come from the app's own pages.

   Every route declares who may use it, or the app doesn't start:

     :access :public       anyone
     :access :signed-in    someone signed in; a signed-out browser asking for
                           a page goes to :login-path?next=…, anything else 401
     :access {:action :orders/see :record [:order :order-id]}
                           a permission on the record whose id is the path
                           parameter (or form/query parameter) :order-id;
                           not allowed answers 404, like a record that isn't there
     :server-to-server true    instead of :access: the handler checks the call

   Request bodies: none may be larger than :max-body-bytes, or a route's own
   [\"/uploads\" {:post upload :max-body-bytes 104857600}]; a larger one is
   refused with 413. A browser route's form body is parsed into :form-params,
   a multipart one into :multipart-params, its files kept as temporary files
   (deleted after an hour) with the type their bytes really are under
   :real-type (see sniff-type), whatever the browser claimed. Other bodies,
   like JSON, and every server-to-server route's body stay untouched, so a
   webhook can check a signature over the exact bytes.

   Every answer carries the standard security headers, and no page may be
   framed by another site unless its route is :embeddable. In production also
   Strict-Transport-Security. The Content-Security-Policy is strict: scripts
   and styles only from the app's own origin or carrying this request's
   nonce (the page shell stamps it on its own tags), plus what the app adds
   per directive in :content-security-policy, like
   {:script-src [\"https://js.payments.example\"] :frame-src […]}. Development
   also allows eval, which its build needs. Files are revalidated on every load (a 304 when
   unchanged), so a deploy is never hidden behind a stale cache."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.page :as page]
   [groundwork.permissions :as permissions]
   [groundwork.sessions :as sessions]
   [reitit.ring :as ring]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.multipart-params.temp-file :refer [temp-file-store]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]])
  (:import
   (java.io File FileInputStream InputStream)
   (java.nio.charset StandardCharsets)))

;; =============================================================================
;; Security headers and frames
;; =============================================================================

(def ^:private csp-directives
  "The directives an app can add sources to, and what each allows already."
  (array-map
   :default-src ["'self'"]
   :script-src  ["'self'"]
   :style-src   ["'self'"]
   :img-src     ["'self'" "data:" "blob:"]
   :font-src    ["'self'" "data:"]
   :connect-src ["'self'"]
   :media-src   ["'self'" "blob:"]
   :frame-src   ["'self'"]
   :worker-src  ["'self'" "blob:"]
   :form-action ["'self'"]))

(defn- check-csp [csp]
  (when-not (and (map? csp)
                 (every? csp-directives (keys csp))
                 (every? #(and (sequential? %) (every? string? %)) (vals csp)))
    (throw (ex-info (str ":content-security-policy must map directives ("
                         (str/join " " (keys csp-directives)) ") to lists of sources")
                    {:type ::bad-setting :setting :content-security-policy :value csp}))))

(defn- csp-header
  "The policy for one answer: Groundwork's strict defaults, the app's added
   sources, this request's nonce for scripts and styles, and who may frame
   it. Style attributes stay allowed (style-src-attr): components set them."
  [csp environment nonce ancestors]
  (let [nonce-src (str "'nonce-" nonce "'")
        sources   (fn [directive]
                    (cond-> (into (csp-directives directive) (get csp directive))
                      (#{:script-src :style-src} directive) (conj nonce-src)
                      ;; the development build loads code with eval
                      (and (= :script-src directive) (= :development environment)) (conj "'unsafe-eval'")))]
    (str/join "; " (concat (for [directive (keys csp-directives)]
                             (str (name directive) " " (str/join " " (sources directive))))
                           ["style-src-attr 'unsafe-inline'"
                            "object-src 'none'"
                            "base-uri 'self'"
                            (str "frame-ancestors " (or ancestors "'none'"))]))))

(def ^:private random (java.security.SecureRandom.))

(defn- new-nonce []
  (let [bytes (byte-array 16)]
    (.nextBytes random bytes)
    (.encodeToString (java.util.Base64/getEncoder) bytes)))

(defn nonce
  "This request's nonce, for a handler that writes its own HTML:
   <script nonce=…>."
  [request]
  (::page/nonce request))

(def ^:private security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"
   "Referrer-Policy"        "strict-origin-when-cross-origin"})

(defn- wrap-security-headers
  "The headers on every answer, with a nonce made for each request. A
   response that says which sites may frame it (::frame-ancestors, from an
   :embeddable route) names them in frame-ancestors, and gets no
   X-Frame-Options, which can't name another site."
  [handler environment csp]
  (let [headers (cond-> security-headers
                  (= :production environment)
                  (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains"))]
    (fn [request]
      (let [nonce (new-nonce)]
        (when-let [response (handler (assoc request ::page/nonce nonce))]
          (let [ancestors (::frame-ancestors response)
                headers   (cond-> (assoc headers "Content-Security-Policy"
                                         (csp-header csp environment nonce ancestors))
                            ancestors (dissoc "X-Frame-Options"))]
            (-> (dissoc response ::frame-ancestors)
                (update :headers #(merge headers %)))))))))

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

(def ^:private page-middleware
  {:name    ::page
   :compile (fn [{:keys [page]} {::keys [path]}]
              (when (some? page)
                (page/check-route-page path page)
                (fn [handler]
                  (fn [request]
                    (handler (assoc request ::page/route-page page))))))})

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
   :compile (fn [{:keys [server-to-server embeddable]} {::keys [path]}]
              (when-not (or (nil? server-to-server) (boolean? server-to-server))
                (throw (ex-info (str "Route " path ": :server-to-server must be true or false")
                                {:type ::bad-route :path path :server-to-server server-to-server})))
              (when-not server-to-server
                #(sessions/wrap-sessions % sessions (some? embeddable))))})

;; =============================================================================
;; Access
;; =============================================================================

(defn- wants-page?
  "A browser asking for a page: GET or HEAD that accepts HTML."
  [{:keys [request-method headers]}]
  (and (#{:get :head} request-method)
       (str/includes? (get headers "accept" "") "text/html")))

(defn- plain [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

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
    (plain 401 "Sign in first")))

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
      (when-not (permissions/declared? permissions action kind)
        (throw (ex-info (str "Route " path ": no permission rule for " action " on " kind)
                        {:type ::bad-route :path path :access access})))
      access)

    :else
    (throw (ex-info (str "Route " path " must declare :access: :public, :signed-in, "
                         "or {:action … :record [kind param]}")
                    {:type ::undeclared-route :path path :access access}))))

(defn- access-middleware
  "Every route says who may use it; a route without a declaration stops the
   start."
  [permissions login-path]
  {:name    ::access
   :compile (fn [{:keys [access server-to-server]} {::keys [path]}]
              (if server-to-server
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
                                       (permissions/allowed? permissions
                                                             {:person person
                                                              :flags  (sessions/flags request)
                                                              :action action
                                                              :record [kind id]}))
                                (handler request)
                                (plain 404 "Not found")))))))))))})

;; =============================================================================
;; Request bodies
;; =============================================================================

(defn- ascii [^bytes b from to]
  (when (<= to (alength b))
    (String. b (int from) (int (- to from)) StandardCharsets/ISO_8859_1)))

(defn- starts-with-bytes? [^bytes b & xs]
  (and (<= (count xs) (alength b))
       (every? true? (map-indexed (fn [i x] (= (unchecked-byte x) (aget b i))) xs))))

(defn sniff-type
  "The type a file's first bytes say it is, or nil when they say nothing we
   know. Trust this, not the name or the type the browser sent."
  [^bytes head]
  (let [brand (when (= "ftyp" (ascii head 4 8)) (ascii head 8 12))
        text  (ascii head 0 (min 512 (alength head)))]
    (cond
      (starts-with-bytes? head 0x89 0x50 0x4E 0x47)           "image/png"
      (starts-with-bytes? head 0xFF 0xD8 0xFF)                "image/jpeg"
      (= "GIF8" (ascii head 0 4))                             "image/gif"
      (and (= "RIFF" (ascii head 0 4)) (= "WEBP" (ascii head 8 12))) "image/webp"
      (= "%PDF" (ascii head 0 4))                             "application/pdf"
      (starts-with-bytes? head 0x50 0x4B 0x03 0x04)           "application/zip"
      (= "glTF" (ascii head 0 4))                             "model/gltf-binary"
      (#{"heic" "heix" "hevc" "hevx" "heim" "heis"} brand)    "image/heic"
      (#{"mif1" "msf1"} brand)                                "image/heif"
      (#{"avif" "avis"} brand)                                "image/avif"
      (= "qt  " brand)                                        "video/quicktime"
      (some? brand)                                           "video/mp4"
      (some-> text (str/starts-with? "ISO-10303-21"))         "model/step"
      (some-> text (str/starts-with? "solid "))               "model/stl"
      (some-> text str/triml (str/starts-with? "<svg"))       "image/svg+xml"
      (and text (str/starts-with? (str/triml text) "<?xml") (str/includes? text "<svg")) "image/svg+xml"
      :else                                                   nil)))

(defn- file-head ^bytes [^File file]
  (with-open [in (FileInputStream. file)]
    (let [buf (byte-array 512)
          n   (.read in buf)]
      (if (pos? n) (java.util.Arrays/copyOf buf n) (byte-array 0)))))

(defn- with-real-types [params]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (instance? File (:tempfile x)))
                     (assoc x :real-type (sniff-type (file-head (:tempfile x))))
                     x))
                 params))

(defn- too-large [_request]
  {:status 413 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Too large"})

(defn- body-size
  "How many bytes the body has: Content-Length, or what the server read."
  [{:keys [headers body]}]
  (or (some-> (get headers "content-length") parse-long)
      (when (instance? InputStream body) (.available ^InputStream body))
      0))

(defn- body-middleware
  "Every route: no body over its limit. Browser routes: forms and multipart
   parsed, files typed by their bytes."
  [default-limit]
  {:name    ::body
   :compile (fn [{:keys [max-body-bytes server-to-server]} {::keys [path]}]
              (when-not (or (nil? max-body-bytes) (pos-int? max-body-bytes))
                (throw (ex-info (str "Route " path ": :max-body-bytes must be a positive integer")
                                {:type ::bad-route :path path :max-body-bytes max-body-bytes})))
              (let [limit (or max-body-bytes default-limit)]
                (fn [handler]
                  (let [parsed (if server-to-server
                                 handler
                                 (-> (fn [request]
                                       (handler (cond-> request
                                                  (:multipart-params request)
                                                  (update :multipart-params with-real-types))))
                                     (wrap-multipart-params {:store         (temp-file-store {:expires-in 3600})
                                                             :max-file-size limit})
                                     wrap-params))]
                    (fn [request]
                      (if (> (body-size request) limit)
                        (too-large request)
                        (parsed request)))))))})

(defn- largest-body
  "The largest body any route accepts: the server reads no more than this."
  [routes default-limit]
  (let [limits (atom [default-limit])]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (pos-int? (:max-body-bytes x)))
                       (swap! limits conj (:max-body-bytes x)))
                     x)
                   routes)
    (apply max @limits)))

;; =============================================================================
;; Files, the page, the rest
;; =============================================================================

(defn- wrap-file-caching [handler]
  (fn [request]
    (when-let [response (handler request)]
      (assoc-in response [:headers "Cache-Control"] "no-cache"))))

(defn page
  "A Ring handler answering with the page shell, for a route of the app's own:
   [\"/customize\" {:get web/page :embeddable true}]."
  [request]
  ((::page request) request))

(defn- not-found [_request]
  (plain 404 "Not found"))

(defn groundwork-routes
  "Groundwork's own routes, mounted next to the app's."
  []
  [])

(def ^:private settings [:routes :max-body-bytes :content-security-policy :login-path])

(defn- check-settings [opts]
  (when-not (map? opts)
    (throw (ex-info ":web must be a map" {:type ::bad-setting :setting :web :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str ":web needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (let [{:keys [routes max-body-bytes]} opts]
    (when-not (vector? routes)
      (throw (ex-info ":routes must be a vector of reitit routes"
                      {:type ::bad-setting :setting :routes :value routes})))
    (when-not (pos-int? max-body-bytes)
      (throw (ex-info ":max-body-bytes must be a positive integer"
                      {:type ::bad-setting :setting :max-body-bytes :value max-body-bytes})))
    (check-csp (:content-security-policy opts))
    (when-not (and (string? (:login-path opts)) (str/starts-with? (:login-path opts) "/"))
      (throw (ex-info ":login-path must be a path like \"/login\""
                      {:type ::bad-setting :setting :login-path :value (:login-path opts)})))))

(defn handler
  "The app's Ring handler, for settings already checked."
  [{:keys [routes max-body-bytes content-security-policy login-path page sessions permissions environment]}]
  (let [files  (-> (ring/create-resource-handler {:path "/" :root "public"})
                   wrap-file-caching
                   wrap-not-modified)
        router (ring/router (into (groundwork-routes) routes)
                            {:data    {:middleware [page-middleware
                                                    embeddable-middleware
                                                    (body-middleware max-body-bytes)
                                                    (sessions-middleware sessions)
                                                    (access-middleware permissions login-path)]}
                             ;; the path, for errors in a route's data
                             :compile (fn [[path data] opts]
                                        (ring/compile-result [path data] (assoc opts ::path path)))})
        app    (ring/ring-handler
                router
                (ring/routes files
                             (fn [request] (when (wants-page? request) (page request)))
                             not-found))]
    (-> (fn [request] (app (assoc request ::page page)))
        (wrap-security-headers environment content-security-policy))))

(defn start
  "The router part: {:handler … :max-body-bytes …}, the most any route
   accepts, which the server reads at most."
  [{:keys [settings environment page sessions permissions]}]
  (check-settings settings)
  {:handler        (handler (assoc settings :page page :sessions sessions :permissions permissions
                                   :environment environment))
   :max-body-bytes (largest-body (:routes settings) (:max-body-bytes settings))})
