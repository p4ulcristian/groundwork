(ns groundwork.eval-bridge-test
  "The eval bridge: code evaluated in the running app, given the secret;
   nothing without it; a time limit and a size cap; never in production."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.eval-bridge :as eval-bridge]
   [groundwork.settings :as gs]
   [groundwork.test-support :refer [app-settings]]
   [jsonista.core :as json])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def secret "a-secret-for-the-bridge")

(def settings (-> app-settings
                  (assoc-in [:server :port] 0)
                  (assoc-in [:web :eval-bridge] {:secret secret :timeout-ms 500 :max-chars 40})))

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (let [app (gw/start settings)]
      (try (binding [*app* app] (t))
           (finally (gw/stop app))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- evaluate [code & {:keys [given cljs] :or {given secret}}]
  (let [r (.send client (-> (HttpRequest/newBuilder
                             (URI. (str "http://127.0.0.1:" (gw/port *app*) eval-bridge/path (when cljs (str "?cljs=" cljs)))))
                            (.header "X-Eval-Secret" given)
                            (.POST (HttpRequest$BodyPublishers/ofString code))
                            .build)
                 (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r)
     :body   (if (str/starts-with? (.orElse (.firstValue (.headers r) "content-type") "") "application/json")
               (json/read-value (.body r) json/keyword-keys-object-mapper)
               (.body r))}))

(deftest code-runs-in-the-app
  (is (= {:status 200 :body {:value "3" :out ""}} (evaluate "(+ 1 2)")))
  (testing "what it prints comes back too"
    (is (= {:value "nil" :out "hello\n"} (:body (evaluate "(println \"hello\")")))))
  (testing "the running app is at hand"
    (is (= (str (gw/port *app*)) (:value (:body (evaluate "(groundwork.core/port (groundwork.core/current))")))))))

(deftest without-the-secret-nothing
  (is (= {:status 404 :body "Not found"} (evaluate "(+ 1 2)" :given "a-wrong-secret-xxxx")))
  (is (= 404 (:status (evaluate "(+ 1 2)" :given "")))))

(deftest errors-limits-and-caps
  (testing "an exception is an answer, not a crash"
    (is (= "ArithmeticException: Divide by zero" (:error (:body (evaluate "(/ 1 0)"))))))
  (testing "too long: stopped"
    (is (= 504 (:status (evaluate "(Thread/sleep 5000)")))))
  (testing "a big value is cut"
    (is (str/ends-with? (:value (:body (evaluate "(apply str (repeat 100 \\x))"))) "(cut at 40 characters)")))
  (testing "the browser side needs the browser build in this server"
    (is (str/includes? (:error (:body (evaluate "(+ 1 2)" :cljs "app"))) "browser build isn't running"))))

(deftest never-in-production
  (let [problems (fn [bridge environment]
                   (mapv :in (gs/part-problems :web (assoc (:web app-settings) :eval-bridge bridge :test-sign-in nil) environment)))]
    (is (= [[:web :eval-bridge]] (problems {:secret secret :timeout-ms 1 :max-chars 1} :production)))
    (is (= [[:web :eval-bridge :secret]] (problems {:secret "short" :timeout-ms 1 :max-chars 1} :test)))))
