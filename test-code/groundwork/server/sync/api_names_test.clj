(ns groundwork.server.sync.api-names-test
  "The public names of groundwork.server.sync's api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.sync.api]))

(deftest the-api-names
  (is (= '#{install-sync-function!
           check-sync-of-tables
           put-sync-on-tables
           put-sync-into-handlers
           add-sse-line!
           remove-sse-line!
           start-sync!
           stop-sync!
           count-open-lines}
         (set (keys (ns-publics 'groundwork.server.sync.api))))))
