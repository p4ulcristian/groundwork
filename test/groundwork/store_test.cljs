(ns groundwork.store-test
  "The store's rules, as plain functions: one request per question, the
   newest answer wins, old data stays while refreshing, bells mark what's on
   screen, unneeded entries go after five minutes."
  (:require
   [cljs.test :refer [deftest is testing]]
   [groundwork.store :as store]))

(def jobs {:endpoint :jobs/list :params {:shop/id "acme"}})
(def machines {:endpoint :machines/list :params {:shop/id "acme"}})
(def jobs-key [:jobs/list {:shop/id "acme"}])

(defn- ensured [s owner & asks]
  (store/ensure-asks s owner asks))

(deftest one-question-one-entry
  (let [[s fetch] (ensured store/empty-store :board jobs machines)]
    (is (= 2 (count fetch)))
    (is (= :loading (get-in s [:entries jobs-key :status])))
    (let [[s _]       (store/start-fetch s jobs-key)
          [s fetch-2] (ensured s :panel jobs)]
      (is (= [] fetch-2) "being asked already: not asked twice")
      (is (= #{:board :panel} (get-in s [:entries jobs-key :owners]))))))

(deftest the-newest-answer-wins
  (let [[s _]  (ensured store/empty-store :board jobs)
        [s n1] (store/start-fetch s jobs-key)
        [s n2] (store/start-fetch s jobs-key)
        s      (store/answered s jobs-key n2 {:status :ok :data [:new]})
        s      (store/answered s jobs-key n1 {:status :ok :data [:old]})]
    (is (= [:new] (get-in s [:entries jobs-key :data])) "the late answer to the older request is dropped")))

(deftest old-data-stays-while-refreshing
  (let [[s _]  (ensured store/empty-store :board jobs)
        [s n1] (store/start-fetch s jobs-key)
        s      (store/answered s jobs-key n1 {:status :ok :data [:jobs]})
        [s n2] (store/start-fetch s jobs-key)
        s      (store/answered s jobs-key n2 {:status :error :message "No connection"})
        entry  (get-in s [:entries jobs-key])]
    (is (= :ready (:status entry)))
    (is (= [:jobs] (:data entry)))
    (is (= :error (get-in entry [:refresh-error :status])))
    (testing "a first load that fails is an error"
      (let [[s _] (ensured store/empty-store :board jobs)
            [s n] (store/start-fetch s jobs-key)
            s     (store/answered s jobs-key n {:status :not-found})]
        (is (= {:status :not-found} (get-in s [:entries jobs-key :error])))
        (is (= :error (get-in s [:entries jobs-key :status])))))))

(deftest bells-mark-what-is-on-screen
  (let [[s _] (ensured store/empty-store :board jobs machines)
        s     (store/release-owner s :board 0)
        [s _] (ensured s :panel jobs)
        s     (store/changed s [["jobs/list" {:shop/id "acme"}] ["machines/list" {:shop/id "acme"}]])]
    (is (= [jobs-key] (store/stale-on-screen s)) "machines aren't on screen anymore")
    (is (= [["jobs/list" {:shop/id "acme"}]] (store/watched s)))))

(deftest unneeded-entries-go-after-five-minutes
  (let [[s _] (ensured store/empty-store :board jobs)
        s     (store/release-owner s :board 1000)]
    (is (contains? (:entries (store/collect s (+ 1000 store/gc-ms -1))) jobs-key) "kept a while, so going back is instant")
    (is (not (contains? (:entries (store/collect s (+ 1000 store/gc-ms))) jobs-key)))
    (testing "needed again before then, it stays"
      (let [[s _] (ensured s :board jobs)]
        (is (contains? (:entries (store/collect s (+ 1000 store/gc-ms 1))) jobs-key))))))

(deftest can-i
  (let [shop  #uuid "7f3c1e2a-0000-4000-8000-000000000001"
        [s _] (ensured store/empty-store :groundwork {:endpoint :groundwork/grants :params {}})
        [s n] (store/start-fetch s [:groundwork/grants {}])
        s     (store/answered s [:groundwork/grants {}] n
                              {:status :ok :data [{:grant/scope-id shop :grant/action :jobs/move}]})]
    (is (store/can? s :jobs/move shop))
    (is (not (store/can? s :money/move shop)))))
