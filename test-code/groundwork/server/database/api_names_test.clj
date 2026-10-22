(ns groundwork.server.database.api-names-test
  "The public names of groundwork.server.database's api. Adding or removing one
   fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.database.api]))

(deftest the-api-names
  (is (= '#{settings-shape run-and-list-tables-read turn-table-name-into-table-key start-listening! stop-listening!
           start-pool! stop-pool!
           make-query-from-sql-file make-query-from-sql-text
           find-rows find-one-row run-statement! run-in-transaction!
           make-plan-from-tables run-instant-part! run-slow-part! migrate!
           does-database-match-tables? turn-table-into-row-shape
           turn-key-into-column-name turn-table-key-into-table-name
           grant-tables-to-role!}
         (set (keys (ns-publics 'groundwork.server.database.api))))))
