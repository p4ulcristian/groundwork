(ns groundwork.web-test
  "A real server on a free port, asked over HTTP."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.page :as page]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:dynamic *app* nil)

(def routes
  [["/api/hello" {:access :public :get (fn [_] {:status 200 :body "hi"})}]
   ["/api/thread" {:access :public :get (fn [_] {:status 200 :body (str (.isVirtual (Thread/currentThread)))})}]
   ["/api/echo" {:access :public :post (fn [_] {:status 200 :body "changed"})}]
   ["/customize" {:access :public :get web/page :embeddable true}]
   ["/partner-widget" {:access :public :get web/page :embeddable ["https://shop.example" "https://other.example"]}]
   ["/api/big" {:access :public :get (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                              :body (str "[" (apply str (interpose "," (repeat 600 "\"powder\""))) "]")})}]
   ["/api/png" {:access :public :get (fn [_] {:status 200 :headers {"Content-Type" "image/png"}
                                              :body (apply str (repeat 3000 "x"))})}]
   ["/label-editor" {:access :public :get web/page :embeddable :same-origin}]
   ["/payments/webhook" {:post             (fn [request]
                                             {:status 200
                                              :body   (pr-str [(sessions/person request)
                                                               (contains? request ::sessions/sessions)])})
                         :server-to-server true}]
   ["/coaters/:slug" {:access :public :get  web/page
                      :page (fn [request]
                              (let [slug (get-in request [:path-params :slug])]
                                {:title       (str "Coater " slug)
                                 :description "Powder coating & more"
                                 :canonical   (str "https://acme.example/coaters/" slug)
                                 :alternates  [{:lang "de" :href (str "https://acme.example/de/coaters/" slug)}]
                                 :stylesheets ["/coaters.css"]
                                 :scripts     ["/js/map.js"]
                                 :body        (str "<h1>" slug "</h1>")}))}]
   ["/about" {:access :public :get web/page :page {:title "About us"}}]
   ["/api/form" {:access :public :post (fn [request] {:status 200 :body (pr-str (:form-params request))})}]
   ["/api/upload" {:access :public :post           (fn [request]
                                     {:status 200
                                      :body   (pr-str (-> (get-in request [:multipart-params "file"])
                                                          (select-keys [:filename :content-type :real-type :size])))})
                   :max-body-bytes 3000000}]
   ["/hooks/raw" {:post             (fn [request]
                                      {:status 200
                                       :body   (pr-str [(slurp (:body request)) (:form-params request)])})
                  :server-to-server true}]])

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (-> app-settings
                            (assoc-in [:web :routes] routes)
                            (assoc-in [:page :title] "Acme <Coating> & Co")))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- request
  ([path] (request path {}))
  ([path {:keys [method headers body] :or {method "GET"}}]
   (let [publisher (cond (nil? body)    (HttpRequest$BodyPublishers/noBody)
                         (string? body) (HttpRequest$BodyPublishers/ofString body)
                         :else          (HttpRequest$BodyPublishers/ofByteArray body))
         builder   (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                       (.method method publisher))]
     (doseq [[k v] headers] (.header builder k v))
     (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
       {:status  (.statusCode response)
        :body    (.body response)
        :headers (into {} (map (fn [[k vs]] [k (first vs)])) (.map (.headers response)))}))))

(def html {"Accept" "text/html,application/xhtml+xml"})

(deftest the-apps-routes-answer
  (is (= {:status 200 :body "hi"} (select-keys (request "/api/hello") [:status :body])))
  (testing "every request runs on a virtual thread"
    (is (= "true" (:body (request "/api/thread"))))))

(deftest every-answer-has-the-security-headers
  (doseq [path ["/api/hello" "/" "/app.css" "/nowhere"]]
    (let [headers (:headers (request path {:headers html}))]
      (is (= "nosniff" (headers "x-content-type-options")) path)
      (is (= "DENY" (headers "x-frame-options")) path)
      (is (str/includes? (headers "content-security-policy") "frame-ancestors 'none'") path)
      (is (str/includes? (headers "content-security-policy") "script-src 'self' 'nonce-") path)))
  (testing "Strict-Transport-Security only in production"
    (is (nil? ((:headers (request "/api/hello")) "strict-transport-security")))))

(deftest an-embeddable-page-may-be-framed-by-other-sites
  (testing "any https site"
    (let [{:keys [status body headers]} (request "/customize" {:headers html})]
      (is (= 200 status))
      (is (str/includes? body "<div id=\"app\"></div>") "the page shell")
      (is (str/includes? (headers "content-security-policy") "frame-ancestors https:"))
      (is (nil? (headers "x-frame-options")))
      (is (= "nosniff" (headers "x-content-type-options")) "the other headers stay")))
  (testing "a list of sites"
    (let [headers (:headers (request "/partner-widget" {:headers html}))]
      (is (str/includes? (headers "content-security-policy")
                         "frame-ancestors https://shop.example https://other.example"))
      (is (nil? (headers "x-frame-options")))))
  (testing "only the app's own pages"
    (let [headers (:headers (request "/label-editor" {:headers html}))]
      (is (str/includes? (headers "content-security-policy") "frame-ancestors 'self'"))
      (is (= "SAMEORIGIN" (headers "x-frame-options")))))
  (testing "every other page stays unframeable"
    (let [headers (:headers (request "/shops/7/orders" {:headers html}))]
      (is (str/includes? (headers "content-security-policy") "frame-ancestors 'none'"))
      (is (= "DENY" (headers "x-frame-options"))))))

(deftest a-route-declares-embeddable-properly
  (is (= ::web/bad-route
         (:type (ex-data (try (web/handler {:routes [["/x" {:get web/page :access :public :embeddable ["shop.example"]}]]
                                            :max-body-bytes 1000 :content-security-policy {} :login-path "/login"
                                            :proxies {:trusted [] :client-ip-header nil :request-id-header nil} :languages nil :test-sign-in nil :eval-bridge nil :hashed-files nil :gzip-min-bytes nil
                                            :page (fn [_]) :sessions {} :environment :test})
                              (catch Exception e e)))))))

(deftest a-server-to-server-route-is-not-a-browser-route
  (testing "a POST without Origin is accepted, with no session read"
    (let [{:keys [status body]} (request "/payments/webhook" {:method "POST"})]
      (is (= 200 status))
      (is (= "[nil false]" body))))
  (testing "a browser route still refuses a POST without Origin"
    (is (= 403 (:status (request "/api/echo" {:method "POST"}))))
    (is (= 200 (:status (request "/api/echo" {:method  "POST"
                                              :headers {"Origin" (:origin (:sessions app-settings))}}))))))

(def ^:private own-origin {"Origin" (:origin (:sessions app-settings))})

(defn- multipart
  "A multipart body with one file: [content-type bytes]."
  [filename claimed-type ^bytes content]
  (let [boundary "gwboundary7MA4YWxkTrZu0gW"
        out      (java.io.ByteArrayOutputStream.)
        write    #(.write out (.getBytes ^String % "UTF-8"))]
    (write (str "--" boundary "\r\n"
                "Content-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\n"
                "Content-Type: " claimed-type "\r\n\r\n"))
    (.write out content)
    (write (str "\r\n--" boundary "--\r\n"))
    [(str "multipart/form-data; boundary=" boundary) (.toByteArray out)]))

(def ^:private png (byte-array (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (repeat 100 0))))

(deftest request-bodies
  (testing "a form is parsed"
    (is (= "{\"color\" \"RAL 9005\"}"
           (:body (request "/api/form" {:method  "POST"
                                        :headers (assoc own-origin "Content-Type" "application/x-www-form-urlencoded")
                                        :body    "color=RAL+9005"})))))
  (testing "a file is kept with the type its bytes are, whatever the browser said"
    (let [[content-type body] (multipart "drawing.pdf" "application/pdf" png)]
      (is (= {:filename "drawing.pdf" :content-type "application/pdf" :real-type "image/png" :size 108}
             (read-string (:body (request "/api/upload" {:method  "POST"
                                                         :headers (assoc own-origin "Content-Type" content-type)
                                                         :body    body})))))))
  (testing "no body over the route's limit"
    (is (= 413 (:status (request "/api/form" {:method  "POST"
                                              :headers (assoc own-origin "Content-Type" "application/x-www-form-urlencoded")
                                              :body    (apply str "a=" (repeat 1100000 "x"))}))))
    (let [[content-type body] (multipart "big.bin" "application/octet-stream" (byte-array 2000000))]
      (is (= 200 (:status (request "/api/upload" {:method  "POST"
                                                  :headers (assoc own-origin "Content-Type" content-type)
                                                  :body    body})))
          "an upload route takes more"))
    (let [[content-type body] (multipart "huge.bin" "application/octet-stream" (byte-array 3500000))]
      (is (= 413 (:status (request "/api/upload" {:method  "POST"
                                                  :headers (assoc own-origin "Content-Type" content-type)
                                                  :body    body}))))))
  (testing "a server-to-server route gets its body untouched"
    (is (= ["a=1&b=2" nil]
           (read-string (:body (request "/hooks/raw" {:method  "POST"
                                                      :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                                      :body    "a=1&b=2"})))))))

(deftest file-types-come-from-their-bytes
  (is (= "image/png" (web/sniff-type png)))
  (is (= "image/jpeg" (web/sniff-type (byte-array [0xFF 0xD8 0xFF 0xE0 0 0]))))
  (is (= "application/pdf" (web/sniff-type (.getBytes "%PDF-1.7 …"))))
  (is (= "image/heic" (web/sniff-type (.getBytes "\u0000\u0000\u0000\u0018ftypheic\u0000\u0000"))))
  (is (= "model/step" (web/sniff-type (.getBytes "ISO-10303-21;\nHEADER;"))))
  (is (nil? (web/sniff-type (.getBytes "just text")))))

(deftest a-browser-gets-the-page-for-any-other-path
  (doseq [path ["/" "/shops/7/orders"]]
    (let [{:keys [status body headers]} (request path {:headers html})]
      (is (= 200 status) path)
      (is (str/includes? body "<div id=\"app\"></div>"))
      (is (re-find #"<script nonce=\"[^\"]+\" src=\"/js/main.js\" defer></script>" body))
      (is (str/includes? body "<link rel=\"stylesheet\" href=\"/app.css\">"))
      (is (= "no-store" (headers "cache-control")))))
  (testing "the title is escaped"
    (is (str/includes? (:body (request "/" {:headers html}))
                       "<title>Acme &lt;Coating&gt; &amp; Co</title>"))))

(deftest a-route-gives-its-own-page
  (let [body (:body (request "/coaters/zinc" {:headers html}))]
    (is (str/includes? body "<title>Coater zinc</title>"))
    (is (str/includes? body "<meta name=\"description\" content=\"Powder coating &amp; more\">"))
    (is (str/includes? body "<link rel=\"canonical\" href=\"https://acme.example/coaters/zinc\">"))
    (is (str/includes? body "<link rel=\"alternate\" hreflang=\"de\" href=\"https://acme.example/de/coaters/zinc\">"))
    (is (str/includes? body "<link rel=\"stylesheet\" href=\"/app.css\">") "the app's stylesheets stay")
    (is (str/includes? body "<link rel=\"stylesheet\" href=\"/coaters.css\">"))
    (is (re-find #"<script nonce=\"[^\"]+\" src=\"/js/main.js\" defer></script>" body))
    (is (re-find #"<script nonce=\"[^\"]+\" src=\"/js/map.js\" defer></script>" body))
    (is (str/includes? body "<div id=\"app\"><h1>zinc</h1></div>")))
  (testing "a map works too, the rest from the app"
    (let [body (:body (request "/about" {:headers html}))]
      (is (str/includes? body "<title>About us</title>"))
      (is (str/includes? body "<div id=\"app\"></div>"))))
  (testing "a route's page keys are checked when the router is built"
    (is (= ::page/bad-route
           (:type (ex-data (try (web/handler {:routes [["/x" {:get web/page :access :public :page {:titel "x"}}]]
                                              :max-body-bytes 1000 :content-security-policy {} :login-path "/login"
                                            :proxies {:trusted [] :client-ip-header nil :request-id-header nil} :languages nil :test-sign-in nil :eval-bridge nil :hashed-files nil :gzip-min-bytes nil
                                              :page (fn [_]) :sessions {} :environment :test})
                                (catch Exception e e))))))))

(deftest a-strict-content-security-policy
  (let [{:keys [body headers]} (request "/coaters/zinc" {:headers html})
        policy                 (headers "content-security-policy")
        nonce                  (second (re-find #"'nonce-([^']+)'" policy))]
    (testing "scripts and styles only from the app or with this request's nonce"
      (is (str/includes? policy (str "script-src 'self' 'nonce-" nonce "'")))
      (is (str/includes? policy (str "style-src 'self' 'nonce-" nonce "'")))
      (is (str/includes? policy "object-src 'none'"))
      (is (not (str/includes? policy "unsafe-eval")) "eval only in development"))
    (testing "the app's added sources"
      (is (str/includes? policy "connect-src 'self' https://api.example")))
    (testing "the shell's scripts carry the nonce"
      (is (str/includes? body (str "<script nonce=\"" nonce "\" src=\"/js/main.js\""))))
    (testing "a new nonce every request"
      (is (not= policy ((:headers (request "/coaters/zinc" {:headers html})) "content-security-policy"))))))

(deftest inline-tags-get-the-nonce
  (let [page (page/start {:settings (assoc (:page app-settings)
                                           :head ["<script>window.x=1</script>" "<style>a{}</style>"
                                                  "<script nonce=\"mine\">y()</script>"])})
        body (:body (page {::page/nonce "N1"}))]
    (is (str/includes? body "<script nonce=\"N1\">window.x=1</script>"))
    (is (str/includes? body "<style nonce=\"N1\">a{}</style>"))
    (is (str/includes? body "<script nonce=\"mine\">y()</script>") "a nonce already there stays")))

(deftest files-are-served-and-revalidated
  (let [{:keys [status body headers]} (request "/app.css")]
    (is (= 200 status))
    (is (= "body { margin: 0; }\n" body))
    (is (= "no-cache" (headers "cache-control")))
    (testing "an unchanged file answers 304"
      (is (= 304 (:status (request "/app.css" {:headers {"If-Modified-Since" (headers "last-modified")}})))))))

(deftest a-hashed-file-is-cached-for-a-year
  (is (= "public, max-age=31536000, immutable"
         ((:headers (request "/app.3f9a1c2b7d.js")) "cache-control")))
  (is (= "no-cache" ((:headers (request "/app.css")) "cache-control"))))

(deftest files-answer-byte-ranges
  (is (= "bytes" ((:headers (request "/app.css")) "accept-ranges")))
  (let [{:keys [status body headers]} (request "/app.css" {:headers {"Range" "bytes=0-3"}})]
    (is (= [206 "body" "bytes 0-3/20"] [status body (headers "content-range")])))
  (is (= "margin: 0; }\n" (:body (request "/app.css" {:headers {"Range" "bytes=7-"}}))))
  (is (= "; }\n" (:body (request "/app.css" {:headers {"Range" "bytes=-4"}}))) "the last bytes")
  (let [{:keys [status headers]} (request "/app.css" {:headers {"Range" "bytes=100-200"}})]
    (is (= [416 "bytes */20"] [status (headers "content-range")]))))

(deftest head-is-answered-like-get
  (let [{:keys [status body]} (request "/api/hello" {:method "HEAD"})]
    (is (= 200 status))
    (is (= "" body)))
  (is (= 200 (:status (request "/app.css" {:method "HEAD"}))))
  (is (= 200 (:status (request "/about" {:method "HEAD" :headers html})))))

(deftest no-indexing-outside-production
  (let [{:keys [status body]} (request "/robots.txt")]
    (is (= [200 "User-agent: *\nDisallow: /\n"] [status body])))
  (is (= "noindex, nofollow" ((:headers (request "/api/hello")) "x-robots-tag")))
  (testing "production serves what the app serves"
    (let [handler (#'web/wrap-no-indexing (fn [_] {:status 200 :headers {} :body "app's own"}) :production)]
      (is (= {:status 200 :headers {} :body "app's own"} (handler {:uri "/robots.txt"}))))))

(deftest text-goes-gzipped
  (let [gz {"Accept-Encoding" "gzip, br"}
        {:keys [headers body]} (request "/api/big" {:headers gz})]
    (is (= "gzip" (headers "content-encoding")))
    (is (= "Accept-Encoding" (headers "vary")))
    (is (< (count body) 1000) "smaller on the wire"))
  (testing "not when the browser doesn't ask, not small, not images"
    (is (nil? ((:headers (request "/api/big")) "content-encoding")))
    (is (nil? ((:headers (request "/api/hello" {:headers {"Accept-Encoding" "gzip"}})) "content-encoding")))
    (is (nil? ((:headers (request "/api/png" {:headers {"Accept-Encoding" "gzip"}})) "content-encoding")))))

(deftest health
  (is (= [200 "ok"] ((juxt :status :body) (request "/health"))))
  (testing "no database, 503"
    (let [[_ {:keys [get]}] (#'web/health-route nil)]
      (is (= 503 (:status (get {})))))))

(deftest anything-else-is-not-found
  (is (= 404 (:status (request "/missing.png" {:headers {"Accept" "image/*"}}))))
  (is (= 404 (:status (request "/" {:method "POST" :headers (assoc html "Origin" (:origin (:sessions app-settings)))}))))
  (is (= 404 (:status (request "/api/nowhere")))))

(deftest page-settings-are-checked
  (let [problems (fn [page] (mapv :in (:problems (ex-data (try (page/start {:settings page}) (catch Exception e e))))))]
    (is (= [[:page :lang] [:page :description] [:page :icon] [:page :stylesheets] [:page :script] [:page :head]]
           (problems {:title "x"})))
    (is (= [[:page :stylesheets]] (problems (assoc (:page app-settings) :stylesheets "x"))))))
