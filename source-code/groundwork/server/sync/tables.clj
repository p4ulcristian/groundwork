(ns groundwork.server.sync.tables
  "Every table map says whether its changes reach screens, with :sync:

     :tell             every open line hears that the table changed
     [:silent \"why\"]   nobody, on purpose, with the reason

   A table that says nothing stops the start, every problem at once: no
   table is quiet by accident. A table that tells gets :on-change, the
   triggers cellar makes, calling groundwork.tell_sync."
  (:require
   [clojure.string :as str]))

(defn- is-silent? [sync]
  (and (vector? sync) (= :silent (first sync))))

(defn- find-problem-of-table [{:keys [table sync]}]
  (cond
    (nil? sync)
    (str table " says nothing about sync: :sync :tell or [:silent \"why\"]")
    (and (is-silent? sync) (not (and (string? (second sync)) (seq (second sync)))))
    (str table " is [:silent …] without saying why")
    (not (or (= :tell sync) (is-silent? sync)))
    (str table "'s :sync is neither :tell nor [:silent \"why\"]: " (pr-str sync))))

(defn check-sync-of-tables
  "Throws with every table's problem at once."
  [tables]
  (let [problems (keep find-problem-of-table tables)]
    (when (seq problems)
      (throw (ex-info (str "The tables' sync has problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork.server.sync/bad-sync :problems (vec problems)})))))

(defn put-on-change-on-tables
  "The tables, checked, each that tells given :on-change."
  [tables]
  (check-sync-of-tables tables)
  (mapv (fn [table]
          (if (= :tell (:sync table))
            (assoc table :on-change {:function "groundwork.tell_sync" :arguments []})
            table))
        tables))
