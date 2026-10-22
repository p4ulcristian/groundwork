(ns groundwork.schema.server-code.catalog
  "What Postgres has, read from its catalog, in the same form desired.clj
   makes from the maps:

     {\"orders\" {:columns      [{:name \"order__id\" :type \"uuid\" :not-null? true :default \"gen_random_uuid()\"} …]
                 :primary-key  [\"order__id\"]
                 :foreign-keys {\"orders_order__shop_id_fkey\" {:column \"order__shop_id\" :foreign-table \"shops\"
                                                              :foreign-column \"shop__id\" :validated? true}}
                 :checks       {\"orders_order__status_enum\" {:column \"order__status\" :values [\"draft\" \"done\"] :validated? true}}
                 :indexes      {\"orders_order__shop_id_idx\" {:columns [\"order__shop_id\"] :valid? true}}}}

   Only what schema makes is read: checks whose names end in _enum or
   _not_null, and indexes that are neither unique nor a primary key."
  (:require
   [clojure.string :as str]
   [groundwork.sql.api :as sql]))

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork/schema/sql_files/" file-name)))

(def ^:private find-tables      (make-query "find_tables.sql"))
(def ^:private find-columns     (make-query "find_columns.sql"))
(def ^:private find-constraints (make-query "find_constraints.sql"))
(def ^:private find-indexes     (make-query "find_indexes.sql"))

(defn find-values-in-check
  "The text values a CHECK (… = ANY (ARRAY['a'::text, …])) allows, in order."
  [definition]
  (mapv #(str/replace (second %) "''" "'") (re-seq #"'((?:[^']|'')*)'::text" definition)))

(defn- add-constraint [tables {:keys [table-name constraint-name constraint-type validated definition
                                      column-names foreign-table foreign-column-names]}]
  (case constraint-type
    "p" (assoc-in tables [table-name :primary-key] column-names)
    "f" (assoc-in tables [table-name :foreign-keys constraint-name]
                  {:column         (first column-names)
                   :foreign-table  foreign-table
                   :foreign-column (first foreign-column-names)
                   :validated?     validated})
    "c" (cond
          (str/ends-with? constraint-name "_enum")
          (assoc-in tables [table-name :checks constraint-name]
                    {:column (first column-names) :values (find-values-in-check definition) :validated? validated})
          (str/ends-with? constraint-name "_not_null")
          (assoc-in tables [table-name :not-null-checks constraint-name]
                    {:column (first column-names) :validated? validated})
          :else tables)
    tables))

(defn read-catalog
  "The app's tables as Postgres has them."
  [pool]
  (let [empty-tables (into {} (map (fn [{:keys [table-name]}]
                                     [table-name {:columns [] :primary-key [] :foreign-keys {}
                                                  :checks {} :not-null-checks {} :indexes {}}]))
                           (sql/find-rows pool find-tables {}))
        with-columns (reduce (fn [tables {:keys [table-name column-name column-type not-null default-text]}]
                               (update-in tables [table-name :columns] conj
                                          {:name column-name :type column-type
                                           :not-null? not-null :default default-text}))
                             empty-tables
                             (sql/find-rows pool find-columns {}))
        with-constraints (reduce add-constraint with-columns (sql/find-rows pool find-constraints {}))]
    (reduce (fn [tables {:keys [table-name index-name valid is-unique is-primary column-names]}]
              (if (or is-unique is-primary)
                tables
                (assoc-in tables [table-name :indexes index-name] {:columns column-names :valid? valid})))
            with-constraints
            (sql/find-rows pool find-indexes {}))))
