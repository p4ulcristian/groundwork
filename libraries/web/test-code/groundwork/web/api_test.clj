(ns groundwork.web.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.steps.api :as steps]
   [groundwork.web.api :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private say-hello-to-shop
  {:name  :say-hello-to-shop
   :enter (fn [context]
            (let [shop-id (get-in context [:groundwork.web/request :path-params :shop-id])]
              (steps/add-answer-to-context context {:status 200 :body (str "hello shop " shop-id)})))})

(def ^:private routes
  [["/shops/:shop-id/hello" {:get {:handler (web/make-ring-handler-from-steps [say-hello-to-shop])}}]
   ["/silent" {:get {:handler (web/make-ring-handler-from-steps [])}}]])

(defn- get-text [port path]
  (let [response (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port path))))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest a-request-runs-its-route's-steps
  (let [server (web/start-server! (web/make-ring-handler-from-routes routes) {:ip "127.0.0.1" :port 0})]
    (try
      (testing "the steps' answer is the response"
        (is (= [200 "hello shop 7"] (get-text (:port server) "/shops/7/hello"))))
      (testing "no step answered: 500"
        (is (= 500 (first (get-text (:port server) "/silent")))))
      (testing "no route: 404"
        (is (= 404 (first (get-text (:port server) "/nowhere")))))
      (finally (web/stop-server! server)))))
