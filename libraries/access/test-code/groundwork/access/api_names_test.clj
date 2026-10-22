(ns groundwork.access.api-names-test
  "The public names of groundwork.access's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.access.api]))

(deftest the-api-names
  (is (= '#{access-tables check-scope-kinds add-scope! add-role! add-membership! remove-membership!
           list-actions-of-person make-sql-conditions install-access-functions!
           put-access-into-handlers put-gates-on-tables find-leaks}
         (set (keys (ns-publics 'groundwork.access.api))))))
