(ns groundwork.server.main.metrics
  "groundwork's own metrics, declared on the app's one meter beside the
   libraries' (each library declares its own where it is handed the
   meter):

     groundwork_stage{stage}              gauge, read: 1 for the app's stage now
                                     (starting, started, stopping), 0 for the others
     groundwork_ready_checks_total{outcome}  counter: each ready check, ready or not-ready
     groundwork_build_info{version}       gauge, read: 1, under the :version setting
     groundwork_logs_lines_total{level}      counter, read: lines written, by level
     groundwork_logs_write_failures_total    counter, read: lines that couldn't be written
     groundwork_logs_errors_kept             gauge, read: :error lines kept for the errors route

   groundwork-logs counts nothing into a meter itself (groundwork-metrics's own log is a
   groundwork-logs), so groundwork reads groundwork-logs's read-counts when the text is
   written, under groundwork-logs's names."
  (:require
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]))

(def ^:private stages #{:starting :started :stopping})

(defn- read-stage [stage]
  (into {} (for [each stages] [{:stage each} (if (= each @stage) 1 0)])))

(defn- read-lines [running-logger]
  (into {} (for [[level n] (:lines (logs/read-counts running-logger))] [{:level level} n])))

(defn declare-groundwork-metrics!
  "groundwork's metrics and groundwork-logs's, on the app's meter: the stage read from
   the health's stage atom, the version from the settings, groundwork-logs's
   counts from the running logger."
  [meter running-logger stage version]
  (metrics/declare-metrics!
   meter
   [{:name    :groundwork/stage
     :type    :gauge
     :help    "The app's stage: 1 for the one it is in (starting, started, stopping)."
     :labels  {:stage stages}
     :buckets nil
     :read    #(read-stage stage)}
    {:name    :groundwork/ready-checks-total
     :type    :counter
     :help    "Ready checks answered, by outcome."
     :labels  {:outcome #{:ready :not-ready}}
     :buckets nil
     :read    nil}
    {:name    :groundwork/build-info
     :type    :gauge
     :help    "1, under the app's version."
     :labels  {:version #{version}}
     :buckets nil
     :read    (fn [] {{:version version} 1})}
    {:name    :groundwork-logs/lines-total
     :type    :counter
     :help    "Lines the app's logger wrote, by level."
     :labels  {:level #{:debug :info :warn :error}}
     :buckets nil
     :read    #(read-lines running-logger)}
    {:name    :groundwork-logs/write-failures-total
     :type    :counter
     :help    "Lines the app's logger couldn't write."
     :labels  {}
     :buckets nil
     :read    (fn [] {{} (:write-failures (logs/read-counts running-logger))})}
    {:name    :groundwork-logs/errors-kept
     :type    :gauge
     :help    "The :error lines kept for the errors route."
     :labels  {}
     :buckets nil
     :read    (fn [] {{} (:errors-kept (logs/read-counts running-logger))})}]))

(defn count-ready-check!
  "One ready check answered, :ready or :not-ready."
  [meter outcome]
  (metrics/add-to-counter! meter :groundwork/ready-checks-total {:outcome outcome} 1))
