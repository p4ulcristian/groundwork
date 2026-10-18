(ns groundwork.repl.browser-test
  "The browser's REPL helpers read the store."
  (:require
   [cljs.test :refer [deftest is]]
   [groundwork.repl.browser :as repl]
   [re-frame.db :as rf-db]))

(deftest the-store-from-the-repl
  (reset! rf-db/app-db {:groundwork/endpoints-store {:entries {[:jobs/list {:shop/id "acme"}]
                                                      {:status :ready :owners #{:board} :data [1 2 3] :stale? true}}}})
  (is (= {[:jobs/list {:shop/id "acme"}] {:status :ready :owners #{:board} :stale? true :items 3}}
         (repl/entries)))
  (is (= [1 2 3] (:data (repl/answer :jobs/list {:shop/id "acme"}))))
  (reset! rf-db/app-db {}))
