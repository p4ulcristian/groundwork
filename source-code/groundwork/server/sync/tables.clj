(ns groundwork.server.sync.tables
  "Every table map says how its changes reach screens, with :sync:

     :by-access        the people its :access sentences let read the row
     :everyone         every open line
     [:silent \"why\"]   nobody, on purpose, with the reason

   A table that says nothing, or :by-access without :access, stops the
   start, every problem at once: no table is quiet by accident. A table that
   tells gets :on-change, the triggers groundwork.server.database makes,
   calling groundwork.tell_sync with its id column and the columns its
   readers name."
  (:require
   [clojure.string :as str]
   [groundwork.server.database.api :as database]))

(defn- find-problem-of-table [{:keys [table sync access]}]
  (cond
    (nil? sync)
    (str table " says nothing about sync: :sync :by-access, :everyone or [:silent \"why\"]")
    (and (= :by-access sync) (nil? access))
    (str table " has :sync :by-access but no :access sentences")
    (and (vector? sync) (= :silent (first sync)) (not (and (string? (second sync)) (seq (second sync)))))
    (str table " is [:silent …] without saying why")
    (not (or (#{:by-access :everyone} sync) (and (vector? sync) (= :silent (first sync)))))
    (str table "'s :sync is none of :by-access, :everyone, [:silent \"why\"]: " (pr-str sync))))

(defn check-sync-of-tables
  "Throws with every table's problem at once."
  [tables]
  (let [problems (keep find-problem-of-table tables)]
    (when (seq problems)
      (throw (ex-info (str "The tables' sync has problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork.server.sync/bad-sync :problems (vec problems)})))))

(defn- find-id-column [{:keys [columns]}]
  (let [primary (filter (fn [[_ _ options]] (:primary-key? options)) columns)]
    (if (= 1 (count primary)) (database/turn-key-into-column-name (ffirst primary)) "")))

(defn is-table-told? [{:keys [sync]}]
  (contains? #{:by-access :everyone} sync))

(defn put-on-change-on-tables
  "The tables, each that tells given :on-change; readers is
   {table-key [reader …]} from groundwork.server.access."
  [tables readers]
  (check-sync-of-tables tables)
  (mapv (fn [table]
          (if (is-table-told? table)
            (assoc table :on-change {:function  "groundwork.tell_sync"
                                     :arguments (into [(find-id-column table)]
                                                      (when (= :by-access (:sync table))
                                                        (distinct (keep :column (get readers (:table table))))))})
            table))
        tables))
