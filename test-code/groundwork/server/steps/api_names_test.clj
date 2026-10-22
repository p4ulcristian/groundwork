(ns groundwork.server.steps.api-names-test
  "The public names of groundwork.server.steps' api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.steps.api]))

(deftest the-api-names
  (is (= '#{run-steps-until-answered
           add-answer-to-context
           does-context-have-answer?}
         (set (keys (ns-publics 'groundwork.server.steps.api))))))
