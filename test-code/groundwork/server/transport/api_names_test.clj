(ns groundwork.server.transport.api-names-test
  "The public names of groundwork.server.transport's api. Adding or removing
   one fails here, so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.server.transport.api]))

(deftest the-api-names
  (is (= '#{find-client-address
           settings-shape
           default-addresses
           make-routes-for-messages
           start-server!
           stop-server!
           send-message-on-sse-line!
           send-message-on-ws-line!
           is-line-open?
           answer-message
           make-server-routes-for-pages
           make-ring-handler-from-routes}
         (set (keys (ns-publics 'groundwork.server.transport.api))))))
