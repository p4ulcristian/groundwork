(ns groundwork.background-chores.api-names-test
  "The public names of groundwork.background-chores' api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.background-chores.api]))

(deftest the-api-names
  (is (= '#{create-tables!
           add-chore-to-queue!
           make-give-up-exception
           start-workers!
           stop-workers!
           list-queued-chores
           list-dead-chores
           run-chore-again-now!}
         (set (keys (ns-publics 'groundwork.background-chores.api))))))
