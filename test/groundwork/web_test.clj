(ns groundwork.web-test
  "A real server on a free port, asked over HTTP."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.page :as page]
   [groundwork.test-support :refer [app-settings]])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:dynamic *app* nil)

(def routes
  [["/api/hello" {:get (fn [_] {:status 200 :body "hi"})}]
   ["/api/thread" {:get (fn [_] {:status 200 :body (str (.isVirtual (Thread/currentThread)))})}]])

(use-fixtures :once
  (fn [t]
    (let [app (gw/start (-> app-settings
                            (assoc :routes routes)
                            (assoc-in [:page :title] "Acme <Coating> & Co")))]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- request
  ([path] (request path {}))
  ([path {:keys [method headers] :or {method "GET"}}]
   (let [builder (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port *app*) path)))
                     (.method method (HttpRequest$BodyPublishers/noBody)))]
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
      (is (str/includes? (headers "content-security-policy") "frame-ancestors 'none'") path)))
  (testing "Strict-Transport-Security only in production"
    (is (nil? ((:headers (request "/api/hello")) "strict-transport-security")))))

(deftest a-browser-gets-the-page-for-any-other-path
  (doseq [path ["/" "/shops/7/orders"]]
    (let [{:keys [status body headers]} (request path {:headers html})]
      (is (= 200 status) path)
      (is (str/includes? body "<div id=\"app\"></div>"))
      (is (str/includes? body "<script src=\"/js/main.js\" defer></script>"))
      (is (str/includes? body "<link rel=\"stylesheet\" href=\"/app.css\">"))
      (is (= "no-store" (headers "cache-control")))))
  (testing "the title is escaped"
    (is (str/includes? (:body (request "/" {:headers html}))
                       "<title>Acme &lt;Coating&gt; &amp; Co</title>"))))

(deftest files-are-served-and-revalidated
  (let [{:keys [status body headers]} (request "/app.css")]
    (is (= 200 status))
    (is (= "body { margin: 0; }\n" body))
    (is (= "no-cache" (headers "cache-control")))
    (testing "an unchanged file answers 304"
      (is (= 304 (:status (request "/app.css" {:headers {"If-Modified-Since" (headers "last-modified")}})))))))

(deftest anything-else-is-not-found
  (is (= 404 (:status (request "/missing.png" {:headers {"Accept" "image/*"}}))))
  (is (= 404 (:status (request "/" {:method "POST" :headers html}))))
  (is (= 404 (:status (request "/api/nowhere")))))

(deftest page-settings-are-checked
  (is (= ::page/missing-settings
         (:type (ex-data (try (page/start {:settings {:title "x"}}) (catch Exception e e))))))
  (is (= ::page/bad-setting
         (:type (ex-data (try (page/start {:settings (assoc (:page app-settings) :stylesheets "x")})
                              (catch Exception e e)))))))
