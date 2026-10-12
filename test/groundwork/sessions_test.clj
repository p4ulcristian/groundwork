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
  [["/login"  {:post (fn [request]
                       (sessions/sign-in request {:status 200 :body "in"} peter
                                         {:flags #{:impersonated} :data {:login/via "test"}}))}]
   ["/logout" {:post (fn [request] (sessions/sign-out request {:status 200 :body "out"}))}]
   ["/me"     {:get (fn [request]
                      {:status 200
                       :body   (pr-str [(some-> (sessions/person request) str)
                                        (sessions/flags request)
                                        (sessions/data request)])})}]])

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (assoc app-settings :routes routes))]
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

(deftest settings-are-checked
  (is (= ::sessions/missing-settings
         (:type (ex-data (try (sessions/start {:settings {}}) (catch Exception e e))))))
  (is (= ::sessions/bad-setting
         (:type (ex-data (try (sessions/start {:settings (assoc (:sessions app-settings) :origin "http://x/path")})
                              (catch Exception e e)))))))
