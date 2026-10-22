(ns groundwork.transport-server.api-names-test
  "The public names of groundwork.transport-server's api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.transport-server.api]))

(deftest the-api-names
  (is (= '#{default-addresses
           make-routes-for-messages
           start-server!
           stop-server!
           send-message-on-sse-line!
           send-message-on-ws-line!
           is-line-open?
           answer-message
           make-server-routes-for-pages
           make-ring-handler-from-routes}
         (set (keys (ns-publics 'groundwork.transport-server.api))))))
