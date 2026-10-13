(ns groundwork.snapshot-test
  "The schema snapshot is written when the start is done: what Groundwork
   installs in the database is in it, on a database's first start too."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.migrations :as migrations]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [app-settings db-settings sql!]]))

(def ^:private fresh-table
  "DROP TABLE IF EXISTS snap_things;
   CREATE TABLE snap_things (snap_thing__id uuid PRIMARY KEY, snap_thing__shop_id uuid NOT NULL)")

(use-fixtures :each
  (fn [t]
    (sql! fresh-table)
    (try (t)
         (finally (sql! "DROP TABLE IF EXISTS snap_things")))))

(defn- settings [file]
  (-> app-settings
      (assoc :environment :development)
      (assoc :migrations {:schema-snapshot (str file)})
      (assoc :permissions {:scopes  {:shop {:actions #{:things/see} :roles {} :parent nil}}
                           :records {:snap-thing {:table :snap_things :id :snap-thing/id
                                                  :in [:shop :snap-thing/shop-id]}}
                           :links [] :passes [] :open {:anyone #{} :signed-in #{}} :session-blocks {}})))

(defn- about-the-table
  "The snapshot's lines about the test's own table: other tests' tables come and go."
  [text]
  (filterv #(str/includes? % "snap_things") (str/split-lines text)))

(deftest the-snapshot-holds-what-the-start-installed
  (let [file (java.io.File/createTempFile "schema" ".sql")]
    (try
      (testing "a table the wall and the bells have never been on: its first start"
        (gw/stop (gw/start (settings file)))
        (let [text (slurp file)]
          (is (str/includes? text "CREATE TABLE public.snap_things"))
          (is (str/includes? text "CREATE POLICY groundwork_wall ON public.snap_things") "the wall")
          (is (str/includes? text "CREATE TRIGGER groundwork_ring_insert AFTER INSERT ON public.snap_things")
              "the bells")
          (is (str/includes? text "FORCE ROW LEVEL SECURITY"))))
      (testing "a later start writes the same about it"
        (let [first-start (about-the-table (slurp file))]
          (gw/stop (gw/start (settings file)))
          (is (= first-start (about-the-table (slurp file))))))
      (finally (.delete file)))))

(deftest the-migrations-part-alone-writes-none
  (let [file (java.io.File/createTempFile "schema" ".sql")
        db   (pg/start db-settings)]
    (try
      (.delete file)
      (migrations/start {:settings {:schema-snapshot (str file)} :environment :development :db db
                         :db-settings db-settings})
      (is (not (.exists file)) "the start writes it when every part has started, not the migrations part")
      (finally (pg/stop db) (.delete file)))))

(deftest only-development-writes-one
  (let [file (java.io.File/createTempFile "schema" ".sql")]
    (try
      (.delete file)
      (gw/stop (gw/start (assoc (settings file) :environment :test)))
      (is (not (.exists file)))
      (finally (.delete file)))))
