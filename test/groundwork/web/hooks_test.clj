(ns groundwork.web.hooks-test
  "Request ids, the visitor's address behind a proxy, and the hooks."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.sessions :as sessions]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web]
   [groundwork.web.http :as http])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util UUID)))

(def peter (UUID/randomUUID))
(def requests (atom []))
(def errors (atom []))

(def routes
  [["/login"            {:access :public
                         :post   (fn [request] (sessions/sign-in request {:status 200} peter))}]
   ["/orders/:order-id" {:access :signed-in
                         :get    (fn [request]
                                   {:status 200 :body (pr-str [(web/request-id request) (web/client-ip request)])})}]
   ["/boom"             {:access :public :get (fn [_] (throw (ex-info "kaput" {})))}]])

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (-> app-settings
                            (assoc-in [:web :routes] routes)
                            (assoc-in [:web :on-request] #(swap! requests conj %))
                            (assoc-in [:web :on-error] #(swap! errors conj %))
                            (assoc-in [:web :proxies] {:trusted           ["127.0.0.0/8"]
                                                       :client-ip-header  "X-Real-IP"
                                                       :request-id-header "X-Request-Id"})))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- request [method path & {:keys [headers]}]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                    (.method method (HttpRequest$BodyPublishers/noBody)))]
    (doseq [[k v] headers] (.header builder k v))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status     (.statusCode response)
       :body       (.body response)
       :request-id (first (.allValues (.headers response) "x-request-id"))
       :cookie     (some-> (first (.allValues (.headers response) "set-cookie")) (str/split #";") first)})))

(deftest every-answer-has-a-request-id
  (let [{:keys [request-id]} (request "GET" "/nowhere")]
    (is (parse-uuid request-id) "made here when the proxy sent none"))
  (testing "a trusted proxy's id and visitor address are believed"
    (let [cookie (:cookie (request "POST" "/login" :headers {"Origin" (get-in app-settings [:sessions :origin])}))
          {:keys [body request-id]} (request "GET" "/orders/42" :headers {"Cookie"       cookie
                                                                           "X-Request-Id" "edge-123"
                                                                           "X-Real-IP"    "203.0.113.9, 10.0.0.1"})]
      (is (= "edge-123" request-id))
      (is (= ["edge-123" "203.0.113.9"] (read-string body))))))

(deftest the-request-hook-hears-every-answer
  (reset! requests [])
  (let [cookie (:cookie (request "POST" "/login" :headers {"Origin" (get-in app-settings [:sessions :origin])}))]
    (request "GET" "/orders/7" :headers {"Cookie" cookie})
    (let [{:keys [method route status ms person flags request-id client-ip]} (last @requests)]
      (is (= [:get "/orders/:order-id" 200 peter #{}] [method route status person flags]))
      (is (number? ms))
      (is (string? request-id))
      (is (= "127.0.0.1" client-ip) "no proxy header: the connection's address"))))

(deftest an-uncaught-error-is-a-plain-500-and-heard
  (reset! errors [])
  (let [{:keys [status body request-id]} (request "GET" "/boom")]
    (is (= 500 status))
    (is (= "Something went wrong" body) "nothing about the error reaches the browser")
    (let [{:keys [error route]} (last @errors)]
      (is (= "kaput" (ex-message error)))
      (is (= "/boom" route))
      (is (= request-id (:request-id (last @errors)))))
    (is (= 500 (:status (last @requests))))))

(deftest addresses-and-ranges
  (let [matches? (fn [entry addr] (boolean ((http/address-matcher entry) addr)))]
    (is (matches? "10.0.0.0/8" "10.200.3.4"))
    (is (not (matches? "10.0.0.0/8" "11.0.0.1")))
    (is (matches? "173.245.48.0/20" "173.245.63.255"))
    (is (not (matches? "173.245.48.0/20" "173.245.64.0")))
    (is (matches? "2400:cb00::/32" "2400:cb00:1::5"))
    (is (matches? "::1" "::1"))
    (is (not (matches? "10.0.0.1" "::1")))
    (is (not (matches? "10.0.0.0/8" "evil.example")) "names are never looked up")))
