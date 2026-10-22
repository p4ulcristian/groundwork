(ns groundwork.transport.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork.steps.api :as steps]
   [groundwork.transport.api :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket$Listener)))

(def ^:private say-hello
  {:name  :say-hello
   :enter #(steps/add-answer-to-context % {:status 200 :body "hello"})})

(defn- with-server [ring-handler f]
  (let [server (transport/start-server! ring-handler {:ip "127.0.0.1" :port 0})]
    (try (f (:port server)) (finally (transport/stop-server! server)))))

(defn- get-text [port path]
  (let [response (.send (HttpClient/newHttpClient)
                        (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port path))))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest http-answers-with-the-steps-answer
  (testing "the steps' answer is the response"
    (is (= {:status 200 :body "hello"} ((transport/make-ring-handler-from-steps [say-hello]) {}))))
  (testing "no step answered: 500"
    (is (= 500 (:status ((transport/make-ring-handler-from-steps []) {})))))
  (testing "over a real server"
    (with-server (transport/make-ring-handler-from-steps [say-hello])
      #(is (= [200 "hello"] (get-text % "/anything"))))))

(deftest sse-keeps-the-answer-open-and-writes-messages
  (let [opened (promise)]
    (with-server (transport/make-ring-handler-for-sse {:on-open #(deliver opened %)})
      (fn [port]
        (let [response (.send (HttpClient/newHttpClient)
                              (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/"))))
                              (HttpResponse$BodyHandlers/ofInputStream))
              reader   (io/reader (.body response))
              line     (deref opened 5000 nil)]
          (is (= "text/event-stream" (.orElse (.firstValue (.headers response) "content-type") nil)))
          (is (transport/is-line-open? line))
          (is (true? (transport/send-message-on-sse-line! line {:id 17 :data {:table "orders" :ids [42]}})))
          (is (= ["id: 17" "data: {\"table\":\"orders\",\"ids\":[42]}"]
                 [(.readLine reader) (.readLine reader)]))
          (.close reader))))))

(deftest ws-talks-both-ways
  (let [echo (transport/make-ring-handler-for-ws
              {:on-message (fn [line data] (transport/send-message-on-ws-line! line {:echo data}))})]
    (with-server echo
      (fn [port]
        (let [received (promise)
              socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                           (URI. (str "ws://localhost:" port "/"))
                                           (reify WebSocket$Listener
                                             (onText [_ socket text _last]
                                               (deliver received (str text))
                                               (.request socket 1)
                                               nil))))]
          (.join (.sendText socket "{\"hello\":\"server\"}" true))
          (is (= "{\"echo\":{\"hello\":\"server\"}}" (deref received 5000 nil)))
          (.join (.sendClose socket 1000 "done")))))
    (testing "a plain GET: 400"
      (with-server echo #(is (= 400 (first (get-text % "/"))))))))
