(ns groundwork.tables-ring-test
  "Every table of the app says how it rings, or the start refuses, naming
   the ones that don't."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.parquery.websocket :as websocket]
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
  (try (gw/stop (gw/start (assoc-in app-settings [:parquery :bells] bells)))
       :started
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest every-table-says-how-it-rings
  (testing "tables nothing names stop the start, all of them in one error"
    (let [problem (start-with {:global #{:tr_colours} :personal {} :silent (others)})]
      (is (= ::websocket/tables-not-declared (:type problem)))
      (is (= ["tr_import_log" "tr_notices"] (:undeclared problem)))))
  (testing "global, personal, or silent with its reason: the app starts"
    (is (= :started (start-with {:global   #{:tr_colours}
                                 :personal {:tr_notices :tr-notice/person-id}
                                 :silent   (assoc (others) :tr_import_log "written by the import, shown nowhere")}))))
  (testing "a table that rings can't be silent too"
    (is (= ["tr_colours"]
           (:twice (start-with {:global   #{:tr_colours}
                                :personal {:tr_notices :tr-notice/person-id}
                                :silent   (assoc (others) :tr_import_log "…" :tr_colours "…")})))))
  (testing "a table named that isn't there"
    (is (= ["tr_gone"]
           (:missing (start-with {:global   #{:tr_colours}
                                  :personal {:tr_notices :tr-notice/person-id}
                                  :silent   (assoc (others) :tr_import_log "…" :tr_gone "…")})))))
  (testing "a database shared with something else: the rest is silent, said once"
    (is (= :started (start-with {:global #{} :personal {}
                                 :silent {websocket/every-other-table "shared with other tests"}})))))
