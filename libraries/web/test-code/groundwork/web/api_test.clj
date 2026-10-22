(ns groundwork.web.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork.steps.api :as steps]
   [groundwork.web.api :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket$Listener)
   (java.util.concurrent TimeUnit)))

(def ^:private say-hello-to-shop
  {:name  :say-hello-to-shop
   :enter (fn [context]
            (let [shop-id (get-in context [:groundwork.web/request :path-params :shop-id])]
              (steps/add-answer-to-context context {:status 200 :body (str "hello shop " shop-id)})))})

(def ^:private opened-sse-line (atom (promise)))

(def ^:private routes
  [["/shops/:shop-id/hello" {:get {:handler (web/make-ring-handler-from-steps [say-hello-to-shop])}}]
   ["/silent" {:get {:handler (web/make-ring-handler-from-steps [])}}]
   ["/bells" {:sse {:on-open #(deliver @opened-sse-line %)}}]
   ["/echo" {:ws {:on-message (fn [line data] (web/send-message-on-ws-line! line {:echo data}))}}]])

(defn- get-text [port path]
  (let [response (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port path))))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(defn- with-server [f]
  (let [server (web/start-server! (web/make-ring-handler-from-routes routes) {:ip "127.0.0.1" :port 0})]
    (try (f (:port server)) (finally (web/stop-server! server)))))

(deftest http-runs-the-routes-steps
  (with-server
    (fn [port]
      (testing "the steps' answer is the response"
        (is (= [200 "hello shop 7"] (get-text port "/shops/7/hello"))))
      (testing "no step answered: 500"
        (is (= 500 (first (get-text port "/silent")))))
      (testing "no route: 404"
        (is (= 404 (first (get-text port "/nowhere"))))))))

(deftest sse-keeps-the-answer-open-and-writes-messages
  (reset! opened-sse-line (promise))
  (with-server
    (fn [port]
      (let [response (.send (HttpClient/newHttpClient)
                            (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/bells"))))
                            (HttpResponse$BodyHandlers/ofInputStream))
            reader   (io/reader (.body response))
            line     (deref @opened-sse-line 5000 nil)]
        (is (= "text/event-stream" (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (some? line) "the route's on-open got the line")
        (is (true? (web/send-message-on-sse-line! line {:id 17 :data {:table "orders" :ids [42]}})))
        (is (= ["id: 17" "data: {\"table\":\"orders\",\"ids\":[42]}"]
               [(.readLine reader) (.readLine reader)]))
        (.close reader)))))

(deftest ws-talks-both-ways
  (with-server
    (fn [port]
      (let [received (promise)
            socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/echo"))
                                         (reify WebSocket$Listener
                                           (onText [_ socket text _last]
                                             (deliver received (str text))
                                             (.request socket 1)
                                             nil))))]
        (.join (.sendText socket "{\"hello\":\"server\"}" true))
        (is (= "{\"echo\":{\"hello\":\"server\"}}" (deref received 5000 nil)))
        (.join (.sendClose socket 1000 "done"))))
    )
  (testing "a plain GET on a ws route: 400"
    (with-server #(is (= 400 (first (get-text % "/echo")))))))
