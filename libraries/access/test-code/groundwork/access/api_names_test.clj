(ns groundwork.access.api-names-test
  "The public names of groundwork.access's api."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.access.api]))

(deftest the-api-names
  (is (= '#{access-tables check-scope-kinds add-scope! add-role! add-membership! remove-membership! list-actions-of-person}
         (set (keys (ns-publics 'groundwork.access.api))))))
