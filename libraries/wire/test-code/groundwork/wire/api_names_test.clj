(ns groundwork.wire.api-names-test
  "The public names of groundwork.wire's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.wire.api]))

(deftest the-api-names
  (is (= '#{make-schema-from-shape
           turn-json-data-into-value-or-errors
           turn-value-into-json-data
           does-value-fit-shape?
           find-errors-in-value-for-shape
           turn-data-into-json-text
           turn-json-text-into-data}
         (set (keys (ns-publics 'groundwork.wire.api))))))
