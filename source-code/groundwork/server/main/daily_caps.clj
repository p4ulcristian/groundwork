(ns groundwork.server.main.daily-caps
  "A handler's daily caps, counted by groundwork in Postgres, after
   doorman's check.

   Every app handler writes out :daily-caps beside bridge's :rate-limit,
   [] for none:

     :rate-limit {:max 3 :per-ms 60000}                              ; bridge's: bursts, in memory
     :daily-caps [{:max 10 :per :asker} {:max 300 :per :everyone}]   ; groundwork's: budgets, in Postgres

   :per is :asker (the person, a token's person, else the visitor, else,
   nobody signed in, the request's address) or :everyone. A day is a UTC
   date. A capped handler gets one step first among its steps,
   :groundwork/daily-caps, which runs after doorman's check (so only an
   asker the handler lets through spends its budget) and before the
   handler's own steps, inside a write's run-once step (a repeat answered
   from its one-time key isn't counted again). It counts in
   groundwork.daily_cap_counts, on a pool of its own (the :daily-caps
   settings' :pool-size), in a transaction of its own that commits at
   once: every call let through counts, whether or not the handler's work
   commits, and the handler's transaction never waits for a second
   connection of the app's pool. The day's rows of the call's caps are
   locked in order, then counted together only when every cap is below
   its :max; over one, nothing is counted and the call is refused,
   :groundwork/daily-cap-reached {:retry-after-ms n}, the time until
   midnight UTC. groundwork's background job, on the :forget-counts cron
   line, deletes the days before today."
  (:require
   [bridge.api :as bridge]
   [cellar.api :as database]
   [malli.core :as malli]
   [malli.error :as malli-error])
  (:import
   (java.time Duration Instant LocalDate ZoneOffset)))

(def daily-cap-counts-table
  "The day's count of each capped handler, per asker or for everyone, in
   groundwork's own folder."
  {:table     :groundwork/daily-cap-counts
   :columns   [[:daily-cap-count/message-name :string          {:primary-key? true}]
               [:daily-cap-count/counted-for  :string          {:primary-key? true}]
               [:daily-cap-count/day          :time/local-date {:primary-key? true}]
               [:daily-cap-count/count        :int]]
   :indexes   [{:columns [[:daily-cap-count/day :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private lock-count (database/make-query-from-sql-file "groundwork/server/main/lock_daily_cap_count.sql"))
(def ^:private add-to-count (database/make-query-from-sql-file "groundwork/server/main/add_to_daily_cap_count.sql"))
(def ^:private delete-old-counts (database/make-query-from-sql-file "groundwork/server/main/delete_old_daily_cap_counts.sql"))

(def refusals
  "groundwork's reason a capped handler refuses with, and its data's
   shape: :groundwork/daily-cap-reached, one of its :daily-caps is spent
   for today (UTC), {:retry-after-ms n} until midnight UTC. It needs its
   text in the app's catalogs once a handler is capped."
  {:groundwork/daily-cap-reached [:map {:closed true} [:retry-after-ms :int]]})

(def daily-caps-shape
  "A handler's :daily-caps: each cap's :max calls a UTC day, :per :asker
   or :everyone, every key written out; [] for none."
  [:vector [:map {:closed true}
            [:max [:int {:min 1}]]
            [:per [:enum :asker :everyone]]]])

(defn- check-handlers!
  "Every app handler writes out :daily-caps, in its shape, and none
   declares groundwork's reason itself; throws with every problem at once."
  [handlers]
  (let [problems (concat
                  (for [[handler-name handler] handlers
                        :when (not (contains? handler :daily-caps))]
                    (str handler-name " doesn't write out :daily-caps: [] for none, or [{:max 10 :per :asker} …]"))
                  (for [[handler-name {:keys [daily-caps] :as handler}] handlers
                        :when (contains? handler :daily-caps)
                        :let [explanation (malli/explain daily-caps-shape daily-caps)]
                        :when explanation]
                    (str handler-name "'s :daily-caps have problems: " (pr-str (malli-error/humanize explanation))))
                  (for [[handler-name handler] handlers
                        reason (keys refusals)
                        :when (contains? (:refusals handler) reason)]
                    (str handler-name " declares " reason ", groundwork's own: it is added to every capped handler")))]
    (when (seq problems)
      (throw (ex-info (str "The handlers have problems: " (pr-str (vec problems)))
                      {:type :groundwork.server.main/bad-handlers :problems (vec problems)})))))

(defn find-time-until-midnight
  "The milliseconds from now until the next UTC midnight, when the day's
   caps start again."
  [^Instant now]
  (let [midnight (-> (LocalDate/ofInstant now ZoneOffset/UTC) (.plusDays 1) (.atStartOfDay ZoneOffset/UTC) (.toInstant))]
    (.toMillis (Duration/between now midnight))))

(defn- find-counted-for
  "Whom a cap counts: everyone, or the asker, the person (a token's
   person too), else the visitor, else the request's address."
  [per {:doorman/keys [person-id visitor-id] :bridge/keys [request]} client-address-header]
  (case per
    :everyone "everyone"
    :asker    (cond person-id  (str "person " person-id)
                    visitor-id (str "visitor " visitor-id)
                    request    (str "address " (bridge/find-client-address request client-address-header))
                    :else      "nobody")))

(defn- count-call!
  "Counts the call on every cap, in one transaction of the counting pool,
   when each is below its :max; returns whether it counted."
  [counting-pool message-name daily-caps context client-address-header day]
  (let [most-by-counted-for (reduce (fn [most {:keys [max per]}]
                                      (update most (find-counted-for per context client-address-header) (fnil min max) max))
                                    (sorted-map) daily-caps)]
    (database/run-in-transaction! counting-pool {:read-only? false}
      (fn [transaction]
        (let [row-of   (fn [counted-for] {:daily-cap-count/message-name message-name
                                          :daily-cap-count/counted-for  counted-for
                                          :daily-cap-count/day          day})
              counts   (into {} (for [counted-for (keys most-by-counted-for)]
                                  [counted-for (:daily-cap-count/count (database/find-one-row transaction lock-count (row-of counted-for)))]))
              under?   (every? (fn [[counted-for most]] (< (get counts counted-for) most)) most-by-counted-for)]
          (when under?
            (doseq [counted-for (keys most-by-counted-for)]
              (database/run-statement! transaction add-to-count (row-of counted-for))))
          under?)))))

(defn- make-daily-caps-step [counting-pool handler-name daily-caps client-address-header]
  (let [message-name (subs (str handler-name) 1)]
    {:name  :groundwork/daily-caps
     :enter (fn [context]
              (let [now (Instant/now)]
                (if (count-call! counting-pool message-name daily-caps context client-address-header
                                 (LocalDate/ofInstant now ZoneOffset/UTC))
                  context
                  (bridge/refuse context :groundwork/daily-cap-reached {:retry-after-ms (find-time-until-midnight now)}))))}))

(defn put-daily-caps-into-handlers
  "The app's handlers, every one checked to write out :daily-caps; each
   capped one with the counting step first among its steps and
   groundwork's reason in its :refusals, the others as they are. Before
   groundwork's run-once and doorman's check, which put their steps
   around these."
  [handlers counting-pool client-address-header]
  (check-handlers! handlers)
  (into {}
        (map (fn [[handler-name {:keys [daily-caps] :as handler}]]
               [handler-name
                (if (seq daily-caps)
                  (-> handler
                      (update :steps #(into [(make-daily-caps-step counting-pool handler-name daily-caps client-address-header)] %))
                      (update :refusals merge refusals))
                  handler)]))
        handlers))

(def forget-counts-system-actor
  "The named system actor (doorman's) the forgetting runs as: it deletes
   from groundwork.daily_cap_counts, a table without sentences, which any
   system actor reaches; no app sentence names it."
  :groundwork-daily-caps)

(defn make-forget-counts-kind
  "groundwork's background job kind that deletes the counts of the days
   before today (UTC); its time limit and attempts are the :daily-caps
   settings' :forget-counts."
  [{{:keys [timeout-ms attempts]} :forget-counts}]
  {:name         :groundwork/forget-old-daily-cap-counts
   :system-actor forget-counts-system-actor
   :params-shape [:map {:closed true}]
   :run          (fn [{:keys [transaction]} _params]
                   (database/run-statement! transaction delete-old-counts
                                            {:daily-cap-count/day (LocalDate/now ZoneOffset/UTC)}))
   :timeout-ms   timeout-ms
   :attempts     attempts})

(defn make-forget-counts-schedule
  "The kind's schedule, on the :forget-counts :cron line (UTC)."
  [daily-caps-settings]
  {:kind (make-forget-counts-kind daily-caps-settings) :params {} :cron (get-in daily-caps-settings [:forget-counts :cron])})
