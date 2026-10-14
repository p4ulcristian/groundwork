(ns groundwork.stop-test
  "Stopping an app: every part, in reverse start order, without searching
   the settings again."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [integrant.core :as ig]))

(deftest the-parts-list-starts-each-part-after-what-it-needs
  ;; stop walks this list backwards, so it must be a start order
  (let [parts @#'gw/parts]
    (doseq [[i [k {:keys [needs]}]] (map-indexed vector parts)
            need needs]
      (testing (str k " needs " need)
        (is (< (.indexOf ^java.util.List (vec (keys parts)) need) i))))))

(deftest stop-does-not-search-the-settings
  ;; Integrant's halt! works the order out again from every part's settings;
  ;; a large app's settings took over ten seconds to search
  (let [app (gw/start (assoc-in app-settings [:server :port] 0))]
    (with-redefs [ig/dependency-graph (fn [& _] (throw (ex-info "searched the settings" {})))]
      (is (nil? (gw/stop app))))
    (is (nil? (gw/current)) "the app is no longer the current one")))

(deftest a-part-that-throws-on-stop-does-not-keep-the-others-running
  (let [app     (gw/start (assoc-in app-settings [:server :port] 0))
        stopped (atom [])
        halt    (get-method ig/halt-key! :groundwork.core/part)]
    (with-redefs [ig/halt-key! (fn [k v]
                                 (swap! stopped conj k)
                                 (if (= k :groundwork/server)
                                   (do (halt k v) (throw (ex-info "the server's stop failed" {})))
                                   (halt k v)))]
      (is (thrown-with-msg? Exception #"the server's stop failed" (gw/stop app))))
    (is (= :groundwork/server (first @stopped)) "the server stops first")
    (is (= :groundwork/db (last @stopped)) "and the database last, after the failure")))
