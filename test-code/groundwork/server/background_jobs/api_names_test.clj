(ns groundwork.server.background-jobs.api-names-test
  "The public names of groundwork.server.background-jobs' api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.background-jobs.api]))

(deftest the-api-names
  (is (= '#{background-jobs-table
           background-job-schedules-table
           background-jobs-tables
           add-background-job-to-queue!
           make-give-up-exception
           start-workers!
           stop-workers!
           list-queued-background-jobs
           list-dead-background-jobs
           run-background-job-again-now!}
         (set (keys (ns-publics 'groundwork.server.background-jobs.api))))))
