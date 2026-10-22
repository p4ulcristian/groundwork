(ns groundwork.steps.api-test
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [groundwork.steps.api :as steps]))

(defn- add-to-trail [step-name]
  {:name  step-name
   :enter (fn [context] (update context :trail conj step-name))})

(deftest steps-run-in-order-until-answered
  (testing "every step runs, in order, each on what the one before returned"
    (is (= [:a :b :c]
           (:trail (steps/run-steps-until-answered
                    [(add-to-trail :a) (add-to-trail :b) (add-to-trail :c)]
                    {:trail []})))))
  (testing "an answer stops the steps after it"
    (let [context (steps/run-steps-until-answered
                   [(add-to-trail :a)
                    {:name :answer :enter #(steps/add-answer-to-context % :done)}
                    (add-to-trail :c)]
                   {:trail []})]
      (is (= [:a] (:trail context)))
      (is (steps/does-context-have-answer? context))
      (is (= :done (:groundwork.steps/answer context)))))
  (testing "no steps: the context comes back as it was"
    (is (= {:trail []} (steps/run-steps-until-answered [] {:trail []})))))
