(ns groundwork.database.api
  "Talking to Postgres, and shaping its tables. The api of groundwork.database,
   in two halves.

   QUERIES. Every query is a .sql file (or text a library makes from data):

     (def find-orders-of-shop (database/make-query-from-sql-file \"app/orders/find_orders_of_shop.sql\"))
     (database/find-rows pool find-orders-of-shop {:order/shop-id 7})
     ;=> [{:order/id 42 :order/total 120.50M} …]

   In a .sql file a param is :name and a column entity__field comes back as
   :entity/field, with real types (UUID, BigDecimal, java.time). Every
   function that runs a query takes a pool, or the transaction that
   run-in-transaction! hands its function.

   TABLES. Declared as Clojure maps, and Postgres brought to what they say:

     (def orders
       {:table   :orders
        :columns [[:order/id         :uuid         {:primary-key? true :default :random-uuid}]
                  [:order/shop-id    :uuid         {:references :shop/id}]
                  [:order/status     [:enum \"draft\" \"done\"]]
                  [:order/note       :string       {:optional? true}]
                  [:order/created-at :time/instant {:default :now}]]
        :indexes [[:order/shop-id :order/created-at]]})

     (database/migrate! pool [shops orders] changes)

   A column is [key type options]; the type is in wire's words (:uuid :int
   :decimal :double :string :boolean :time/instant :time/local-date
   [:enum …]); it is NOT NULL unless {:optional? true}; {:unique? true}
   makes a unique key; :default is
   :random-uuid, :now, a number, true/false or text; {:references :shop/id}
   points at another table's one-column primary key. A table key with a
   namespace is in that Postgres schema (folder): :groundwork/sessions is
   groundwork.sessions; one without is in public.

   Changes are data, for what a map can't say; each runs once, remembered in
   groundwork.changes_done, and a fresh database marks them all done:

     [{:id \"2026-10-23-rename-order-note\" :rename-column [:orders :order/note :order/customer-note]}
      {:id \"2026-10-24-drop-order-fax\"    :drop-column [:orders :order/fax] :why \"no faxes since 2019\"}
      {:id \"2026-10-25-rename-jobs\"       :rename-table [:jobs :coatings]}
      {:id \"2026-10-26-drop-old-things\"   :drop-table :old-things :why \"…\"}
      {:id \"2026-10-27-fill-priority\"     :run-sql-file \"app/changes/fill_priority.sql\"}]

   A plan has two parts: :instant (only Postgres's catalog changes; before
   the app starts) and :slow (reads or writes every row: indexes built
   CONCURRENTLY, constraints validated; after, in the background). A
   difference that could lose data, or that v1 can't make, is a stop.

   A table map with :gates ({:read sql :insert sql :update sql :delete sql},
   made by groundwork.access from its sentences) gets row-level security,
   on and forced, and one policy per operation; grant-tables-to-role! lets
   the app's own role, which owns nothing, use the tables through them."
  (:require
   [clojure.string :as str]
   [groundwork.database.server-code.queries.postgres :as sql]
   [groundwork.database.server-code.tables.catalog :as catalog]
   [groundwork.database.server-code.tables.desired :as desired]
   [groundwork.database.server-code.tables.names :as names]
   [groundwork.database.server-code.tables.plan :as plan]))

;; -----------------------------------------------------------------------------
;; Queries

(defn start-pool!
  "Opens a HikariCP pool of connections to Postgres. Every setting is
   required but :password (nil for none) and :tls-root-cert:

     :host :port :database :user :password
     :tls                  :verified (encrypted, certificate checked) or :off
     :tls-root-cert        with :verified: a file of certificates to trust
     :pool-size            a fixed number of connections
     :statement-timeout-ms Postgres cancels a statement that runs longer; :none for no limit
     :time-zone            the session's time zone, e.g. \"UTC\"

   Returns the pool."
  [db-settings]
  (sql/start-pool! db-settings))

(defn stop-pool!
  "Closes the pool's connections."
  [pool]
  (sql/stop-pool! pool))

(defn make-query-from-sql-file
  "A query from a .sql file on the classpath, read now: a missing file fails
   when the namespace that defs the query loads."
  [path]
  (sql/make-query-from-sql-file path))

(defn make-query-from-sql-text
  "A query from SQL text made by code, not read from a file (a table's
   statements, made from its map). The name stands where a file's path
   would, in errors."
  [query-name sql-text]
  (sql/make-query-from-sql-text query-name sql-text))

(defn find-rows
  "Every row of a query that returns rows: a vector, empty when none."
  [pool-or-transaction query params]
  (sql/find-rows pool-or-transaction query params))

(defn find-one-row
  "The one row of a query, or nil; a second row throws."
  [pool-or-transaction query params]
  (sql/find-one-row pool-or-transaction query params))

(defn run-statement!
  "Runs a statement for what it changes. Returns how many rows it changed."
  [pool-or-transaction query params]
  (sql/run-statement! pool-or-transaction query params))

(defn run-in-transaction!
  "Runs (f transaction) in one transaction: kept when f returns, undone when
   it throws. Returns what f returns. Inside a transaction, it joins that one."
  [pool-or-transaction f]
  (sql/run-in-transaction! pool-or-transaction f))

;; -----------------------------------------------------------------------------
;; Tables


(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork/database/sql_files/" file-name)))

(def ^:private create-changes-done (make-query "create_changes_done.sql"))
(def ^:private find-changes-done   (make-query "find_changes_done.sql"))
(def ^:private insert-change-done  (make-query "insert_change_done.sql"))

(defn- turn-statement-into-query [{:keys [sql why]}]
  (sql/make-query-from-sql-text why sql))

(defn- find-whether-table-has-rows [pool table-name]
  (:has-rows (sql/find-one-row pool (sql/make-query-from-sql-text
                                     (str "rows of " table-name)
                                     (str "SELECT EXISTS (SELECT 1 FROM " table-name ") AS has_rows"))
                               {})))

(defn make-plan-from-tables
  "What would make Postgres match the tables and changes, without running
   anything: {:instant [{:sql :why}] :slow [{:sql :why}] :stops [message]
   :changes-to-mark [id]}."
  [pool tables changes]
  (sql/run-statement! pool create-changes-done {})
  (let [desired (desired/turn-table-maps-into-desired tables)
        folders (set (map names/find-folder-of-table (keys desired)))]
    (plan/make-plan {:catalog      (catalog/read-catalog pool folders)
                   :desired      desired
                   :changes      changes
                   :changes-done (set (map :change-done/id (sql/find-rows pool find-changes-done {})))
                   :has-rows?    (memoize #(find-whether-table-has-rows pool %))})))

(defn run-instant-part!
  "Runs the plan's instant part and marks its changes done, in one
   transaction: all of it, or none. A plan with stops throws, running nothing."
  [pool {:keys [instant stops changes-to-mark]}]
  (when (seq stops)
    (throw (ex-info (str "The database can't be brought to the maps:\n  " (str/join "\n  " stops))
                    {:type :groundwork.database/stops :stops stops})))
  (sql/run-in-transaction! pool
    (fn [transaction]
      (doseq [statement instant]
        (sql/run-statement! transaction (turn-statement-into-query statement) {}))
      (doseq [id changes-to-mark]
        (sql/run-statement! transaction insert-change-done {:change-done/id id})))))

(defn run-slow-part!
  "Runs the plan's slow part, one statement at a time, outside a
   transaction (CONCURRENTLY needs that). A failure throws; the next plan
   finds what is left and plans it again."
  [pool {:keys [slow]}]
  (doseq [statement slow]
    (sql/run-statement! pool (turn-statement-into-query statement) {})))

(defn- print-plan [{:keys [instant slow]}]
  (doseq [[part statements] [["now" instant] ["in the background" slow]]
          {:keys [sql why]} statements]
    (println (str "groundwork.database (" part ", " why "):\n  " (str/replace sql "\n" "\n  ")))))

(defn migrate!
  "Brings Postgres to the tables, for development: plans, throws on a stop
   (nothing runs), prints the plan, runs the instant part now and the slow
   part on a background thread. Returns the plan with :slow-part, a future
   of the slow part (deref it to wait)."
  [pool tables changes]
  (let [plan (make-plan-from-tables pool tables changes)]
    (run-instant-part! pool plan)
    (print-plan plan)
    (assoc plan :slow-part (future (run-slow-part! pool plan)))))

(defn does-database-match-tables?
  "Whether Postgres has everything the tables and changes need to run: no
   instant part left, no stop, no change waiting. A slow part still to run
   is allowed."
  [pool tables changes]
  (let [{:keys [instant stops changes-to-mark]} (make-plan-from-tables pool tables changes)]
    (and (empty? instant) (empty? stops) (empty? changes-to-mark))))

(defn turn-table-into-row-shape
  "The malli shape of a table's rows, for groundwork.wire: an optional column
   may be missing or nil."
  [table]
  (desired/turn-table-into-row-shape table))

(defn turn-key-into-column-name
  "A column key's Postgres name: :order/shop-id -> \"order__shop_id\"."
  [k]
  (names/turn-key-into-column-name k))

(defn turn-table-key-into-table-name
  "A table key's Postgres name: :orders -> \"orders\", :groundwork/sessions -> \"groundwork.sessions\"."
  [k]
  (names/turn-table-key-into-table-name k))

(defn grant-tables-to-role!
  "Lets the app's own database role use the tables of these maps: their
   folders, their rows (select, insert, update, delete), and the functions
   in them. The role owns nothing, so the gates hold for it. Run by migrate
   as the owner; safe to run again."
  [pool tables role]
  (doseq [folder (set (map (comp names/find-folder-of-table names/turn-table-key-into-table-name :table) tables))]
    (sql/run-statement! pool (sql/make-query-from-sql-text
                              (str "grant " folder " to " role)
                              (str "GRANT USAGE ON SCHEMA " folder " TO " role "; "
                                   "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " folder " TO " role "; "
                                   "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " folder " TO " role))
                        {})))
