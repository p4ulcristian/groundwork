(ns groundwork.server.sessions.api-names-test
  "The public names of groundwork.server.sessions' api."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.sessions.api]))

(deftest the-api-names
  (is (= '#{find-session-key-of-request
           settings-shape
           sessions-table sign-in! sign-out! sign-out-everywhere! delete-ended-sessions! make-find-session-step}
         (set (keys (ns-publics 'groundwork.server.sessions.api))))))
