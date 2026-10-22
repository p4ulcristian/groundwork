(ns groundwork.server.background-chores.api-names-test
  "The public names of groundwork.server.background-chores' api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.background-chores.api]))

(deftest the-api-names
  (is (= '#{chores-table
           chore-schedules-table
           chores-tables
           add-chore-to-queue!
           make-give-up-exception
           start-workers!
           stop-workers!
           list-queued-chores
           list-dead-chores
           run-chore-again-now!}
         (set (keys (ns-publics 'groundwork.server.background-chores.api))))))
