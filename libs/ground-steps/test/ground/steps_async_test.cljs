(ns ground.steps-async-test
  (:require
   [cljs.test :refer [deftest is async]]
   [ground.steps :as steps]))

(def adding
  {:name  :test/add
   :enter (fn [ctx] (steps/effect ctx {:effect :test/add :x 1 :y 2}
                                  (fn [ctx sum] (assoc ctx :sum sum))))})

(deftest awaited-work
  (async done
         (-> (steps/run-async {:test/add (fn [{:keys [x y]}]
                                           (js/Promise. (fn [ok _] (js/setTimeout #(ok (+ x y)) 5))))}
                              [adding] {})
             (.then (fn [ctx] (is (= 3 (:sum ctx))) (done))))))

(deftest a-rejected-promise-is-an-error-of-its-step
  (async done
         (-> (steps/run-async {:test/add (fn [_] (js/Promise.reject (ex-info "database down" {})))}
                              [adding] {})
             (.then (fn [ctx]
                      (is (= {:step :test/add :phase :enter :effect :test/add}
                             (select-keys (steps/error ctx) [:step :phase :effect])))
                      (done))))))
