(ns groundwork.server.wire.api-names-test
  "The public names of groundwork.server.wire's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.wire.api]))

(deftest the-api-names
  (is (= '#{make-schema-from-shape
           turn-json-data-into-shaped-data-or-errors
           turn-shaped-data-into-json-data
           does-data-fit-shape?
           find-errors-in-data-for-shape
           turn-data-into-json-text
           turn-json-text-into-data
           turn-shape-into-closed-shape}
         (set (keys (ns-publics 'groundwork.server.wire.api))))))
