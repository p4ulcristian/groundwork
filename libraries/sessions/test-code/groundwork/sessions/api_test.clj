(ns groundwork.sessions.api-test
  "The Postgres tests run when GROUNDWORK_SQL_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.schema.api :as schema]
   [groundwork.sessions.api :as sessions]
   [groundwork.sessions.server-code.cookie :as cookie]
   [groundwork.sql.api :as sql]
   [groundwork.steps.api :as steps]))

(def ^:private port (some-> (System/getenv "GROUNDWORK_SQL_TEST_PORT") parse-long))

(def ^:private settings {:session-days 30 :allowed-origin "https://app.test" :secure-cookie? true})

(def ^:private anna #uuid "a0000000-0000-4000-8000-000000000001")

(defn- make-request [set-cookie origin]
  {:request-method :post
   :headers        (cond-> {"origin" origin}
                     set-cookie (assoc "cookie" (first (clojure.string/split set-cookie #";"))))})

(defn- run-find-session [step request]
  (steps/run-steps-until-answered [step] {:groundwork.transport/request request}))

(deftest the-cookie
  (testing "only the fingerprint is kept, and it is the same for the same cookie"
    (is (= 64 (count (cookie/turn-cookie-value-into-fingerprint "abc"))))
    (is (= (cookie/turn-cookie-value-into-fingerprint "abc") (cookie/turn-cookie-value-into-fingerprint "abc"))))
  (testing "the cookie is HttpOnly, SameSite=Lax and Secure"
    (is (= "groundwork_session=xyz; Path=/; HttpOnly; SameSite=Lax; Max-Age=60; Secure"
           (cookie/make-set-cookie-text "xyz" 60 true)))))

(deftest sessions-against-postgres
  (if-not port
    (println "groundwork.sessions.api-test: skipped, GROUNDWORK_SQL_TEST_PORT is not set")
    (let [pool (sql/start-pool! {:host "localhost" :port port :database "postgres"
                                 :user (System/getenv "GROUNDWORK_SQL_TEST_USER") :password nil
                                 :tls :off :pool-size 2 :statement-timeout-ms 5000 :time-zone "UTC"})
          step (sessions/make-find-session-step pool settings)]
      (try
        (sql/run-statement! pool (sql/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS groundwork CASCADE") {})
        (with-out-str @(:slow-part (schema/migrate! pool [sessions/sessions-table] [])))
        (let [{:keys [set-cookie]} (sessions/sign-in! pool anna settings)]
          (testing "a signed-in request: the person is in the context"
            (is (= anna (:groundwork.sessions/person-id (run-find-session step (make-request set-cookie "https://app.test"))))))
          (testing "another site's request is answered with an error, before any work"
            (is (contains? (:groundwork.steps/answer (run-find-session step (make-request set-cookie "https://evil.test"))) :error)))
          (testing "no cookie: nobody"
            (is (nil? (:groundwork.sessions/person-id (run-find-session step (make-request nil "https://app.test"))))))
          (testing "signed out: the same cookie finds nobody"
            (sessions/sign-out! pool (make-request set-cookie "https://app.test") settings)
            (is (nil? (:groundwork.sessions/person-id (run-find-session step (make-request set-cookie "https://app.test")))))))
        (testing "signing out everywhere ends every session of the person"
          (sessions/sign-in! pool anna settings)
          (sessions/sign-in! pool anna settings)
          (is (= 2 (sessions/sign-out-everywhere! pool anna))))
        (finally (sql/stop-pool! pool))))))
