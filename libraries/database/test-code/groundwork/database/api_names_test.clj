(ns groundwork.database.api-names-test
  "The public names of groundwork.database's api. Adding or removing one
   fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.database.api]))

(deftest the-api-names
  (is (= '#{start-pool! stop-pool!
           make-query-from-sql-file make-query-from-sql-text
           find-rows find-one-row run-statement! run-in-transaction!
           make-plan-from-tables run-instant-part! run-slow-part! migrate!
           does-database-match-tables? turn-table-into-row-shape
           turn-key-into-column-name turn-table-key-into-table-name
           grant-tables-to-role!}
         (set (keys (ns-publics 'groundwork.database.api))))))
