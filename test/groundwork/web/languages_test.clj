(ns groundwork.web.languages-test
  "The language in the address, the cookie, or the browser's wish."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def routes
  [["/pricing" {:access :public
                :get    (fn [request] {:status 200 :body (pr-str [(web/language request) (:uri request)])})}]
   ["/about"   {:access :public :get web/page}]])

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (-> app-settings
                            (assoc-in [:web :routes] routes)
                            (assoc-in [:web :languages] {:supported ["en" "de"] :default "en" :cookie "lang"})))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))
(def ^:private origin (get-in app-settings [:sessions :origin]))

(defn- request [path & {:as headers}]
  (let [builder (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))]
    (doseq [[k v] headers] (.header builder k v))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})))

(defn- pricing [path & headers]
  (read-string (:body (apply request path headers))))

(deftest the-language-comes-from-the-address-first
  (is (= ["de" "/pricing"] (pricing "/de/pricing")) "the prefix is taken off before routing")
  (is (= ["en" "/pricing"] (pricing "/en/pricing")))
  (is (= ["de" "/pricing"] (pricing "/de/pricing" "Cookie" "lang=en")) "the address beats the cookie")
  (is (= 404 (:status (request "/fr/pricing"))) "only supported languages are prefixes"))

(deftest then-the-cookie-then-the-browser-then-the-default
  (is (= ["en" "/pricing"] (pricing "/pricing" "Cookie" "lang=en" "Accept-Language" "de")))
  (is (= ["de" "/pricing"] (pricing "/pricing" "Accept-Language" "fr;q=0.9, de-AT;q=0.8, en;q=0.5")))
  (is (= ["en" "/pricing"] (pricing "/pricing" "Accept-Language" "fr, es")))
  (is (= ["en" "/pricing"] (pricing "/pricing"))))

(deftest the-page-follows-the-language
  (let [body (:body (request "/de/about" "Accept" "text/html"))]
    (is (str/includes? body "<html lang=\"de\">"))
    (is (str/includes? body (str "<link rel=\"alternate\" hreflang=\"en\" href=\"" origin "/about\">")))
    (is (str/includes? body (str "<link rel=\"alternate\" hreflang=\"de\" href=\"" origin "/de/about\">")))
    (is (str/includes? body (str "<link rel=\"alternate\" hreflang=\"x-default\" href=\"" origin "/about\">"))))
  (testing "the bare prefix is the home page in that language"
    (let [body (:body (request "/de" "Accept" "text/html"))]
      (is (str/includes? body "<html lang=\"de\">"))
      (is (str/includes? body (str "hreflang=\"de\" href=\"" origin "/de\"")))
      (is (str/includes? body (str "hreflang=\"en\" href=\"" origin "/\""))))))
