(ns groundwork.routes.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.routes.api :as routes]
   [groundwork.steps.api :as steps]
   [groundwork.transport.api :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private handlers
  {:greetings/hello {:steps [{:name :say-hello :enter #(steps/add-answer-to-context % "hello")}]}})

(def ^:private pages
  {:orders/page {:address "/orders/:order-id" :title "Order"}})

(defn- page-handler [_request] {:status 200 :body "<html>the app</html>"})

(defn- send-request [port method path body]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port path)))
                            (.method method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest an-address-leads-to-its-pipe-or-its-page
  (let [server (transport/start-server!
                (routes/make-ring-handler-from-routes
                 (into (transport/make-routes-for-messages handlers {})
                       (routes/make-server-routes-for-pages pages page-handler)))
                {:ip "127.0.0.1" :port 0})
        port   (:port server)]
    (try
      (testing "a named message on transport's http address"
        (is (= [200 "{\"greetings/hello\":\"hello\"}"] (send-request port "POST" "/groundwork/http" "{\"greetings/hello\":{}}"))))
      (testing "a page opened fresh: the page handler"
        (is (= [200 "<html>the app</html>"] (send-request port "GET" "/orders/42" nil))))
      (testing "no route, no page: 404"
        (is (= 404 (first (send-request port "GET" "/nowhere" nil)))))
      (finally (transport/stop-server! server)))))

(deftest a-page-needs-a-namespaced-name-and-an-address
  (is (thrown? clojure.lang.ExceptionInfo (routes/make-server-routes-for-pages {:page {:address "/x"}} page-handler)))
  (is (thrown? clojure.lang.ExceptionInfo (routes/make-server-routes-for-pages {:orders/page {}} page-handler))))
