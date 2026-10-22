(ns house.server.main.api-names-test
  "The public names of house.server.main's api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [house.server.main.api]))

(deftest the-api-names
  (is (= '#{page-settings-shape
           health-settings-shape
           errors-settings-shape
           migration-settings-shape
           doorbell-settings-shape
           browser-build-settings-shape
           browser-wire-settings-shape
           my-actions-settings-shape
           run-once-settings-shape
           settings-shape
           check-settings
           house-tables
           make-ready-tables
           migrate-app!
           start-app!
           stop-app!}
         (set (keys (ns-publics 'house.server.main.api))))))
