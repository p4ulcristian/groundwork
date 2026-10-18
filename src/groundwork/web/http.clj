(ns groundwork.web.http
  "What every answer gets on its way out, around the router: the security
   headers and the Content-Security-Policy with this request's nonce, who
   may frame a page (:embeddable), the request's id and the client's
   address (believed from a trusted proxy only), the :on-request and
   :on-error hooks, gzip, HEAD answered like GET, and search engines kept
   out outside production. See groundwork.web for the settings."
  (:require
   [clojure.string :as str]
   [groundwork.sessions :as sessions]
   [groundwork.web :as-alias web]
   [groundwork.web.page :as-alias page]
   [reitit.ring :as ring])
  (:import
   (java.io File InputStream)
   (java.lang System$Logger System$Logger$Level)
   (java.net InetAddress)
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
   it. Style attributes stay allowed (style-src-attr): components set them.
   An app that adds 'unsafe-inline' to :style-src gets no nonce there: a
   nonce makes browsers ignore 'unsafe-inline', so its inline <style> tags
   would stop working."
  [csp environment nonce ancestors]
  (let [nonce-src (str "'nonce-" nonce "'")
        inline?   (fn [directive] (some #{"'unsafe-inline'"} (get csp directive)))
        sources   (fn [directive]
                    (cond-> (into (csp-directives directive) (get csp directive))
                      (or (= :script-src directive)
                          (and (= :style-src directive) (not (inline? :style-src)))) (conj nonce-src)
                      ;; the development build loads code with eval
                      (and (= :script-src directive) (= :development environment)) (conj "'unsafe-eval'")
                      ;; and hears from its watcher (new code, a changed
                      ;; stylesheet) over a websocket on the watcher's own port
                      (and (= :connect-src directive) (= :development environment)) (conj "ws:" "wss:")))]
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

(def ^:private security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"
   "Referrer-Policy"        "strict-origin-when-cross-origin"})

(defn wrap-security-headers
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
                    {:type ::web/bad-route :path path :embeddable embeddable}))))

(def embeddable-middleware
  {:name    ::web/embeddable
   :compile (fn [{:keys [embeddable]} {::web/keys [path]}]
              (when (some? embeddable)
                (let [ancestors (frame-ancestors path embeddable)]
                  (fn [handler]
                    (fn [request]
                      (some-> (handler request) (assoc ::frame-ancestors ancestors)))))))})

;; =============================================================================
;; Request ids, the client's address, hooks
;; =============================================================================

(def ^:private ^System$Logger logger (System/getLogger "groundwork.web"))

(defn plain
  "A plain-text answer."
  [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- address-bytes ^bytes [s]
  (when (and (string? s) (re-matches #"[0-9A-Fa-f:.]+" s))
    (try (.getAddress (InetAddress/getByName s)) (catch Exception _ nil))))

(defn address-matcher
  "A predicate for an address or a range, like \"10.0.0.0/8\" or \"::1\"."
  [entry]
  (let [[address bits] (str/split entry #"/" 2)
        ^bytes prefix  (address-bytes address)
        bits           (if bits (parse-long bits) (some-> prefix alength (* 8)))]
    (when (or (nil? prefix) (nil? bits) (neg? bits) (> bits (* 8 (alength prefix))))
      (throw (ex-info (str "Not an address or range: " entry)
                      {:type ::web/bad-setting :setting :proxies :value entry})))
    (fn [s]
      (let [^bytes b (address-bytes s)]
        (and b (= (alength b) (alength prefix))
             (every? (fn [i]
                       (let [mask (unchecked-byte (bit-and 0xFF (bit-shift-left 0xFF (- 8 (min 8 (- bits (* 8 i)))))))]
                         (= (bit-and (aget b i) mask) (bit-and (aget prefix i) mask))))
                     (range (quot (+ bits 7) 8))))))))

(defn- call-hook [hook name m]
  (when hook
    (try (hook m)
         (catch Throwable e
           (.log logger System$Logger$Level/WARNING (str "The " name " hook threw") e)))))

(defn wrap-context
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

(def context-middleware
  "Tells the outer layer which route answered and who asked."
  {:name ::web/context
   :wrap (fn [handler]
           (fn [request]
             (when-let [context (::context request)]
               (vswap! context assoc
                       :route  (:template (ring/get-match request))
                       :person (sessions/person request)
                       :flags  (sessions/flags request)))
             (handler request)))})

;; =============================================================================
;; Gzip, HEAD, no indexing
;; =============================================================================

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

(defn wrap-gzip
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

(defn wrap-no-indexing
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

(defn wrap-head
  "HEAD is answered like GET, without the body: every GET route has one."
  [handler]
  (fn [request]
    (if (= :head (:request-method request))
      (some-> (handler (assoc request :request-method :get)) (assoc :body nil))
      (handler request))))
