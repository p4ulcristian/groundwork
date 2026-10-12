(ns groundwork.migrations-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.migrations :as migrations]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [db-settings sql!]]))

(def ^:dynamic *db* nil)

(defn- clean! []
  (sql! "DROP SCHEMA IF EXISTS groundwork CASCADE; DROP TABLE IF EXISTS widgets, gadgets"))

(use-fixtures :each
  (fn [t]
    (clean!)
    (let [db (pg/start db-settings)]
      (try (binding [*db* db] (t))
           (finally (pg/stop db) (clean!))))))

(def first-only [["test" "fixtures/migrations/first/"]])
(def both       [["test" "fixtures/migrations/first/"] ["later" "fixtures/migrations/second/"]])

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- columns [table]
  (set (map :columns/column_name
            (sql! "SELECT column_name FROM information_schema.columns WHERE table_name = ?" table))))

(deftest new-files-run-once-in-order
  (is (= [["test" "001_create_widgets.sql"] ["test" "002_add_widget_name.sql"]
          ["later" "001_add_widget_color.sql"]]
         (migrations/migrate! *db* both)))
  (is (= #{"widget__id" "widget__name" "widget__color"} (columns "widgets")))
  (testing "a second run has nothing to do"
    (is (= [] (migrations/migrate! *db* both))))
  (testing "each file is remembered with its fingerprint"
    (is (= 3 (count (sql! "SELECT * FROM groundwork.migrations"))))))

(deftest two-servers-starting-together-migrate-once
  (let [runs (doall (repeatedly 4 #(future (migrations/migrate! *db* first-only))))]
    (is (= 2 (count (mapcat deref runs))))))

(deftest a-failing-file-rolls-back-and-stops
  (let [e (error #(migrations/migrate! *db* [["test" "fixtures/migrations/failing/"]]))]
    (is (= {:type ::migrations/migration-failed :source "test" :file "001_half_done.sql"} e)))
  (is (empty? (columns "gadgets")) "the statement before the error rolled back too")
  (is (empty? (sql! "SELECT * FROM groundwork.migrations"))))

(deftest a-changed-file-stops-the-start
  (let [dir  (io/file "test/fixtures/migrations/changing")
        file (io/file dir "001_create_widgets.sql")]
    (try
      (.mkdirs dir)
      (spit file "CREATE TABLE widgets (widget__id uuid PRIMARY KEY);")
      (migrations/migrate! *db* [["test" "fixtures/migrations/changing/"]])
      (spit file "CREATE TABLE widgets (widget__id uuid PRIMARY KEY, widget__name text);")
      (is (= {:type ::migrations/changed-migration :source "test" :file "001_create_widgets.sql"}
             (error #(migrations/migrate! *db* [["test" "fixtures/migrations/changing/"]]))))
      (testing "a file that ran and is gone stops the start too"
        (io/delete-file file)
        (is (= ::migrations/missing-migration
               (:type (error #(migrations/migrate! *db* [["test" "fixtures/migrations/changing/"]]))))))
      (finally
        (io/delete-file file true)
        (io/delete-file dir true)))))

(deftest file-names-are-checked
  (is (= ::migrations/bad-file-name
         (:type (error #(migrations/files "fixtures/migrations/badname/")))))
  (testing "a folder that doesn't exist has no migrations"
    (is (= [] (migrations/files "fixtures/migrations/none/")))))

(deftest the-snapshot-holds-the-whole-schema
  (migrations/migrate! *db* first-only)
  (let [file (java.io.File/createTempFile "schema" ".sql")]
    (try
      (is (true? (migrations/write-snapshot! db-settings (str file))))
      (let [text (slurp file)]
        (is (str/includes? text "CREATE TABLE public.widgets"))
        (is (str/includes? text "CREATE TABLE groundwork.migrations")))
      (testing "the same schema writes the same file"
        (let [again (java.io.File/createTempFile "schema" ".sql")]
          (migrations/write-snapshot! db-settings (str again))
          (is (= (slurp file) (slurp again)))
          (.delete again)))
      (finally (.delete file)))))
