(ns groundwork.wire.api-names-test
  "The public names of groundwork.wire's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.wire.api]))

(deftest the-api-names
  (is (= '#{schema decode encode fits? explain write-json read-json}
         (set (keys (ns-publics 'groundwork.wire.api))))))
