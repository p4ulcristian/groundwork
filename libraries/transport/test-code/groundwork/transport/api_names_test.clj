(ns groundwork.transport.api-names-test
  "The public names of groundwork.transport's server api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.transport.api]))

(deftest the-api-names
  (is (= '#{start-server!
           stop-server!
           make-ring-handler-from-steps
           make-ring-handler-for-sse
           make-ring-handler-for-ws
           send-message-on-sse-line!
           send-message-on-ws-line!
           is-line-open?}
         (set (keys (ns-publics 'groundwork.transport.api))))))
