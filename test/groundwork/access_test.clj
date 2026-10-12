(ns groundwork.access-test
  "Every route declares who may use it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.permissions :as perm]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings sql!]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util UUID)))

(def shop (UUID/randomUUID))
(def other-shop (UUID/randomUUID))
(def owner (UUID/randomUUID))
(def stranger (UUID/randomUUID))
(def note (UUID/randomUUID))
(def other-note (UUID/randomUUID))

(def description
  {:scopes         {:shop {:actions #{:notes/see} :roles {} :parent nil}}
   :records        {:note {:table :notes :id :note/id :in [:shop :note/shop-id]}}
   :links          []
   :session-blocks {}})

(defn- ok [_] {:status 200 :body "ok"})

(def routes
  [["/login"            {:access :public
                         :post   (fn [request]
                                   (sessions/sign-in request {:status 200 :body "in"}
                                                     (parse-uuid (get-in request [:params "person"]))))}]
   ["/open"             {:access :public :get ok}]
   ["/mine"             {:access :signed-in :get ok}]
   ["/notes/:note-id"   {:access {:action :notes/see :record [:note :note-id]} :get ok}]
   ["/notes"            {:access {:action :notes/see :record [:note :note-id]} :post ok}]])

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS notes;
           CREATE TABLE notes (note__id uuid PRIMARY KEY, note__shop_id uuid NOT NULL)")
    (sql! "INSERT INTO notes VALUES (?, ?), (?, ?)" note shop other-note other-shop)
    (let [app (gw/start (-> app-settings
                            (assoc-in [:web :routes] routes)
                            (assoc :permissions description)))]
      (try
        (perm/create-scope! (gw/permissions app) {:kind :shop :id shop :parent nil :owner owner})
        (perm/create-scope! (gw/permissions app) {:kind :shop :id other-shop :parent nil :owner stranger})
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS notes"))))))

(def ^:private client (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) .build))
(def ^:private origin (get-in app-settings [:sessions :origin]))

(defn- request [method path & {:keys [cookie accept body]}]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                    (.method method (if body
                                      (HttpRequest$BodyPublishers/ofString body)
                                      (HttpRequest$BodyPublishers/noBody))))]
    (when cookie (.header builder "Cookie" cookie))
    (when accept (.header builder "Accept" accept))
    (when (not= "GET" method)
      (.header builder "Origin" origin)
      (.header builder "Content-Type" "application/x-www-form-urlencoded"))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status   (.statusCode response)
       :location (first (.allValues (.headers response) "location"))
       :cookie   (some-> (first (.allValues (.headers response) "set-cookie")) (str/split #";") first)})))

(defn- sign-in [person]
  (:cookie (request "POST" "/login" :body (str "person=" person))))

(deftest public-and-signed-in
  (is (= 200 (:status (request "GET" "/open"))))
  (testing "signed out: a page goes to the login, anything else is 401"
    (let [{:keys [status location]} (request "GET" "/mine?tab=2" :accept "text/html")]
      (is (= 302 status))
      (is (= "/login?next=%2Fmine%3Ftab%3D2" location)))
    (is (= 401 (:status (request "GET" "/mine")))))
  (is (= 200 (:status (request "GET" "/mine" :cookie (sign-in stranger))))))

(deftest a-permission-on-the-record-in-the-request
  (let [owner-cookie    (sign-in owner)
        stranger-cookie (sign-in stranger)]
    (is (= 200 (:status (request "GET" (str "/notes/" note) :cookie owner-cookie))))
    (testing "another shop's record is not found"
      (is (= 404 (:status (request "GET" (str "/notes/" other-note) :cookie owner-cookie))))
      (is (= 404 (:status (request "GET" (str "/notes/" note) :cookie stranger-cookie)))))
    (testing "the id can come from the form"
      (is (= 200 (:status (request "POST" "/notes" :cookie owner-cookie :body (str "note-id=" note)))))
      (is (= 404 (:status (request "POST" "/notes" :cookie owner-cookie :body (str "note-id=" other-note))))))
    (is (= 401 (:status (request "GET" (str "/notes/" note)))) "signed out")))

(deftest tests-can-sign-in-with-the-secret
  (let [sign-in (fn [secret]
                  (let [builder (-> (HttpRequest/newBuilder
                                     (URI. (str "http://127.0.0.1:" (gw/port *app*)
                                                "/groundwork/test-sign-in?person=" owner "&flags=impersonated&hours=1")))
                                    (.POST (HttpRequest$BodyPublishers/noBody)))]
                    (when secret (.header builder "X-Test-Secret" secret))
                    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
                      {:status (.statusCode response)
                       :cookie (some-> (first (.allValues (.headers response) "set-cookie")) (str/split #";") first)})))]
    (let [{:keys [status cookie]} (sign-in (get-in app-settings [:web :test-sign-in :secret]))]
      (is (= 200 status))
      (is (= 200 (:status (request "GET" (str "/notes/" note) :cookie cookie)))))
    (is (= 404 (:status (sign-in "a-wrong-secret-of-some-length"))))
    (is (= 404 (:status (sign-in nil)))))
  (testing "never in production"
    (is (= ::web/bad-setting
           (:type (ex-data (try (web/start {:settings    (:web app-settings)
                                            :environment :production
                                            :page ok :sessions {} :permissions (gw/permissions *app*)})
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest a-route-without-a-declaration-stops-the-start
  (let [build (fn [routes]
                (try (web/handler {:routes routes :max-body-bytes 1000 :content-security-policy {}
                                   :login-path "/login" :page ok :sessions {} :environment :test
                                   :proxies {:trusted [] :client-ip-header nil :request-id-header nil} :languages nil :test-sign-in nil :hashed-files nil :gzip-min-bytes nil
                                   :permissions (gw/permissions *app*)})
                     nil
                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (is (= ::web/undeclared-route (build [["/x" {:post ok}]])))
    (is (= ::web/undeclared-route (build [["/x" {:get ok :access :everyone}]])))
    (is (= ::web/bad-route (build [["/x" {:get ok :access {:action :notes/edit :record [:note :id]}}]]))
        "no rule for that action")
    (is (= ::web/bad-route (build [["/x" {:post ok :server-to-server true :access :public}]])))
    (is (nil? (build [["/x" {:post ok :server-to-server true}]])))))
