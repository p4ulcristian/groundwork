(ns groundwork.migrate-test
  "Production migrates before it starts: migrate, as the role that owns the
   tables, runs the migrations, installs what Groundwork puts into the
   database and grants the app's role; production's start, as the app's
   role, changes nothing and refuses a database migrate hasn't brought up
   to its settings. Needs the second role (see groundwork.test-support)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.migrations :as migrations]
   [groundwork.migrations.installs :as installs]
   [groundwork.migrations.roles :as roles]
   [groundwork.permissions.install :as install]
   [groundwork.postgres :as pg]
   [groundwork.test-support :as support :refer [app-settings db-settings owner sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private tables
  "DROP TABLE IF EXISTS mig_things, mig_later;
   CREATE TABLE mig_things (mig_thing__id uuid PRIMARY KEY, mig_thing__shop_id uuid NOT NULL)")

(use-fixtures :each
  (fn [t]
    (sql! tables)
    (try (t)
         (finally (sql! "DROP TABLE IF EXISTS mig_things, mig_later")))))

(def ^:private settings
  (assoc app-settings
         :permissions {:scopes  {:shop {:actions {:things/see #{:read} :things/edit #{:read :update}}
                                        :roles {} :parent nil}}
                       :records {:mig-thing {:table :mig_things :id :mig-thing/id
                                             :whose {:mig-thing/shop-id :shop}}}
                       :rules   [{:allow :things/see :on :mig-thing :to [:member :mig-thing/shop-id]}]}))

(def ^:private production (support/production settings))

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-owner-db [f]
  (let [db (pg/start db-settings)]
    (try (f db) (finally (pg/stop db)))))

(defn- status-of [app path]
  (.statusCode (.send (HttpClient/newHttpClient)
                      (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port app) path))))
                      (HttpResponse$BodyHandlers/discarding))))

(defn- as-app! [app sql]
  (jdbc/execute! (::pg/datasource (gw/db app)) [sql]))

(deftest production-starts-after-migrate
  (testing "migrate, as the owner: the migrations, the wall, the bells, the grants"
    (is (vector? (:ran (gw/migrate! production owner))))
    (with-owner-db
      (fn [db]
        (is (= [] (roles/missing db (:user support/app-role))) "the app's role holds every grant")
        (is (= [] (roles/owned db (:user support/app-role))) "and owns nothing")
        (is (empty? (:waiting (migrations/status db))) "every migration ran")))
    (is (= 1 (count (sql! "SELECT 1 FROM pg_policy WHERE polrelid = 'mig_things'::regclass AND polname = 'groundwork_read'")))
        "the wall is up"))
  (testing "a second migrate has no migration to run and installs the same"
    (is (= [] (:ran (gw/migrate! production owner)))))
  (testing "the start, as the app's role, serves"
    (let [app (gw/start production)]
      (try
        (is (= 200 (status-of app "/health")))
        (testing "the app's role can't change the tables or the wall"
          (is (thrown? Exception (as-app! app "ALTER TABLE mig_things DISABLE ROW LEVEL SECURITY")))
          (is (thrown? Exception (as-app! app "DROP TABLE mig_things")))
          (is (thrown? Exception (as-app! app "DROP POLICY groundwork_read ON mig_things")))
          (is (thrown? Exception (as-app! app "CREATE TABLE mig_sneaky (x int)"))))
        (testing "it reads Groundwork's record of migrations, and doesn't write it"
          (is (seq (as-app! app "SELECT migration__file FROM groundwork.migrations")))
          (is (thrown? Exception (as-app! app "DELETE FROM groundwork.migrations"))))
        (finally (gw/stop app))))))

(deftest production-refuses-what-migrate-has-not-brought-up
  (gw/migrate! production owner)
  (testing "settings that make another wall"
    (is (= ::installs/not-installed
           (:type (error #(gw/start (assoc-in production [:permissions :rules]
                                              [{:allow :things/edit :on :mig-thing :to [:member :mig-thing/shop-id]}])))))))
  (testing "a table migrate never granted"
    (sql! "CREATE TABLE mig_later (mig_later__id uuid PRIMARY KEY)")
    (let [{:keys [type missing]} (error #(gw/start production))]
      (is (= ::roles/app-role-lacks type))
      (is (some #{"SELECT on public.mig_later"} missing)))
    (testing "until migrate grants it"
      (gw/migrate! production owner)
      (gw/stop (gw/start production))))
  (testing "a migration that hasn't run"
    (with-owner-db
      (fn [db]
        (is (= ::migrations/not-current
               (:type (error #(migrations/check-current! db (conj migrations/sources
                                                                  ["test" "fixtures/migrations/first/"])))))))))
  (testing "the role that owns the tables can't serve"
    (let [{:keys [type owned]} (error #(gw/start (update production :db merge owner)))]
      (is (= ::roles/app-role-owns type))
      (is (some #{"table public.mig_things"} owned)))))

(deftest migrate-refuses
  (testing "the app's own role as the owner"
    (is (= ::roles/same-role (:type (error #(gw/migrate! production support/app-role))))))
  (testing "an app role that doesn't exist"
    (is (= ::roles/no-app-role
           (:type (error #(gw/migrate! (assoc-in production [:db :user] "groundwork_no_such_role") owner))))))
  (testing "an app role that owns the tables (here the tests' own role, checked from the second one)"
    (let [db (pg/start (merge db-settings support/app-role))]
      (try
        (is (= ::roles/app-role-owns (:type (error #(roles/check-before-migrate! db (:user owner))))))
        (finally (pg/stop db)))))
  (testing "no owner role"
    (is (= ::gw/bad-owner (:type (error #(gw/migrate! production {:password "x"})))))))

(deftest development-still-migrates-at-start
  (testing "one role: the start migrates, installs and records what it installed"
    (let [app (gw/start settings)]
      (try
        (is (nil? (installs/check! (gw/db app) :wall
                                   (install/statements (gw/db app) (:description (gw/permissions app))))))
        (finally (gw/stop app))))))
