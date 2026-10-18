(ns groundwork.jobs-test
  "Background jobs: queued in the app's transaction, claimed once, retried,
   dead and brought back, behind the wall, on a clock."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.jobs :as jobs]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.repl :as repl]
   [groundwork.test-support :refer [app-settings sql!]])
  (:import
   (java.time Instant)
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id)) (def paul (id))
(def acme-note (id)) (def zinc-note (id))

(def ran (atom []))
(def flaky-runs (atom 0))

(pg/defsql jb-count-notes)

(def kinds
  {:test/ok     {:run (fn [_ params] (swap! ran conj params)) :params [:map [:n :int]] :timeout-ms 5000 :attempts 3}
   :test/flaky  {:run        (fn [_ _] (when (= 1 (swap! flaky-runs inc)) (throw (ex-info "not yet" {}))))
                 :params     [:map] :timeout-ms 5000 :attempts 3}
   :test/broken {:run (fn [_ _] (throw (ex-info "always" {}))) :params [:map] :timeout-ms 5000 :attempts 2}
   :test/gone   {:run (fn [_ _] (throw (jobs/give-up "The order was deleted" {:order/id 7})))
                 :params [:map] :timeout-ms 5000 :attempts 5}
   :test/slow   {:run (fn [_ _] (Thread/sleep 5000)) :params [:map] :timeout-ms 200 :attempts 1}
   :test/notes  {:run        (fn [{:keys [db]} _] (swap! ran conj (:count (pg/row db jb-count-notes))))
                 :params     [:map [:note/id :uuid]]
                 :timeout-ms 5000 :attempts 1
                 :runs-in    [:note :note/id]}
   :test/system {:run (fn [{:keys [db]} _] (swap! ran conj (:count (pg/row db jb-count-notes))))
                 :params [:map] :timeout-ms 5000 :attempts 1}})

(def job-settings {:kinds [kinds] :schedules [] :workers 2 :poll-ms 200 :on? false})

(def note-endpoints
  {:notes/remind {:write      (fn [ctx {:keys [n]}] (jobs/enqueue! ctx :test/ok {:n n}) "queued")
                  :access     :public
                  :input      [:map [:n :int]]
                  :answer     :string}})

(def ^:dynamic *app* nil)

(use-fixtures :once
  (fn [t]
    (sql! "DROP TABLE IF EXISTS jb_notes;
           CREATE TABLE jb_notes (note__id uuid PRIMARY KEY, note__shop_id uuid NOT NULL)")
    ;; before the start: from then on the wall guards the table
    (sql! "INSERT INTO jb_notes VALUES (?, ?), (?, ?)" acme-note acme zinc-note zinc)
    (let [app (gw/start (-> app-settings
                            (assoc :jobs job-settings)
                            (assoc :permissions {:scopes  {:shop {:actions {:notes/see #{:read}} :roles {} :parent nil}}
                                                 :records {:note {:table :jb_notes :id :note/id :whose {:note/shop-id :shop}}}
                                                 :rules   [{:allow :notes/see :on :note :to [:member :note/shop-id]}]})
                            (assoc-in [:endpoints :list] [note-endpoints])))]
      (try
        (sql! "TRUNCATE groundwork.jobs, groundwork.job_schedules")
        (perm/create-scope! (gw/permissions app) {:kind :shop :id acme :parent nil :owner paul})
        (perm/create-scope! (gw/permissions app) {:kind :shop :id zinc :parent nil :owner paul})
        (binding [*app* app] (t))
        (finally
          (gw/stop app)
          (sql! "DROP TABLE IF EXISTS jb_notes"))))))

(use-fixtures :each
  (fn [t]
    (sql! "TRUNCATE groundwork.jobs")
    (t)))

(defn- part [] (gw/jobs *app*))
(defn- db [] (gw/db *app*))

(defn- claim-and-run []
  (when-let [job (pg/row (db) jobs/claim)]
    (jobs/run-one! (part) job)))

(defn- state [job-id]
  (:job/state (first (filter #(= job-id (:job/id %))
                             (concat (jobs/queued (part)) (jobs/dead (part))
                                     (pg/rows (db) jobs/by-state {:job/state "done" :limit 100}))))))

(defn- enqueue! [kind params & [opts]]
  (pg/with-transaction [tx (db)]
    (jobs/enqueue! {:db tx :jobs (part)} kind params (or opts {}))))

(deftest cron-lines
  (let [at (Instant/parse "2026-10-12T17:30:00Z")]
    (is (= (Instant/parse "2026-10-12T18:00:00Z") (jobs/next-time "0 18 * * *" at)))
    (is (= (Instant/parse "2026-10-13T18:00:00Z") (jobs/next-time "0 18 * * *" (Instant/parse "2026-10-12T18:00:00Z"))))
    (is (= (Instant/parse "2026-10-12T17:45:00Z") (jobs/next-time "*/15 * * * *" at)))
    (is (= (Instant/parse "2026-10-19T09:00:00Z") (jobs/next-time "0 9 * * 1" at)) "Mondays")
    (is (= (Instant/parse "2026-11-01T00:00:00Z") (jobs/next-time "0 0 1 * *" at)))
    (is (thrown? Exception (jobs/next-time "61 * * * *" at)))))

(deftest a-job-exists-only-if-its-transaction-commits
  (is (thrown? Exception (pg/with-transaction [tx (db)]
                           (jobs/enqueue! {:db tx :jobs (part)} :test/ok {:n 99})
                           (throw (ex-info "rolled back" {})))))
  (is (not-any? #(= "{\"n\": 99}" (:job/params %)) (jobs/queued (part))))
  (testing "params must fit their shape"
    (is (thrown? Exception (enqueue! :test/ok {:n "one"})))))

(deftest one-job-per-key
  (reset! ran [])
  (let [first-job  (enqueue! :test/ok {:n 7} {:key "email:7"})
        second-job (enqueue! :test/ok {:n 7} {:key "email:7"})]
    (is (= first-job second-job) "the second enqueue keeps the first")
    (is (= :done (claim-and-run)))
    (is (nil? (claim-and-run)) "one job, not two")
    (is (= [{:n 7}] @ran))
    (testing "a done job still holds its key"
      (is (= first-job (enqueue! :test/ok {:n 7} {:key "email:7"})))
      (is (nil? (claim-and-run))))))

(deftest a-job-runs-once-and-is-done
  (reset! ran [])
  (let [job (enqueue! :test/ok {:n 1})]
    (is (= :done (claim-and-run)))
    (is (= [{:n 1}] @ran))
    (is (= "done" (state job)))
    (is (nil? (claim-and-run)) "nothing left to claim")))

(deftest a-failing-job-waits-then-runs-again-then-dies
  (let [flaky (enqueue! :test/flaky {})]
    (is (= :queued (claim-and-run)) "the first try failed; it waits")
    (sql! "UPDATE groundwork.jobs SET job__run_at = now() WHERE job__id = ?" flaky)
    (is (= :done (claim-and-run)) "the second try ran"))
  (let [broken (enqueue! :test/broken {})]
    (is (= :queued (claim-and-run)))
    (sql! "UPDATE groundwork.jobs SET job__run_at = now() WHERE job__id = ?" broken)
    (is (= :dead (claim-and-run)) "after its attempts")
    (is (re-find #"always" (:job/last-error (first (filter #(= broken (:job/id %)) (jobs/dead (part)))))))
    (testing "retry! brings it back, its attempts afresh"
      (is (true? (jobs/retry! (part) broken)))
      (is (= "queued" (state broken))))))

(deftest the-repl-lists-jobs-and-retries-a-dead-one
  (let [broken (enqueue! :test/broken {})]
    (claim-and-run)
    (sql! "UPDATE groundwork.jobs SET job__run_at = now() WHERE job__id = ?" broken)
    (is (= :dead (claim-and-run)))
    (let [waiting (enqueue! :test/ok {:n 5})]
      (is (= [waiting] (mapv :job/id (repl/queued-jobs *app*))))
      (is (= [broken] (mapv :job/id (repl/dead-jobs *app*))))
      (is (re-find #"always" (:job/last-error (first (repl/dead-jobs *app*)))))
      (is (true? (repl/retry-job! *app* broken)))
      (is (= #{waiting broken} (set (map :job/id (repl/queued-jobs *app*)))))
      (is (empty? (repl/dead-jobs *app*)))
      (is (false? (repl/retry-job! *app* (id))) "no such job"))))

(deftest a-job-can-give-up-for-good
  (let [job (enqueue! :test/gone {})]
    (is (= :dead (claim-and-run)) "dead at once, with attempts left")
    (is (re-find #"The order was deleted"
                 (:job/last-error (first (filter #(= job (:job/id %)) (jobs/dead (part)))))))))

(deftest a-job-has-a-time-limit
  (let [job (enqueue! :test/slow {})]
    (is (= :dead (claim-and-run)))
    (is (re-find #"Ran longer than 200 ms" (:job/last-error (first (filter #(= job (:job/id %)) (jobs/dead (part)))))))))

(deftest a-job-sees-what-its-scope-allows
  (reset! ran [])
  (enqueue! :test/notes {:note/id acme-note})
  (is (= :done (claim-and-run)))
  (enqueue! :test/system {})
  (is (= :done (claim-and-run)))
  (is (= [1 2] @ran) "a scoped job sees its shop's note; a system job sees both"))

(deftest workers-run-jobs-by-themselves-and-on-a-clock
  (reset! ran [])
  (let [on (jobs/start {:settings     (assoc job-settings :on? true
                                             :schedules [{:job :test/ok :params {:n 7} :cron "* * * * *"}])
                        :db           (db)
                        :permissions  (gw/permissions *app*)
                        :app-settings app-settings})
        wait (fn [pred] (loop [n 0] (cond (pred) true (< n 100) (do (Thread/sleep 100) (recur (inc n))) :else false)))]
    (try
      (testing "a job queued by an endpoint's write runs after the commit"
        (is (= {:status :ok :data "queued"}
               (endpoints/call (gw/endpoints *app*) {:endpoint :notes/remind :params {:n 5} :person nil :flags #{}})))
        (is (wait #(some #{{:n 5}} @ran))))
      (testing "a due schedule queues its job once, and moves on"
        (sql! "UPDATE groundwork.job_schedules SET job_schedule__next_at = now() - interval '1 minute'")
        (.offer ^java.util.concurrent.ArrayBlockingQueue (:wake on) :test)
        (is (wait #(some #{{:n 7}} @ran)))
        (is (pos? (-> (sql! "SELECT count(*) AS n FROM groundwork.job_schedules WHERE job_schedule__next_at > now()")
                      first vals first))))
      (finally (jobs/stop on)))))

(deftest jobs-can-be-off
  (is (nil? (:dispatcher (part))) "the app's own part runs nothing: :on? false"))
