(ns groundwork.endpoints.tables-ring-test
  "Every table of the app says how it rings, or the start refuses, naming
   the ones that don't."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.endpoints.bells :as bells]
   [groundwork.test-support :refer [app-settings sql!]]))

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS tr_colours, tr_notices, tr_import_log;
           CREATE TABLE tr_colours (tr_colour__id uuid PRIMARY KEY);
           CREATE TABLE tr_notices (tr_notice__id uuid PRIMARY KEY, tr_notice__person_id uuid NOT NULL);
           CREATE TABLE tr_import_log (tr_import_log__line text)")
    (try (t)
         (finally (sql! "DROP TABLE IF EXISTS tr_colours, tr_notices, tr_import_log")))))

(defn- others
  "Every other table the shared test database holds right now, as silent."
  []
  (into {}
        (comp (map :pg_class/relname)
              (remove #{"tr_colours" "tr_notices" "tr_import_log"})
              (map (fn [t] [(keyword t) "another test's table"])))
        (sql! "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')")))

(defn- start-with [bells]
  (try (gw/stop (gw/start (assoc-in app-settings [:endpoints :bells] bells)))
       :started
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-table-says-how-it-rings
  (testing "tables nothing names stop the start, all of them in one error"
    (let [problem (start-with {:global {:tr_colours "reference data"} :personal {} :silent (others)})]
      (is (= ::bells/tables-not-declared (:type problem)))
      (is (= ["tr_import_log" "tr_notices"] (:undeclared problem)))))
  (testing "global, personal, or silent with its reason: the app starts"
    (is (= :started (start-with {:global   {:tr_colours "reference data"}
                                 :personal {:tr_notices :tr-notice/person-id}
                                 :silent   (assoc (others) :tr_import_log "written by the import, shown nowhere")}))))
  (testing "a table that rings can't be silent too"
    (is (= ["tr_colours"]
           (:twice (start-with {:global   {:tr_colours "reference data"}
                                :personal {:tr_notices :tr-notice/person-id}
                                :silent   (assoc (others) :tr_import_log "…" :tr_colours "…")})))))
  (testing "a table named that isn't there"
    (is (= ["tr_gone"]
           (:missing (start-with {:global   {:tr_colours "reference data"}
                                  :personal {:tr_notices :tr-notice/person-id}
                                  :silent   (assoc (others) :tr_import_log "…" :tr_gone "…")})))))
  (testing "a database shared with something else: the rest is silent, said once"
    (is (= :started (start-with {:global {} :personal {}
                                 :silent {bells/every-other-table "shared with other tests"}})))))

(deftest a-global-table-says-why
  (testing "a set of tables, with no reasons, is refused by the settings check"
    (let [problem (start-with {:global #{:tr_colours} :personal {} :silent (others)})]
      (is (= :groundwork.settings/invalid (:type problem)) (pr-str problem))))
  (testing "an empty reason too"
    (let [problem (start-with {:global {:tr_colours ""} :personal {} :silent (others)})]
      (is (= :groundwork.settings/invalid (:type problem)) (pr-str problem)))))

(defn- ring-triggers [table]
  (count (sql! (str "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                    " WHERE c.relname = '" table "' AND t.tgname LIKE 'groundwork_ring_%'"))))

(deftest a-table-that-turns-silent-stops-ringing
  (testing "it rings while it is global"
    (is (= :started (start-with {:global   {:tr_colours "reference data"}
                                 :personal {:tr_notices :tr-notice/person-id}
                                 :silent   (assoc (others) :tr_import_log "…")})))
    (is (= 3 (ring-triggers "tr_colours"))))
  (testing "declared silent at a later start, in the same database, its triggers are gone"
    (is (= :started (start-with {:global   {}
                                 :personal {:tr_notices :tr-notice/person-id}
                                 :silent   (assoc (others) :tr_import_log "…" :tr_colours "nothing shows it any more")})))
    (is (zero? (ring-triggers "tr_colours")))
    (is (= 3 (ring-triggers "tr_notices")) "the others keep theirs")))
