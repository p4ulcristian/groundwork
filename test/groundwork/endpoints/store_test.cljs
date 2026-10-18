(ns groundwork.endpoints.store-test
  "The store's rules, as plain functions: one request per question, the
   newest answer wins, old data stays while refreshing, bells mark what's on
   screen, unneeded entries go after five minutes."
  (:require
   [cljs.test :refer [deftest is testing]]
   [groundwork.endpoints.store :as store]))

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
  (let [shop  "7f3c1e2a-0000-4000-8000-000000000001"
        [s _] (ensured store/empty-store :groundwork {:endpoint :groundwork/grants :params {}})
        [s n] (store/start-fetch s [:groundwork/grants {}])
        s     (store/answered s [:groundwork/grants {}] n
                              {:status :ok :data [{:grant/scope-id shop :grant/action :jobs/move}
                                                  {:grant/scope-id nil :grant/action :catalogue/see}]})]
    (is (store/can? s :jobs/move shop))
    (is (store/can? s :jobs/move (uuid shop)) "a UUID value counts as its text")
    (is (not (store/can? s :money/move shop)))
    (testing "a grant with no scope is for every scope"
      (is (store/can? s :catalogue/see shop))
      (is (store/can? s :catalogue/see "7f3c1e2a-0000-4000-8000-000000000002"))
      (is (not (store/can? s :jobs/move "7f3c1e2a-0000-4000-8000-000000000002"))))
    (testing "several scopes: in every one of them"
      (is (store/can? s :jobs/move [shop (uuid shop)]))
      (is (not (store/can? s :jobs/move [shop "7f3c1e2a-0000-4000-8000-000000000002"]))
          "one scope it isn't given in says no")
      (is (store/can? s :catalogue/see [shop "7f3c1e2a-0000-4000-8000-000000000002"]))
      (is (not (store/can? s :catalogue/see [])) "no scope named is no"))))

(deftest one-question-however-its-ids-were-given
  (let [id    "7f3c1e2a-0000-4000-8000-000000000001"
        [s _] (ensured store/empty-store :board {:endpoint :jobs/list :params {:shop/id id}})
        [s _] (ensured s :panel {:endpoint :jobs/list :params {:shop/id (uuid id)}})]
    (is (= [[:jobs/list {:shop/id id}]] (keys (:entries s))) "one entry, its id as text")))

(deftest too-many-is-kept-as-the-error
  (let [[s _] (ensured store/empty-store :board jobs)
        [s n] (store/start-fetch s jobs-key)
        limit {:status :too-many :limit :per-minute :retry-after-seconds 40}
        s     (store/answered s jobs-key n limit)]
    (is (= :error (get-in s [:entries jobs-key :status])))
    (is (= limit (get-in s [:entries jobs-key :error])) "which limit, and when to come back")))

(deftest asking-again
  (let [[s _] (ensured store/empty-store :board jobs)
        [s n] (store/start-fetch s jobs-key)]
    (testing "a question that is out is asked once more when its answer lands"
      (let [[s fetch] (store/ask-again s jobs)]
        (is (= [] fetch))
        (let [s         (store/answered s jobs-key n {:status :ok :data [:jobs]})
              [s fetch] (store/asked-again s jobs-key)]
          (is (= [jobs-key] fetch))
          (is (= [] (second (store/asked-again s jobs-key))) "once"))))
    (testing "a question at rest is asked now"
      (let [s (store/answered s jobs-key n {:status :ok :data [:jobs]})]
        (is (= [jobs-key] (second (store/ask-again s jobs))))))
    (testing "a question nothing has ensured isn't asked"
      (is (= [] (second (store/ask-again s machines)))))))

(deftest a-writes-answer-carries-its-bell
  (let [jobs-form     ["jobs/list" {:shop/id "acme"}]
        machines-form ["machines/list" {:shop/id "acme"}]
        [s _]  (ensured store/empty-store :board jobs machines)
        [s n1] (store/start-fetch s jobs-key)
        s      (store/answered s jobs-key n1 {:status :ok :data [:jobs]})
        [s _]  (store/start-fetch s [:machines/list {:shop/id "acme"}])]
    (testing "what the answer says changed is asked at once; a question that is out, once more after"
      (let [[s fetch] (store/rang-in-answer s "t1" [jobs-form machines-form] [jobs-form machines-form])]
        (is (= [jobs-key] fetch))
        (is (:again? (get-in s [:entries [:machines/list {:shop/id "acme"}]])))
        (testing "the websocket's bell for the same write adds nothing for what the answer covered"
          (is (= [] (store/stale-on-screen (store/rang s [jobs-form] "t1"))))
          (is (= [jobs-key] (store/stale-on-screen (store/rang s [jobs-form] "another-write")))))))
    (testing "the bell first, then the answer: what the bell rang isn't asked again"
      (let [s         (store/rang s [jobs-form] "t2")
            [_ fetch] (store/rang-in-answer s "t2" [jobs-form machines-form] [jobs-form])]
        (is (= [] fetch))))
    (testing "a read that came on screen after the write was sent still hears the websocket"
      (let [[s _] (store/rang-in-answer s "t3" [machines-form] [])]
        (is (= [jobs-key] (store/stale-on-screen (store/rang s [jobs-form] "t3"))))))
    (testing "only the newest tags are remembered"
      (let [s (reduce #(store/rang %1 [jobs-form] (str "tag-" %2)) s (range (+ 5 store/tags-kept)))]
        (is (= store/tags-kept (count (:tags s)) (count (:tag-order s))))))))

(deftest an-owner-needs-exactly-these-now
  (let [orders  {:endpoint :orders/list :params {}}
        shop    {:endpoint :shop/get :params {}}
        order   {:endpoint :orders/get :params {:order/id "5"}}
        [s f1]  (store/own-asks store/empty-store :route [orders shop] 1000)
        [s _]   (ensured s :panel orders)
        [s f2]  (store/own-asks s :route [order shop] 2000)]
    (is (= #{[:orders/list {}] [:shop/get {}]} (set f1)))
    (is (= [[:orders/get {:order/id "5"}]] f2) "what the next list shares isn't asked again")
    (is (= #{:route} (get-in s [:entries [:shop/get {}] :owners])))
    (is (= #{:panel} (get-in s [:entries [:orders/list {}] :owners])) "still needed elsewhere: kept")
    (let [[s _] (store/own-asks s :route [] 3000)]
      (is (= 3000 (get-in s [:entries [:shop/get {}] :released-at])) "nothing needs it: let go, with the time")
      (is (nil? (get-in s [:entries [:orders/list {}] :released-at]))))))

(deftest fresh-for-a-while
  (let [ask            (assoc jobs :fresh-ms 60000)
        [store ks]     (store/ensure-asks store/empty-store :page [ask] 1000)
        [store n]      (store/start-fetch store (first ks))
        store          (store/answered store jobs-key n {:status :ok :data [1]} 2000)]
    (testing "ensured again within its time, nothing is asked"
      (is (= [] (second (store/ensure-asks store :panel [ask] 30000)))))
    (testing "past its time it is asked"
      (is (= [jobs-key] (second (store/ensure-asks store :panel [ask] 62000)))))
    (testing "a bell makes it stale whatever its age"
      (is (= [jobs-key] (second (store/ensure-asks (store/changed store [(:form (get-in store [:entries jobs-key]))])
                                                   :panel [ask] 30000)))))
    (testing "without :fresh-ms every ensure asks"
      (is (= [jobs-key] (second (store/ensure-asks store :panel [jobs] 3000)))))))

(deftest asking-again-after-a-failed-first-load-reads-as-loading
  (let [[store ks] (store/ensure-asks store/empty-store :page [jobs])
        [store n]  (store/start-fetch store (first ks))
        store      (store/answered store jobs-key n {:status :error :message "down"})
        _          (is (= :error (get-in store [:entries jobs-key :status])))
        [store _]  (store/start-fetch store jobs-key)]
    (is (= :loading (get-in store [:entries jobs-key :status])))
    (is (nil? (get-in store [:entries jobs-key :error])))))

(deftest waiting-once-for-an-answer
  (let [[store ks] (store/ensure-asks store/empty-store :page [jobs])
        [store n]  (store/start-fetch store (first ks))]
    (testing "a question nothing ensured tells nothing"
      (is (= false (second (store/once-answered store/empty-store jobs [:loaded])))))
    (testing "with the request out, the event waits for the answer"
      (let [[store now?] (store/once-answered store jobs [:loaded])
            store        (store/answered store jobs-key n {:status :ok :data [1]})
            [store told] (store/heard store jobs-key {:status :ok :data [1]})]
        (is (= false now?))
        (is (= [[:loaded]] told))
        (is (= [] (second (store/heard store jobs-key {:status :ok})))
            "once")))
    (testing "a failed answer drops it"
      (let [[store _]    (store/once-answered store jobs [:loaded])
            store        (store/answered store jobs-key n {:status :error})
            [store told] (store/heard store jobs-key {:status :error})]
        (is (= [] told))
        (is (empty? (:listeners store)))))
    (testing "with the answer there and nothing out, it is told at once"
      (let [store (store/answered store jobs-key n {:status :ok :data [1]})]
        (is (= true (second (store/once-answered store jobs [:loaded]))))))))
