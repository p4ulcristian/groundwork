(ns groundwork.schema.api-names-test
  "The public names of groundwork.schema's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.schema.api]))

(deftest the-api-names
  (is (= '#{make-plan-from-tables
           run-instant-part!
           run-slow-part!
           migrate!
           does-database-match-tables?
           turn-table-into-row-shape
           turn-key-into-column-name
           turn-table-key-into-table-name
           grant-tables-to-role!}
         (set (keys (ns-publics 'groundwork.schema.api))))))
