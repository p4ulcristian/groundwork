(ns groundwork.web.api-names-test
  "The public names of groundwork.web's api. Adding or removing one fails
   here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.web.api]))

(deftest the-api-names
  (is (= '#{make-ring-handler-from-steps
           make-ring-handler-from-routes
           start-server!
           stop-server!}
         (set (keys (ns-publics 'groundwork.web.api))))))
