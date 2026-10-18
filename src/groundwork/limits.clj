(ns groundwork.limits
  "How often a caller may ask: rate limits, for endpoints and plain routes.

     :limits {:public   {:reads-per-minute 600 :writes-per-minute 60}   ; or nil
              :on-limit (fn [{:keys [id limit caller]}] …)}             ; or nil

   An endpoint or a route declares its own limit:

     :rate-limit {:per-minute 10}                    ; a burst bucket, refilled over the minute
     :rate-limit {:per-day 20 :by :person}           ; a cap, counted in Postgres, reset at midnight UTC
     :rate-limit {:per-minute 30 :per-day 500 :by :scope}

   :by says who the caller is: :person (the one signed in), :scope (the
   verified scopes, for a whole shop's cap) or :client-ip (the visitor's
   address, from the trusted proxy's header, see groundwork.web). Without
   :by, it's the person, or the address when no one is signed in.

   A public endpoint or route without its own limit gets the :public one,
   per caller: reads (and GET routes) and writes (and changing routes) each
   their own. Anything else without a limit has none: permissions already
   say who may call it.

   A limit is checked after the permission (someone not allowed doesn't use
   up a quota) and before the work. A caller over it gets :too-many with
   which limit (:per-minute or :per-day) and when it resets; a plain route
   gets 429 with Retry-After. Every hit goes to :on-limit.

   Per-minute buckets live in each server's memory: fast, nothing written;
   with several servers each counts its own. Per-day caps live in Postgres,
   exact across servers and restarts, since they usually guard money."
  (:require
   [clojure.string :as str]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.time LocalDate ZoneOffset)
   (java.util.concurrent ConcurrentHashMap)))

(pg/defsql count-today "groundwork/limits/count_today.sql")
(pg/defsql delete-old-days "groundwork/limits/delete_old_days.sql")

(def ^:private ^System$Logger logger (System/getLogger "groundwork.limits"))

;; =============================================================================
;; Buckets
;; =============================================================================

(defn take-token!
  "One token from a bucket of capacity n, refilled evenly over a minute:
   nil when there was one, else the seconds until there is."
  [^ConcurrentHashMap buckets k n now-ms]
  (let [rate   (/ n 60000.0)
        result (volatile! nil)]
    (.compute buckets k
              (reify java.util.function.BiFunction
                (apply [_ _ old]
                  (let [{:keys [tokens at]} (or old {:tokens (double n) :at now-ms})
                        tokens (min (double n) (+ tokens (* rate (- now-ms at))))]
                    (if (>= tokens 1.0)
                      (do (vreset! result nil) {:tokens (dec tokens) :at now-ms})
                      (do (vreset! result (long (Math/ceil (/ (- 1.0 tokens) rate 1000.0))))
                          {:tokens tokens :at now-ms}))))))
    @result))

;; =============================================================================
;; Callers and limits
;; =============================================================================

(defn caller
  "Who a limit counts: [:person id], [:scope ids], [:client-ip address]."
  [by {:keys [person scopes client-ip]}]
  (case (or by (if person :person :client-ip))
    :person    (if person [:person (str person)] [:client-ip (str client-ip)])
    :scope     [:scope (str/join "," (sort (map str scopes)))]
    :client-ip [:client-ip (str client-ip)]))

(defn limit-of
  "The limit for a call: its own, or the :public one for a public call."
  [{:keys [settings]} {:keys [own public? read?]}]
  (or own
      (when-let [{:keys [reads-per-minute writes-per-minute]} (and public? (:public settings))]
        {:per-minute (if read? reads-per-minute writes-per-minute)})))

(defn- seconds-to-midnight [now-ms]
  (let [now      (quot now-ms 1000)
        midnight (.toEpochSecond (.atStartOfDay (.plusDays (LocalDate/now ZoneOffset/UTC) 1) ZoneOffset/UTC))]
    (max 1 (- midnight now))))

(defn take!
  "Count one call against its limit: nil when it may go on, else
   {:limit :per-minute|:per-day :retry-after-seconds n}. :id names what's
   limited (an endpoint, a route), :limit is the limit, :about says who
   asked ({:person :scopes :client-ip}) for the caller and the hook."
  [{:keys [buckets db settings]} {:keys [id limit about] :as call}]
  (when-let [{:keys [per-minute per-day by]} limit]
    (let [now    (System/currentTimeMillis)
          who    (caller by about)
          minute (when per-minute (take-token! buckets [id who] per-minute now))
          day    (when (and per-day (nil? minute))
                   (let [{:limit-count/keys [n]} (pg/row db count-today {:limit-count/limit-id (str id)
                                                                         :limit-count/caller   (str/join ":" (map name who))})]
                     (when (> n per-day) (seconds-to-midnight now))))
          hit    (cond minute {:limit :per-minute :retry-after-seconds minute}
                       day    {:limit :per-day :retry-after-seconds day})]
      (when hit
        (when-let [hook (:on-limit settings)]
          (try (hook (merge (dissoc call :limit) hit {:caller who}))
               (catch Throwable e
                 (.log logger System$Logger$Level/WARNING "The on-limit hook threw" e)))))
      hit)))

;; =============================================================================
;; The part
;; =============================================================================

(defn start
  "The limits part: the buckets, and yesterday's counts forgotten."
  [{:keys [settings db]}]
  (settings/check-part! :limits settings)
  (pg/execute! db delete-old-days)
  {:db db :settings settings :buckets (ConcurrentHashMap.)})
