(ns groundwork.server.main.metrics
  "groundwork's own metrics, declared on the app's one meter beside the
   libraries' (each library declares its own where it is handed the
   meter):

     groundwork_stage{stage}              gauge, read: 1 for the app's stage now
                                     (starting, started, stopping), 0 for the others
     groundwork_ready_checks_total{outcome}  counter: each ready check, ready or not-ready
     groundwork_build_info{version}       gauge, read: 1, under the :version setting
     logbook_lines_total{level}      counter, read: lines written, by level
     logbook_write_failures_total    counter, read: lines that couldn't be written
     logbook_errors_kept             gauge, read: :error lines kept for the errors route

   logbook counts nothing into a meter itself (meter's own log is a
   logbook), so groundwork reads logbook's read-counts when the text is
   written, under logbook's names."
  (:require
   [logbook.api :as logbook]
   [meter.api :as meter]))

(def ^:private stages #{:starting :started :stopping})

(defn- read-stage [stage]
  (into {} (for [each stages] [{:stage each} (if (= each @stage) 1 0)])))

(defn- read-lines [running-logbook]
  (into {} (for [[level n] (:lines (logbook/read-counts running-logbook))] [{:level level} n])))

(defn declare-groundwork-metrics!
  "groundwork's metrics and logbook's, on the app's meter: the stage read from
   the health's stage atom, the version from the settings, logbook's
   counts from the running logbook."
  [meter running-logbook stage version]
  (meter/declare-metrics!
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
    {:name    :logbook/lines-total
     :type    :counter
     :help    "Lines the app's logbook wrote, by level."
     :labels  {:level #{:debug :info :warn :error}}
     :buckets nil
     :read    #(read-lines running-logbook)}
    {:name    :logbook/write-failures-total
     :type    :counter
     :help    "Lines the app's logbook couldn't write."
     :labels  {}
     :buckets nil
     :read    (fn [] {{} (:write-failures (logbook/read-counts running-logbook))})}
    {:name    :logbook/errors-kept
     :type    :gauge
     :help    "The :error lines kept for the errors route."
     :labels  {}
     :buckets nil
     :read    (fn [] {{} (:errors-kept (logbook/read-counts running-logbook))})}]))

(defn count-ready-check!
  "One ready check answered, :ready or :not-ready."
  [meter outcome]
  (meter/add-to-counter! meter :groundwork/ready-checks-total {:outcome outcome} 1))
