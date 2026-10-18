(ns groundwork.sessions-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings sql!]])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.security MessageDigest)
   (java.util UUID)))

(def ^:dynamic *app* nil)

(def peter (UUID/randomUUID))

(def routes
  [["/login"  {:access :public :post (fn [request]
                       (sessions/sign-in request {:status 200 :body "in"} peter
                                         {:flags #{:impersonated} :data {:login/via "test"}}))}]
   ["/logout" {:access :public :post (fn [request] (sessions/sign-out request {:status 200 :body "out"}))}]
   ["/cart"   {:access :public :post (fn [request]
                       (sessions/put-data request {:status 200 :body "kept"} {:cart/items ["rim" "frame"]}))}]
   ["/login-short" {:access :public :post (fn [request]
                                            (sessions/sign-in request {:status 200} peter
                                                              {:flags #{:impersonated} :hours 1}))}]
   ["/designs" {:access :public :post (fn [request]
                                        (sessions/put-data request {:status 200} {:design/ids ["d1"]}
                                                           {:keep-days 60}))}]
   ["/widget/cart"  {:access :public :post       (fn [request]
                                   (sessions/put-data request {:status 200 :body "kept"} {:cart/items ["hood"]}))
                     :embeddable? true}]
   ["/widget/login" {:access :public :post       (fn [request] (sessions/sign-in request {:status 200} peter))
                     :embeddable? true}]
   ["/robot-login" {:server-to-server? true
                    :post             (fn [request]
                                        (if (= "let-me-in" (get-in request [:headers "x-robot-secret"]))
                                          (sessions/sign-in (sessions/on-request request (gw/sessions (gw/current)))
                                                            {:status 200 :body "in"} peter)
                                          {:status 404 :body "Not found"}))}]
   ["/me"     {:access :public :get (fn [request]
                      {:status 200
                       :body   (pr-str [(some-> (sessions/person request) str)
                                        (sessions/flags request)
                                        (sessions/data request)])})}]])

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (assoc-in app-settings [:web :routes] routes))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private origin (get-in app-settings [:sessions :origin]))

(defn- request [method path & {:keys [cookie origin]}]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                    (.method method (HttpRequest$BodyPublishers/noBody)))]
    (when cookie (.header builder "Cookie" cookie))
    (when origin (.header builder "Origin" origin))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status     (.statusCode response)
       :body       (.body response)
       :set-cookie (first (.allValues (.headers response) "set-cookie"))})))

(defn- login []
  (let [set-cookie (:set-cookie (request "POST" "/login" :origin origin))]
    {:set-cookie set-cookie
     :cookie     (first (str/split set-cookie #";"))}))

(defn- me [cookie]
  (read-string (:body (request "GET" "/me" :cookie cookie))))

(defn- sha-256 [^String s]
  (vec (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(deftest signing-in-and-out
  (let [{:keys [set-cookie cookie]} (login)]
    (testing "the cookie can't be read by scripts or sent from other sites"
      (is (str/includes? set-cookie "HttpOnly"))
      (is (str/includes? set-cookie "SameSite=Lax"))
      (is (str/includes? set-cookie "Max-Age=2592000")))
    (testing "the session knows the person, its flags and the login's data"
      (is (= [(str peter) #{:impersonated} {:login/via "test"}] (me cookie))))
    (testing "the table keeps the key's SHA-256, never the key"
      (let [key  (second (str/split cookie #"=" 2))
            rows (sql! "SELECT session__key_hash FROM groundwork.sessions WHERE session__person_id = ?" peter)]
        (is (some #(= (sha-256 key) (vec (:sessions/session__key_hash %))) rows))))
    (testing "signing out ends the session and clears the cookie"
      (let [out (request "POST" "/logout" :cookie cookie :origin origin)]
        (is (str/includes? (:set-cookie out) "Max-Age=0")))
      (is (= [nil #{} {}] (me cookie))))))

(deftest a-server-to-server-route-can-sign-someone-in
  (testing "no Origin, no session read: the route proves the caller itself"
    (is (= 404 (:status (request "POST" "/robot-login"))))
    (let [response (.send client
                          (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) "/robot-login")))
                              (.header "x-robot-secret" "let-me-in")
                              (.method "POST" (HttpRequest$BodyPublishers/noBody))
                              (.build))
                          (HttpResponse$BodyHandlers/ofString))
          cookie   (first (str/split (first (.allValues (.headers response) "set-cookie")) #";"))]
      (is (= 200 (.statusCode response)))
      (is (= (str peter) (first (me cookie)))))))

(deftest signing-in-ends-the-session-the-request-had
  (let [{old :cookie} (login)
        new           (first (str/split (:set-cookie (request "POST" "/login" :cookie old :origin origin)) #";"))]
    (is (nil? (first (me old))))
    (is (= (str peter) (first (me new))))))

(deftest signing-out-everywhere
  (let [a (:cookie (login))
        b (:cookie (login))]
    (is (<= 2 (sessions/sign-out-everywhere! (gw/part *app* :groundwork/sessions) peter)))
    (is (nil? (first (me a))))
    (is (nil? (first (me b))))))

(deftest an-ended-session-is-nobody
  (let [{:keys [cookie]} (login)]
    (sql! "UPDATE groundwork.sessions SET session__expires_at = now() - interval '1 second'
           WHERE session__person_id = ?" peter)
    (is (nil? (first (me cookie))))))

(deftest a-changing-request-must-come-from-the-apps-pages
  (is (= 403 (:status (request "POST" "/login"))) "no Origin")
  (is (= 403 (:status (request "POST" "/login" :origin "https://evil.example"))) "another site")
  (is (= 200 (:status (request "POST" "/login" :origin origin))))
  (testing "reading needs no Origin"
    (is (= 200 (:status (request "GET" "/me"))))))

(defn- visitor-rows []
  (sql! "SELECT session__id, session__expires_at FROM groundwork.sessions WHERE session__person_id IS NULL"))

(defn- visit-cart
  "A visitor keeps a cart: the cookie of their new visitor session."
  []
  (first (str/split (:set-cookie (request "POST" "/cart" :origin origin)) #";")))

(deftest a-visitor-gets-a-session-only-when-something-is-kept
  (sql! "DELETE FROM groundwork.sessions WHERE session__person_id IS NULL")
  (testing "reading makes nothing: no cookie, no row"
    (let [response (request "GET" "/me")]
      (is (nil? (:set-cookie response)))
      (is (empty? (visitor-rows)))))
  (testing "keeping a cart makes the session"
    (let [cookie (visit-cart)]
      (is (= 1 (count (visitor-rows))))
      (is (= [nil #{} {:cart/items ["rim" "frame"]}] (me cookie)) "a visitor: no person"))))

(deftest a-visitor-session-ends-when-unseen
  (sql! "DELETE FROM groundwork.sessions WHERE session__person_id IS NULL")
  (let [cookie (visit-cart)]
    (testing "a visit pushes the end out again"
      (sql! "UPDATE groundwork.sessions SET session__expires_at = now() + interval '30 minutes'
             WHERE session__person_id IS NULL")
      (me cookie)
      (is (every? #(.isAfter (.toInstant ^java.sql.Timestamp (:sessions/session__expires_at %))
                             (.plusSeconds (java.time.Instant/now) (* 23 3600)))
                  (visitor-rows))))
    (testing "unseen for too long, it's gone"
      (sql! "UPDATE groundwork.sessions SET session__expires_at = now() - interval '1 second'
             WHERE session__person_id IS NULL")
      (is (= [nil #{} {}] (me cookie))))))

(deftest signing-in-keeps-the-visitors-data-under-a-new-key
  (let [visitor    (visit-cart)
        set-cookie (:set-cookie (request "POST" "/login" :cookie visitor :origin origin))
        signed-in  (first (str/split set-cookie #";"))]
    (is (not= visitor signed-in) "a new key")
    (is (= [(str peter) #{:impersonated} {:cart/items ["rim" "frame"] :login/via "test"}] (me signed-in)))
    (testing "the visitor's key is worthless now"
      (is (= [nil #{} {}] (me visitor))))))

(deftest a-framed-page-keeps-its-visitor-in-the-embed-cookie
  (let [set-cookie (:set-cookie (request "POST" "/widget/cart" :origin origin))
        cookie     (first (str/split set-cookie #";"))]
    (testing "a cookie that works in another site's frame"
      (is (str/starts-with? set-cookie "gw_session_embed="))
      (is (str/includes? set-cookie "SameSite=None"))
      (is (str/includes? set-cookie "Secure"))
      (is (str/includes? set-cookie "Partitioned")))
    (testing "the framed page's other requests carry it"
      (is (= [nil #{} {:cart/items ["hood"]}] (me cookie))))
    (testing "signing in from the frame is refused"
      (is (= 500 (:status (request "POST" "/widget/login" :cookie cookie :origin origin)))))
    (testing "signing in on the app's own site keeps the cart, under the Lax cookie"
      (let [set-cookie (:set-cookie (request "POST" "/login" :cookie cookie :origin origin))]
        (is (str/starts-with? set-cookie "gw_session="))
        (is (str/includes? set-cookie "SameSite=Lax"))
        (is (= [(str peter) #{:impersonated} {:cart/items ["hood"] :login/via "test"}]
               (me (first (str/split set-cookie #";")))))
        (is (= [nil #{} {}] (me cookie)) "the visitor's embed key is worthless now")))))

(deftest the-embed-cookie-never-holds-someone-signed-in
  (let [{:keys [cookie]} (login)
        key              (second (str/split cookie #"=" 2))]
    (is (= [nil #{} {}] (me (str "gw_session_embed=" key))))))

(defn- hours-left [rows]
  (map #(/ (- (.getTime ^java.sql.Timestamp (:sessions/session__expires_at %)) (System/currentTimeMillis))
           3600000.0)
       rows))

(deftest a-sign-in-can-have-its-own-lifetime
  (let [set-cookie (:set-cookie (request "POST" "/login-short" :origin origin))
        key        (second (str/split (first (str/split set-cookie #";")) #"=" 2))]
    (is (str/includes? set-cookie "Max-Age=3600"))
    (is (every? #(< 0.9 % 1.01)
                (hours-left (sql! "SELECT session__expires_at FROM groundwork.sessions WHERE session__key_hash = ?"
                                  (byte-array (sha-256 key))))))))

(deftest a-visitor-session-worth-keeping-lives-longer
  (let [set-cookie (:set-cookie (request "POST" "/designs" :origin origin))
        cookie     (first (str/split set-cookie #";"))
        key        (second (str/split cookie #"=" 2))
        rows       #(sql! "SELECT session__expires_at, session__keep_hours FROM groundwork.sessions
                           WHERE session__key_hash = ?" (byte-array (sha-256 key)))]
    (is (str/includes? set-cookie "Max-Age=5184000") "the cookie outlives it too")
    (is (= 1440 (:sessions/session__keep_hours (first (rows)))))
    (is (every? #(< 1439 % 1441) (hours-left (rows))))
    (testing "each visit pushes the end out by its own hours"
      (sql! "UPDATE groundwork.sessions SET session__expires_at = now() + interval '10 days'
             WHERE session__key_hash = ?" (byte-array (sha-256 key)))
      (is (= [nil #{} {:design/ids ["d1"]}] (me cookie)))
      (is (every? #(< 1439 % 1441) (hours-left (rows)))))))

(deftest settings-are-checked
  (let [problems (fn [opts] (mapv :in (:problems (ex-data (try (sessions/start {:settings opts}) (catch Exception e e))))))]
    (is (= 5 (count (problems {}))))
    (is (= [[:sessions :origin]] (problems (assoc (:sessions app-settings) :origin "http://x/path"))))
    (is (= [[:sessions :visitor-hours]] (problems (assoc (:sessions app-settings) :visitor-hours 0))))))
