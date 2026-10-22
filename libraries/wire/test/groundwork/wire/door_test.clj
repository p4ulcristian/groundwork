(ns groundwork.wire.door-test
  "The door of groundwork.wire: what it promises. Adding or removing a public
   name fails here, so a promise changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.wire.clojure-common]))

(deftest the-door
  (is (= '#{schema decode encode fits? explain write-json read-json}
         (set (keys (ns-publics 'groundwork.wire.clojure-common))))))
