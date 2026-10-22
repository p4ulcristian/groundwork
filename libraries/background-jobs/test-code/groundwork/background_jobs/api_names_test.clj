(ns groundwork.background-jobs.api-names-test
  "The public names of groundwork.background-jobs' api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.background-jobs.api]))

(deftest the-api-names
  (is (= '#{create-tables!
           add-job-to-queue!
           make-give-up-exception
           start-workers!
           stop-workers!
           list-queued-jobs
           list-dead-jobs
           run-job-again-now!}
         (set (keys (ns-publics 'groundwork.background-jobs.api))))))
