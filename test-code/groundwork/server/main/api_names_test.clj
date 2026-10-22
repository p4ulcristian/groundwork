(ns groundwork.server.main.api-names-test
  "The public names of groundwork.server.main's api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.main.api]))

(deftest the-api-names
  (is (= '#{page-settings-shape
           health-settings-shape
           migration-settings-shape
           doorbell-settings-shape
           browser-build-settings-shape
           settings-shape
           check-settings
           groundwork-tables
           make-ready-tables
           migrate-app!
           start-app!
           stop-app!}
         (set (keys (ns-publics 'groundwork.server.main.api))))))
