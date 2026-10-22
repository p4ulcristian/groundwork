(ns groundwork.sql.api-names-test
  "The public names of groundwork.sql's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.sql.api]))

(deftest the-api-names
  (is (= '#{start-pool!
           stop-pool!
           make-query-from-sql-file
           find-rows
           find-one-row
           run-statement!
           run-in-transaction!}
         (set (keys (ns-publics 'groundwork.sql.api))))))
