(ns groundwork.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.core :as gw]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [app-settings]]))

(pg/defsql timeout-setting)

(def settings app-settings)

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest settings-are-checked
  (testing "a missing setting throws, naming every one that is missing"
    (is (= {:type ::gw/missing-settings :missing [:environment :db :migrations :sessions :permissions :parquery :page :web :server]}
           (error #(gw/start {})))))
  (testing "an unknown setting throws, so a typo can't go unnoticed"
    (is (= {:type ::gw/unknown-settings :unknown [:dbb]}
           (error #(gw/start (assoc settings :dbb {}))))))
  (testing "the environment is one of three"
    (is (= ::gw/bad-setting (:type (error #(gw/start (assoc settings :environment :staging))))))))

(deftest start-and-stop
  (let [app (gw/start settings)]
    (try
      (testing "the db part is a working db"
        (is (= {:statement-timeout/value "5s"} (pg/row (gw/db app) timeout-setting))))
      (finally (gw/stop app)))))

(defn- get-status [app path]
  (let [client  (java.net.http.HttpClient/newHttpClient)
        request (.build (java.net.http.HttpRequest/newBuilder
                         (java.net.URI. (str "http://127.0.0.1:" (gw/port app) path))))]
    (.statusCode (.send client request (java.net.http.HttpResponse$BodyHandlers/discarding)))))

(deftest routes-swap-without-a-restart
  (let [app (gw/start settings)]
    (try
      (is (= 404 (get-status app "/new")))
      (gw/swap-routes! app [["/new" {:access :public :get (fn [_] {:status 200 :body "new"})}]])
      (is (= 200 (get-status app "/new")) "the same server, the new routes")
      (testing "wrong routes throw and the old ones stay"
        (is (= :groundwork.web/undeclared-route
               (:type (error #(gw/swap-routes! app [["/newer" {:get (fn [_] {:status 200})}]])))))
        (is (= 200 (get-status app "/new"))))
      (testing "a bigger body than the server reads needs a restart"
        (is (= :groundwork.web/restart-needed
               (:type (error #(gw/swap-routes! app [["/up" {:access :public :post (fn [_] {:status 200})
                                                             :max-body-bytes 999999999}]]))))))
      (finally (gw/stop app)))))

(deftest a-part-can-be-replaced-by-a-fake
  (let [app (gw/start-with settings {:groundwork/server ::fake-server})]
    (is (= ::fake-server (gw/part app :groundwork/server)))
    (testing "a fake isn't stopped"
      (gw/stop app))
    (testing "only parts can be replaced"
      (is (= ::gw/unknown-part (:type (error #(gw/start-with settings {:groundwork/nope 1}))))))))
