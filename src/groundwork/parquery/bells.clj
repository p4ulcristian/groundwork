(ns groundwork.parquery.bells
  "The bells' bookkeeping: what rings and what each read waits for.

   Every table of the app says how it rings, or the start refuses (see
   groundwork.parquery.websocket); each that rings gets a trigger
   (groundwork.ring) that announces, after a statement commits, the table,
   the scopes and people of the changed rows and their ids. A write tags
   its transaction, so its bells carry the tag, and learns which bells it
   rang (rang), which its answer carries (bell). A read notes the SQL files
   it ran, here and in the database (endpoint_files), and Postgres's plan
   of each file says which tables it reads: what its watchers wait for.

   The websocket carries the bells to the browsers; this namespace only
   keeps the books."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions.description :as description]
   [groundwork.permissions.sql :as sql]
   [groundwork.postgres :as pg]
   [groundwork.postgres.names :as names]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [next.jdbc.result-set :as rs])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection DriverManager)
   (java.util Properties)))

(pg/defsql app-tables "groundwork/parquery/bells/app_tables.sql")
(pg/defsql set-tag "groundwork/parquery/bells/set_tag.sql")
(pg/defsql rang-sql "groundwork/parquery/bells/rang.sql")
(pg/defsql remember-file "groundwork/parquery/bells/remember_file.sql")
(pg/defsql files-of-sql "groundwork/parquery/bells/files_of.sql")

(def ^:private ^System$Logger logger (System/getLogger "groundwork.parquery.bells"))

;; =============================================================================
;; Which tables ring
;; =============================================================================

(defn ringing-tables
  "Every table that rings: {table {:id col :scopes [cols] :people [cols]
   :universal? bool}}."
  [description {:keys [global personal]}]
  (merge
   (into {} (map (fn [[kind {t :table id :id}]]
                   [(name t) (assoc (description/ringing description kind) :id (names/key->column id))]))
         (:records description))
   (into {} (map (fn [t] [(name t) {:id nil :scopes [] :people [] :global? true}])) (keys global))
   (into {} (map (fn [[t col]] [(name t) {:id nil :scopes [] :people [(names/key->column col)]}])) personal)))

(def every-other-table
  "In :bells :silent: every table not named anywhere is silent, with the
   reason given. For a database the app shares with something else (tests);
   an app's own database names its tables."
  :groundwork/every-other-table)

(defn check-every-table-rings!
  "Every table of the app says how it rings: in the permission description
   (:records), or in :bells as :global, :personal or :silent. Throws naming
   every table that doesn't, a table named twice, and a table named that
   isn't there."
  [db ringing {:keys [silent]}]
  (let [quiet   (into #{} (map name) (keys (dissoc silent every-other-table)))
        rest?   (contains? silent every-other-table)
        actual  (into #{} (map :table/name) (pg/rows db app-tables))
        twice   (sort (filter quiet (keys ringing)))
        missing (sort (remove actual (into quiet (keys ringing))))
        unsaid  (when-not rest? (sort (remove (into quiet (keys ringing)) actual)))
        problem (cond
                  (seq twice)   (str "These tables ring and are :silent too: " (str/join ", " twice))
                  (seq missing) (str "These tables are named but aren't in the database: " (str/join ", " missing))
                  (seq unsaid)  (str "These tables don't say how they ring: " (str/join ", " unsaid)
                                     ". Each belongs in the permission description (:records), or in"
                                     " :parquery :bells as :global (with the reason it is nobody's), :personal or :silent (with the reason)"))]
    (when problem
      (throw (ex-info problem {:type ::tables-not-declared :twice (vec twice) :missing (vec missing)
                               :undeclared (vec unsaid)})))))

(def ^:private stale-bells
  "The app's tables that carry a ring trigger: [{:trigger/table … :trigger/name …}]."
  (str "SELECT c.relname AS trigger__table, t.tgname AS trigger__name"
       " FROM pg_catalog.pg_trigger t"
       " JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
       " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
       " WHERE n.nspname = 'public' AND NOT t.tgisinternal"
       "   AND t.tgname IN ('groundwork_ring_insert', 'groundwork_ring_update', 'groundwork_ring_delete')"))

(defn install-bells!
  "A trigger per table and kind of change, calling groundwork.ring. Safe to
   run again. A table that rang under an earlier start and is silent now
   loses its triggers: left on, it would go on ringing in a database that
   was there before, and stay quiet only in a new one."
  [db tables]
  (pg/with-transaction [tx db]
    (doseq [{trigger_table :trigger__table trigger_name :trigger__name}
            (jdbc/execute! (::pg/conn tx) [stale-bells] {:builder-fn rs/as-unqualified-lower-maps})
            :when (not (contains? tables trigger_table))]
      (jdbc/execute! (::pg/conn tx)
                     [(str "DROP TRIGGER IF EXISTS " trigger_name " ON " (sql/quote-table trigger_table))]))
    (doseq [[t {:keys [id scopes people universal?]}] tables
            [op referencing] [["INSERT" "NEW TABLE AS new_rows"]
                              ["UPDATE" "NEW TABLE AS new_rows OLD TABLE AS old_rows"]
                              ["DELETE" "OLD TABLE AS old_rows"]]]
      (jdbc/execute! (::pg/conn tx)
                     [(str "CREATE OR REPLACE TRIGGER " "groundwork_ring_" (str/lower-case op)
                           " AFTER " op " ON " (sql/quote-table t)
                           " REFERENCING " referencing " FOR EACH STATEMENT"
                           " EXECUTE FUNCTION groundwork.ring('" (or id "") "', '"
                           (str/join "," scopes) "', '" (str/join "," people) "'"
                           (when universal? ", 'u'") ")")]))))

;; =============================================================================
;; A write's bells
;; =============================================================================

(defn tag!
  "Tag this transaction: the bells it rings carry the tag, so the browser
   that wrote, which hears of them with its answer, doesn't ask twice."
  [tx tag]
  (pg/execute! tx set-tag {:tag tag}))

(defn rang
  "The bells rung in this transaction: :any, or the bells as maps, or nil."
  [tx]
  (let [text (:bell/rang (pg/row tx rang-sql))]
    (cond
      (str/blank? text) nil
      (= "any" text)    :any
      :else             (mapv wire/read-json (str/split-lines text)))))

(defn bell
  "What a write's answer knows of what it changed, or nil:
   {:tag … :rang [{:t table :s scopes :p people :i ids} …] or :any}."
  [answer]
  (::bell (meta answer)))

;; =============================================================================
;; The SQL files a read ran
;; =============================================================================

(defn collecting
  "An on-query hook that notes each SQL file a read runs, and calls the
   app's own hook too."
  [files hook]
  (fn [{:keys [file] :as q}]
    (when file (swap! files conj file))
    (when hook (hook q))))

(defn remember-files!
  "Note which SQL files a read endpoint ran, here and in the database, so
   every server knows which tables its watchers wait for."
  [{:keys [db known-files]} endpoint files]
  (doseq [file files
          :when (not (contains? (get @known-files endpoint) file))]
    (pg/execute! db remember-file {:endpoint-file/endpoint (wire/endpoint-name endpoint) :endpoint-file/file file})
    (swap! known-files update endpoint (fnil conj #{}) file)))

(defn files-of
  "The SQL files a read endpoint has run, on any server: what its watchers
   wait for. Empty when it has never run."
  [{:keys [db known-files]} endpoint]
  (or (not-empty (get @known-files endpoint))
      (let [files (into #{} (map :endpoint-file/file)
                        (pg/rows db files-of-sql {:endpoint-file/endpoint (wire/endpoint-name endpoint)}))]
        (when (seq files) (swap! known-files assoc endpoint files))
        files)))

;; =============================================================================
;; Which tables a read uses
;; =============================================================================

(defn connect
  "A connection of the bells' own, outside the pool."
  ^Connection [{:keys [host port database user password]} more]
  (let [props (doto (Properties.)
                (.setProperty "user" (str user))
                (.setProperty "ApplicationName" "groundwork-bells"))]
    (when password (.setProperty props "password" password))
    (doseq [[k v] more] (.setProperty props k v))
    (DriverManager/getConnection (connection/jdbc-url {:dbtype "postgresql" :host host :port port :dbname database})
                                 props)))

(defn- numbered
  "JDBC's ? as Postgres's $1, $2…; ?? stays a ?."
  [sql]
  (let [n (atom 0)]
    (str/replace sql #"\?\?|\?" (fn [m] (if (= "??" m) "?" (str "$" (swap! n inc)))))))

(defn- relations
  "Every table a plan reads, named as its bell names it: a table outside the
   app's own schema with its schema, \"groundwork.memberships\"."
  [plan]
  (let [found (atom #{})]
    (walk/postwalk (fn [x]
                     (when-let [table (and (map? x) (get x "Relation Name"))]
                       (let [schema (get x "Schema")]
                         (swap! found conj (if (or (nil? schema) (= "public" schema)) table (str schema "." table)))))
                     x)
                   plan)
    @found))

(defn- tables-of-file
  "The tables a SQL file reads, from Postgres's plan; nil when Postgres can't
   plan it without values."
  [{:keys [db-settings plans]} file]
  (let [sql   (pg/sql-text (pg/->Query file))
        known (get @plans file)]
    (if (and known (= sql (:sql known)))
      (:tables known)
      (let [tables (try
                     ;; the simple protocol sends $1 as text, for EXPLAIN to plan without values
                     (with-open [conn (connect db-settings {"preferQueryMode" "simple"})
                                 st   (.createStatement conn)]
                       (.execute st "SET search_path = public")
                       (with-open [rs (.executeQuery st (str "EXPLAIN (GENERIC_PLAN, VERBOSE, FORMAT JSON) " (numbered sql)))]
                         (.next rs)
                         (relations (json/read-value (.getString rs 1)))))
                     (catch Exception e
                       (.log logger System$Logger$Level/DEBUG (str "No plan for " file ": " (ex-message e)))
                       nil))]
        (swap! plans assoc file {:sql sql :tables tables})
        tables))))

(defn tables-of-endpoint
  "The tables a read endpoint uses, or nil when that isn't known (yet)."
  [{:keys [endpoints] :as part} endpoint]
  (let [files  (files-of endpoints endpoint)
        labels (map name (get-in endpoints [:endpoints endpoint :reads]))
        each   (map #(tables-of-file part %) files)]
    (cond
      (and (seq files) (every? some? each)) (into (set labels) cat each)
      ;; an endpoint with its own transactions runs no file Groundwork sees:
      ;; its :reads say it all
      (and (empty? files) (seq labels))     (set labels))))
