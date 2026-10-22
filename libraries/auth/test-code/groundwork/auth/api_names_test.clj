(ns groundwork.auth.api-names-test
  "The public names of groundwork.auth's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.auth.api]))

(deftest the-api-names
  (is (= '#{access-tables
           add-membership!
           add-role!
           add-scope!
           check-scope-kinds
           delete-ended-sessions!
           find-leaks
           install-access-functions!
           list-actions-of-person
           make-find-session-step
           make-sql-conditions
           put-access-into-handlers
           put-gates-on-tables
           remove-membership!
           sessions-table
           sign-in!
           sign-out!
           sign-out-everywhere!}
         (set (keys (ns-publics 'groundwork.auth.api))))))
