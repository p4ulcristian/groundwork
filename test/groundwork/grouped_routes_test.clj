(ns groundwork.grouped-routes-test
  "Routes in a group, and catch-all routes: what children inherit."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:dynamic *app* nil)

(def routes
  [["/customize" {:get web/page :access :public :embeddable true
                  :page {:title "Design your part"}}
    [""]
    ["/step/:n"]
    ["/review" {:page {:title "Review your part"}}]
    ["/saved" {:access :signed-in}]]
   ["/embed/*path" {:get web/page :access :public :embeddable true
                    :page {:title "Embedded"}}]])

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (assoc-in app-settings [:web :routes] routes))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- get-page [path]
  (let [request  (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                     (.header "Accept" "text/html")
                     (.GET)
                     (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status  (.statusCode response)
     :body    (.body response)
     :headers (into {} (map (fn [[k vs]] [k (first vs)])) (.map (.headers response)))}))

(defn- title [{:keys [body]}]
  (second (re-find #"<title>([^<]*)</title>" body)))

(defn- framable? [{:keys [headers]}]
  (str/includes? (get headers "content-security-policy" "") "frame-ancestors https:"))

(deftest children-inherit-the-group
  (doseq [path ["/customize" "/customize/step/2"]]
    (testing path
      (let [r (get-page path)]
        (is (= 200 (:status r)))
        (is (= "Design your part" (title r)))
        (is (framable? r) "embeddable is inherited")))))

(deftest a-child-overrides-one-thing
  (let [r (get-page "/customize/review")]
    (is (= 200 (:status r)))
    (is (= "Review your part" (title r)))
    (is (framable? r) "and keeps the rest")))

(deftest a-child-can-be-stricter
  (let [r (get-page "/customize/saved")]
    (is (= 302 (:status r)) "signed out, sent to the login")
    (is (str/starts-with? (get-in r [:headers "location"]) "/login"))))

(deftest a-catch-all-covers-everything-below
  (doseq [path ["/embed/a" "/embed/a/b/c"]]
    (testing path
      (let [r (get-page path)]
        (is (= 200 (:status r)))
        (is (= "Embedded" (title r)))
        (is (framable? r))))))

(deftest a-group-without-access-refuses-the-start
  (is (thrown? Exception
               (gw/start (assoc-in app-settings [:web :routes]
                                   [["/nothing" {:get web/page}
                                     ["/here"]]])))))
