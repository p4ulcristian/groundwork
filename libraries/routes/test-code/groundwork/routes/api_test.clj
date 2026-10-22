(ns groundwork.routes.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork.routes.api :as routes]
   [groundwork.steps.api :as steps]
   [groundwork.transport.api :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private say-hello-to-shop
  {:name  :say-hello-to-shop
   :enter (fn [context]
            (let [shop-id (get-in context [:groundwork.transport/request :path-params :shop-id])]
              (steps/add-answer-to-context context {:status 200 :body (str "hello shop " shop-id)})))})

(def ^:private pages
  [["/orders/:order-id" {:name :order :title "Order"}]])

(defn- page-handler [_request] {:status 200 :body "<html>the app</html>"})

(def ^:private bells-opened (atom (promise)))

(def ^:private all-routes
  (into [["/shops/:shop-id/hello" {:get {:handler (transport/make-ring-handler-from-steps [say-hello-to-shop])}}]
         ["/bells" {:sse {:on-open #(deliver @bells-opened %)}}]]
        (routes/make-server-routes-for-pages pages page-handler)))

(defn- get-text [port path]
  (let [response (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port path))))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest an-address-leads-to-its-work-or-its-page
  (reset! bells-opened (promise))
  (let [server (transport/start-server! (routes/make-ring-handler-from-routes all-routes) {:ip "127.0.0.1" :port 0})
        port   (:port server)]
    (try
      (testing "http: a route's steps, with its path params"
        (is (= [200 "hello shop 7"] (get-text port "/shops/7/hello"))))
      (testing "a page opened fresh: the page handler"
        (is (= [200 "<html>the app</html>"] (get-text port "/orders/42"))))
      (testing "no route, no page: 404"
        (is (= 404 (first (get-text port "/nowhere")))))
      (testing "sse: the route opens a line"
        (let [response (.send (HttpClient/newHttpClient)
                              (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/bells"))))
                              (HttpResponse$BodyHandlers/ofInputStream))]
          (is (some? (deref @bells-opened 5000 nil)))
          (.close (io/reader (.body response)))))
      (finally (transport/stop-server! server)))))

(deftest a-page-needs-a-name
  (is (thrown? clojure.lang.ExceptionInfo (routes/make-server-routes-for-pages [["/x" {}]] page-handler))))
