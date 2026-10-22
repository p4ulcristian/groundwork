(ns groundwork.routes.api-names-test
  "The public names of groundwork.routes' server api."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork.routes.api]))

(deftest the-api-names
  (is (= '#{make-ring-handler-from-routes make-server-routes-for-pages}
         (set (keys (ns-publics 'groundwork.routes.api))))))
