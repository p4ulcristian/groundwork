(ns groundwork.sessions.api-names-test
  "The public names of groundwork.sessions' api."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.sessions.api]))

(deftest the-api-names
  (is (= '#{sessions-table sign-in! sign-out! sign-out-everywhere! delete-ended-sessions! make-find-session-step}
         (set (keys (ns-publics 'groundwork.sessions.api))))))
