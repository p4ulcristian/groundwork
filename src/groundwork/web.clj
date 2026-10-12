(ns groundwork.web
  "The router: the app's routes, as reitit data, next to Groundwork's own;
   files from resources/public; the page shell for every other page a browser
   asks for, so the app's own routing in the browser takes over.

   Route data Groundwork reads:

     [\"/customize\" {:get web/page :embeddable true}]
         a page other sites may show in a frame; true for any https site, a
         list of origins: [\"https://shop.example\"], or :same-origin for
         only the app's own pages (an editor inside another page)
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

   Languages (or nil for one language):

     :languages {:supported [\"en\" \"de\"] :default \"en\" :cookie \"lang\"}

   A /de/… prefix is taken off before routing; the request's language comes
   from the address, then the cookie (when named), then the browser's
   Accept-Language, then the default (see language). The page shell's lang
   and its alternate links follow it.

   GET /health answers 200 when the database answers, 503 when it doesn't,
   for a load balancer or a monitor.

   Automated tests can sign in without the real login (never in production;
   a setting there stops the start):

     :test-sign-in {:secret \"at least 16 characters\"}   ; or nil
     POST /groundwork/test-sign-in?person=<uuid>&flags=impersonated&hours=1
          with X-Test-Secret: <secret>   → the session cookie

   A preview can be talked to from outside, given a secret (never in
   production either): :eval-bridge, see groundwork.eval-bridge.

   Rate limits: a route's own :rate-limit, or for a :public route the app's
   :public limit (see groundwork.limits); over it, 429 with Retry-After.

   Request bodies: none may be larger than :max-body-bytes, or a route's own
   [\"/uploads\" {:post upload :max-body-bytes 104857600}]; a larger one is
   refused with 413. A browser route's form body is parsed into :form-params,
   a multipart one into :multipart-params, its files kept as temporary files
   (deleted after an hour) with the type their bytes really are under
   :real-type (see sniff-type), whatever the browser claimed. Other bodies,
   like JSON, and every server-to-server route's body stay untouched, so a
   webhook can check a signature over the exact bytes.

   A file whose name carries its content's hash (it matches the regular
   expression :hashed-files, like \"\\\\.[0-9a-f]{8,}\\\\.\", or nil) is cached a year;
   every other file is revalidated on each load.

   Text-like answers of at least :gzip-min-bytes (or nil for none) go
   gzipped to a browser that accepts it.

   Files answer byte ranges (206), so video plays and seeks everywhere. HEAD
   is answered for every GET route, like GET without the body.

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
   [groundwork.eval-bridge :as eval-bridge]
   [groundwork.limits :as limits]
   [groundwork.page :as page]
   [groundwork.permissions :as permissions]
   [groundwork.postgres :as pg]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [reitit.ring :as ring]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.multipart-params.temp-file :refer [temp-file-store]]
   [ring.middleware.not-modified :refer [wrap-not-modified]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.util.codec :as codec])
  (:import
   (java.io File FileInputStream InputStream)
   (java.lang System$Logger System$Logger$Level)
   (java.net InetAddress)
   (java.nio.charset StandardCharsets)
   (java.util UUID)))

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
                            ancestors                (dissoc "X-Frame-Options")
                            (= "'self'" ancestors)   (assoc "X-Frame-Options" "SAMEORIGIN"))]
            (-> (dissoc response ::frame-ancestors)
                (update :headers #(merge headers %)))))))))

(defn- frame-ancestors
  "The frame-ancestors value for a route's :embeddable, checked when the
   router is built."
  [path embeddable]
  (cond
    (true? embeddable)
    "https:"

    (= :same-origin embeddable)
    "'self'"

    (and (vector? embeddable) (seq embeddable)
         (every? #(and (string? %) (re-matches #"https?://[^/\s;,]+" %)) embeddable))
    (str/join " " embeddable)

    :else
    (throw (ex-info (str "Route " path ": :embeddable must be true, :same-origin, or a list of origins like \"https://shop.example\"")
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
                ;; only another site's frame needs the embed cookie
                #(sessions/wrap-sessions % sessions (and (some? embeddable) (not= :same-origin embeddable)))))})

;; =============================================================================
;; Request ids, the client's address, hooks
;; =============================================================================

(def ^:private ^System$Logger logger (System/getLogger "groundwork.web"))

(defn- plain [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- address-bytes ^bytes [s]
  (when (and (string? s) (re-matches #"[0-9A-Fa-f:.]+" s))
    (try (.getAddress (InetAddress/getByName s)) (catch Exception _ nil))))

(defn- address-matcher
  "A predicate for an address or a range, like \"10.0.0.0/8\" or \"::1\"."
  [entry]
  (let [[address bits] (str/split entry #"/" 2)
        ^bytes prefix  (address-bytes address)
        bits           (if bits (parse-long bits) (some-> prefix alength (* 8)))]
    (when (or (nil? prefix) (nil? bits) (neg? bits) (> bits (* 8 (alength prefix))))
      (throw (ex-info (str "Not an address or range: " entry)
                      {:type ::bad-setting :setting :proxies :value entry})))
    (fn [s]
      (let [^bytes b (address-bytes s)]
        (and b (= (alength b) (alength prefix))
             (every? (fn [i]
                       (let [mask (unchecked-byte (bit-and 0xFF (bit-shift-left 0xFF (- 8 (min 8 (- bits (* 8 i)))))))]
                         (= (bit-and (aget b i) mask) (bit-and (aget prefix i) mask))))
                     (range (quot (+ bits 7) 8))))))))

(defn request-id
  "This request's id, also sent back as X-Request-Id."
  [request]
  (::request-id request))

(defn client-ip
  "The visitor's address: the proxy's header when the request came through a
   trusted proxy, else the connection's."
  [request]
  (::client-ip request))

(defn- call-hook [hook name m]
  (when hook
    (try (hook m)
         (catch Throwable e
           (.log logger System$Logger$Level/WARNING (str "The " name " hook threw") e)))))

(defn- wrap-context
  "The outermost layer: the request id and client address, the hooks, and a
   plain 500 for an exception no handler caught."
  [handler {:keys [on-request on-error proxies]}]
  (let [trusted?  (let [matchers (mapv address-matcher (:trusted proxies))]
                    (fn [addr] (some #(% addr) matchers)))
        id-header (some-> (:request-id-header proxies) str/lower-case)
        ip-header (some-> (:client-ip-header proxies) str/lower-case)]
    (fn [request]
      (let [started  (System/nanoTime)
            from-proxy? (trusted? (:remote-addr request))
            client-ip   (or (when (and from-proxy? ip-header)
                              (some-> (get-in request [:headers ip-header]) (str/split #",") first str/trim not-empty))
                            (:remote-addr request))
            request-id  (or (when (and from-proxy? id-header)
                              (some->> (get-in request [:headers id-header]) (re-matches #"[A-Za-z0-9._:\-]{1,128}")))
                            (str (UUID/randomUUID)))
            context     (volatile! {})
            request     (assoc request ::request-id request-id ::client-ip client-ip ::context context)
            about       (fn [] (merge {:method     (:request-method request)
                                       :request-id request-id
                                       :client-ip  client-ip
                                       :person     nil
                                       :flags      #{}
                                       :route      nil}
                                      @context))
            response    (try
                          (handler request)
                          (catch Throwable e
                            (call-hook on-error "on-error" (assoc (about) :error e))
                            (when-not on-error
                              (.log logger System$Logger$Level/ERROR "A request failed" e))
                            (assoc (plain 500 "Something went wrong") ::failed true)))
            response    (some-> response (assoc-in [:headers "X-Request-Id"] request-id))]
        (call-hook on-request "on-request"
                   (assoc (about)
                          :status (:status response 404)
                          :ms     (/ (- (System/nanoTime) started) 1e6)))
        (some-> response (dissoc ::failed))))))

(def ^:private context-middleware
  "Tells the outer layer which route answered and who asked."
  {:name ::context
   :wrap (fn [handler]
           (fn [request]
             (when-let [context (::context request)]
               (vswap! context assoc
                       :route  (:template (ring/get-match request))
                       :person (sessions/person request)
                       :flags  (sessions/flags request)))
             (handler request)))})

;; =============================================================================
;; Languages
;; =============================================================================

(defn- cookie-value [request name]
  (some (fn [pair]
          (let [[k v] (str/split (str/trim pair) #"=" 2)]
            (when (= k name) v)))
        (some-> (get-in request [:headers "cookie"]) (str/split #";"))))

(defn- accepted-languages
  "The browser's Accept-Language, most wanted first, as lowercase tags."
  [header]
  (->> (str/split (or header "") #",")
       (keep (fn [entry]
               (let [[tag & params] (map str/trim (str/split entry #";"))
                     q (or (some #(some-> (re-matches #"q=([0-9.]+)" %) second parse-double) params) 1.0)]
                 (when (and (seq tag) (not= "*" tag)) [(str/lower-case tag) q]))))
       (sort-by (comp - second))
       (map first)))

(defn- pick-language [{:keys [supported default cookie]} request]
  (let [supported? (set supported)]
    (or (some-> cookie (#(cookie-value request %)) supported?)
        (some (fn [tag] (or (supported? tag) (supported? (first (str/split tag #"-")))))
              (accepted-languages (get-in request [:headers "accept-language"])))
        default)))

(defn language
  "The request's language, or nil when the app has one."
  [request]
  (::page/language request))

(defn- wrap-languages
  "Take a /xx/ prefix off the path and pick the request's language; give the
   page shell the language and the same page's address in each language."
  [handler languages origin]
  (if (nil? languages)
    handler
    (let [{:keys [supported default]} languages
          prefix (re-pattern (str "^/(" (str/join "|" supported) ")(/.*)?$"))]
      (fn [request]
        (let [[_ lang rest] (re-matches prefix (:uri request))
              path          (if lang (or rest "/") (:uri request))
              chosen        (or lang (pick-language languages request))
              href          (fn [l] (str origin (when (not= l default) (str "/" l)) (if (= "/" path) (when (= l default) "/") path)))]
          (handler (assoc request
                          :uri                 path
                          ::page/language      chosen
                          ::page/alternates    (conj (mapv (fn [l] {:lang l :href (href l)}) supported)
                                                     {:lang "x-default" :href (href default)}))))))))

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
;; Rate limits
;; =============================================================================

(defn- rate-limit-middleware
  "A route's own :rate-limit, or the app's :public one for a public route;
   counted after access, before the handler (see groundwork.limits)."
  [limits]
  {:name    ::rate-limit
   :compile (fn [{:keys [rate-limit access]} {::keys [path]}]
              (when (= :scope (:by rate-limit))
                (throw (ex-info (str "Route " path ": a route's limit counts :person or :client-ip, not :scope")
                                {:type ::bad-route :path path :rate-limit rate-limit})))
              (when (and limits (or rate-limit (= :public access)))
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
                        (-> (plain 429 (str "Too many requests: try again in " retry-after-seconds " seconds"))
                            (assoc-in [:headers "Retry-After"] (str retry-after-seconds))
                            (assoc-in [:headers "X-Rate-Limit"] (name limit)))
                        (handler request)))))))})

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

(defn- wrap-file-caching
  "A file whose name carries its content's hash (it matches :hashed-files)
   never changes, so browsers keep it a year; every other file is
   revalidated on each load."
  [handler hashed-files]
  (let [hashed (some-> hashed-files re-pattern)]
    (fn [request]
      (when-let [response (handler request)]
        (assoc-in response [:headers "Cache-Control"]
                  (if (and hashed (re-find hashed (:uri request)))
                    "public, max-age=31536000, immutable"
                    "no-cache"))))))

(defn- bounded
  "At most n bytes of in, then the end."
  ^InputStream [^InputStream in n]
  (let [left (atom n)]
    (proxy [java.io.FilterInputStream] [in]
      (read
        ([] (if (pos? @left)
              (let [b (.read in)] (when-not (neg? b) (swap! left dec)) b)
              -1))
        ([^bytes buf] (.read ^InputStream this buf 0 (alength buf)))
        ([^bytes buf off len]
         (if (pos? @left)
           (let [n (.read in buf (int off) (int (min len @left)))]
             (when (pos? n) (swap! left - n))
             n)
           -1)))
      (available [] (int (min (.available in) @left))))))

(defn- body-length [{:keys [body headers]}]
  (cond
    (instance? File body) (.length ^File body)
    :else                 (some-> (get headers "Content-Length") str parse-long)))

(defn- open-body ^InputStream [body]
  (if (instance? File body) (FileInputStream. ^File body) body))

(defn- wrap-ranges
  "Files answer one byte range of themselves (a video player's seeking, which
   Safari needs): 206 with that part, 416 when it's outside the file."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (let [length (body-length response)
            range  (some->> (get-in request [:headers "range"]) (re-matches #"bytes=(\d*)-(\d*)"))]
        (cond
          (or (not= 200 (:status response)) (nil? length))
          response

          (or (nil? range) (= ["" ""] (rest range)))
          (assoc-in response [:headers "Accept-Ranges"] "bytes")

          :else
          (let [[_ from to] range
                [start end] (if (str/blank? from)
                              [(max 0 (- length (parse-long to))) (dec length)]
                              [(parse-long from) (min (dec length) (if (str/blank? to) (dec length) (parse-long to)))])]
            (if (or (>= start length) (> start end))
              {:status  416
               :headers {"Content-Range" (str "bytes */" length)}
               :body    ""}
              (let [in (open-body (:body response))]
                (.skipNBytes in start)
                (-> response
                    (assoc :status 206 :body (bounded in (inc (- end start))))
                    (update :headers assoc
                            "Accept-Ranges"  "bytes"
                            "Content-Range"  (str "bytes " start "-" end "/" length)
                            "Content-Length" (str (inc (- end start)))))))))))))

(defn- compressible? [content-type]
  (let [t (str/lower-case (first (str/split (or content-type "") #";")))]
    (or (str/starts-with? t "text/")
        (#{"application/json" "application/javascript" "application/xml" "application/wasm"
           "application/manifest+json" "image/svg+xml"} t))))

(defn- body-bytes ^bytes [body]
  (cond
    (string? body)                 (.getBytes ^String body "UTF-8")
    (bytes? body)                  body
    (instance? File body)          (java.nio.file.Files/readAllBytes (.toPath ^File body))
    (instance? InputStream body)   (with-open [in ^InputStream body] (.readAllBytes in))
    :else                          nil))

(defn- gzip ^bytes [^bytes b]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [z (java.util.zip.GZIPOutputStream. out)]
      (.write z b))
    (.toByteArray out)))

(defn- wrap-gzip
  "Text-like answers of at least min-bytes go gzipped to a browser that
   accepts it; images, video, archives and fonts are compressed already."
  [handler min-bytes]
  (if (nil? min-bytes)
    handler
    (fn [request]
      (let [response (handler request)
            headers  (:headers response)
            type     (or (get headers "Content-Type") (get headers "content-type"))]
        (if (and response
                 (= 200 (:status response))
                 (some? (:body response))
                 (str/includes? (get-in request [:headers "accept-encoding"] "") "gzip")
                 (not (or (get headers "Content-Encoding") (get headers "Content-Range")))
                 (compressible? type)
                 (or (not (instance? File (:body response))) (<= min-bytes (.length ^File (:body response)))))
          (let [b (body-bytes (:body response))]
            (cond
              (nil? b)                  response
              (< (alength b) min-bytes) (assoc response :body b)
              :else                     (let [z (gzip b)]
                                          (-> response
                                              (assoc :body z)
                                              (update :headers assoc
                                                      "Content-Encoding" "gzip"
                                                      "Vary"             "Accept-Encoding"
                                                      "Content-Length"   (str (alength z)))))))
          response)))))

(defn- wrap-no-indexing
  "Outside production, search engines are kept out: /robots.txt disallows
   everything, whatever the app serves there in production, and every
   answer says noindex."
  [handler environment]
  (if (= :production environment)
    handler
    (fn [request]
      (some-> (if (= "/robots.txt" (:uri request))
                {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "User-agent: *\nDisallow: /\n"}
                (handler request))
              (assoc-in [:headers "X-Robots-Tag"] "noindex, nofollow")))))

(defn- wrap-head
  "HEAD is answered like GET, without the body: every GET route has one."
  [handler]
  (fn [request]
    (if (= :head (:request-method request))
      (some-> (handler (assoc request :request-method :get)) (assoc :body nil))
      (handler request))))

(defn page
  "A Ring handler answering with the page shell, for a route of the app's own:
   [\"/customize\" {:get web/page :embeddable true}]."
  [request]
  ((::page request) request))

(defn- not-found [_request]
  (plain 404 "Not found"))

(defn- test-sign-in-route
  "Signs a test in as a person, given the secret; unknown to anyone without it."
  [{:keys [secret]} sessions]
  (let [secret-bytes (.getBytes ^String secret "UTF-8")]
    ["/groundwork/test-sign-in"
     {:server-to-server true
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
                              (plain 404 "Not found"))))}]))

(pg/defsql health-check "groundwork/health.sql")

(defn- health-route
  "GET /health: 200 when the database answers, 503 when it doesn't."
  [db]
  ["/health"
   {:access :public
    :get    (fn [_]
              (if (try (pg/row db health-check) (catch Exception _ nil))
                (plain 200 "ok")
                (plain 503 "database unreachable")))}])

(defn groundwork-routes
  "Groundwork's own routes, mounted next to the app's: /health, parquery's
   /parquery, and the test sign-in and the eval bridge when they're on."
  [{:keys [test-sign-in eval-bridge sessions parquery]}]
  (cond-> (into [(health-route (:db sessions))] (:routes parquery))
    test-sign-in (conj (test-sign-in-route test-sign-in sessions))
    eval-bridge  (conj (eval-bridge/route eval-bridge))))

(def ^:private settings-keys
  [:routes :max-body-bytes :content-security-policy :login-path :on-request :on-error :proxies :languages
   :test-sign-in :eval-bridge :hashed-files :gzip-min-bytes])

(defn handler
  "The app's Ring handler, for settings already checked."
  [{:keys [routes max-body-bytes content-security-policy login-path languages page sessions permissions
           environment limits]
    :as   settings}]
  (let [files  (-> (ring/create-resource-handler {:path "/" :root "public"})
                   (wrap-file-caching (:hashed-files settings))
                   wrap-not-modified
                   wrap-ranges)
        router (ring/router (into (groundwork-routes settings) routes)
                            {:data    {:middleware [page-middleware
                                                    embeddable-middleware
                                                    (body-middleware max-body-bytes)
                                                    (sessions-middleware sessions)
                                                    context-middleware
                                                    (access-middleware permissions login-path)
                                                    (rate-limit-middleware limits)]}
                             ;; the path, for errors in a route's data
                             :compile (fn [[path data] opts]
                                        (ring/compile-result [path data] (assoc opts ::path path)))})
        app    (ring/ring-handler
                router
                (ring/routes files
                             (fn [request] (when (wants-page? request) (page request)))
                             not-found))]
    (-> (fn [request] (app (assoc request ::page page)))
        wrap-head
        (wrap-no-indexing environment)
        (wrap-languages languages (get-in sessions [:settings :origin]))
        (wrap-security-headers environment content-security-policy)
        (wrap-gzip (:gzip-min-bytes settings))
        (wrap-context settings))))

(defn start
  "The router part: {:handler … :max-body-bytes …}, the most any route
   accepts, which the server reads at most. The handler can be swapped for
   one with new routes while the server runs (swap-routes!)."
  [{:keys [settings environment page sessions permissions parquery limits]}]
  (settings/check-part! :web settings environment)
  (let [inputs  (assoc settings :page page :sessions sessions :permissions permissions :environment environment
                       :parquery parquery :limits limits)
        current (atom (handler inputs))
        largest (largest-body (:routes settings) (:max-body-bytes settings))]
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
    (when (> (largest-body routes (:max-body-bytes inputs)) max-body-bytes)
      (throw (ex-info "A route takes a bigger body than the running server reads; restart the app"
                      {:type ::restart-needed})))
    (reset! current (handler inputs))
    routes))
