(ns groundwork.transport.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork.steps.api :as steps]
   [groundwork.transport.api :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket$Listener)))

(def ^:private say-hello
  {:name  :say-hello
   :enter #(steps/add-answer-to-context % {:hello (get-in % [:groundwork.transport/message-data :name])})})

(def ^:private handlers
  {:greetings/hello {:steps [say-hello]}
   :greetings/fail  {:steps [{:name :fail :enter (fn [_] (throw (ex-info "it broke" {})))}]}})

(defn- find-handler
  "The Ring handler of one of transport's three routes: 0 http, 1 sse, 2 ws."
  [routes index method]
  (get-in (nth routes index) [1 method :handler]))

(defn- with-server [ring-handler f]
  (let [server (transport/start-server! ring-handler {:ip "127.0.0.1" :port 0})]
    (try (f (:port server)) (finally (transport/stop-server! server)))))

(defn- post-json [port body]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/groundwork/http")))
                            (.POST (HttpRequest$BodyPublishers/ofString body))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest the-three-routes-are-groundworks
  (is (= ["/groundwork/http" "/groundwork/sse" "/groundwork/ws"]
         (map first (transport/make-routes-for-messages handlers {})))))

(deftest http-answers-each-name-apart
  (with-server (find-handler (transport/make-routes-for-messages handlers {}) 0 :post)
    (fn [port]
      (testing "each name answered under its name; one failing doesn't stop the other"
        (is (= [200 "{\"greetings/hello\":{\"hello\":\"Anna\"},\"greetings/fail\":{\"error\":\"it broke\"}}"]
               (post-json port "{\"greetings/hello\":{\"name\":\"Anna\"},\"greetings/fail\":{}}"))))
      (testing "an unknown name: an error under it"
        (is (= [200 "{\"nobody/home\":{\"error\":\"No handler named :nobody/home\"}}"]
               (post-json port "{\"nobody/home\":{}}"))))
      (testing "not a JSON object: 400"
        (is (= 400 (first (post-json port "[1,2]"))))))))

(deftest sse-writes-named-messages
  (let [opened (promise)
        routes (transport/make-routes-for-messages handlers {:on-sse-open #(deliver opened %)})]
    (with-server (find-handler routes 1 :get)
      (fn [port]
        (let [response (.send (HttpClient/newHttpClient)
                              (.build (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/groundwork/sse"))))
                              (HttpResponse$BodyHandlers/ofInputStream))
              reader   (io/reader (.body response))
              line     (deref opened 5000 nil)]
          (is (transport/is-line-open? line))
          (is (true? (transport/send-message-on-sse-line! line {:bells/changed {:table "orders"}} 17)))
          (is (= ["id: 17" "data: {\"bells/changed\":{\"table\":\"orders\"}}"]
                 [(.readLine reader) (.readLine reader)]))
          (.close reader))))))

(deftest ws-answers-by-the-same-handlers
  (with-server (find-handler (transport/make-routes-for-messages handlers {}) 2 :get)
    (fn [port]
      (let [received (promise)
            socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/groundwork/ws"))
                                         (reify WebSocket$Listener
                                           (onText [_ socket text _last]
                                             (deliver received (str text))
                                             (.request socket 1)
                                             nil))))]
        (.join (.sendText socket "{\"greetings/hello\":{\"name\":\"Bob\"}}" true))
        (is (= "{\"greetings/hello\":{\"hello\":\"Bob\"}}" (deref received 5000 nil)))
        (.join (.sendClose socket 1000 "done"))))))
