(ns groundwork.postgres
  "Postgres through next.jdbc and PgJDBC. SQL lives in .sql files, and a var
   points at each one.

     (pg/defsql list-recent)                 ; list_recent.sql next to this ns
     (pg/rows db list-recent {:limit 20})    ;=> [{:post/id … :post/title …}]

   For each column of a result, the key (through groundwork.names) and the
   way to read its value are chosen once; every row reuses them. Values are
   real types: UUID, java.time, BigDecimal. In json/jsonb, keys shaped
   entity__field become :entity/field; every other key stays a string.

   Params: a map becomes jsonb; (json x) makes anything jsonb; an Instant
   goes in as a timestamptz."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.names :as names]
   [groundwork.sql :as sql]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection])
  (:import
   (com.zaxxer.hikari HikariConfig HikariDataSource)
   (java.net URL)
   (java.sql Connection PreparedStatement ResultSet)
   (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
   (java.util.concurrent ConcurrentHashMap)
   (org.postgresql.util PGobject PSQLException)))

;; =============================================================================
;; Keys
;; =============================================================================

(defn- column-key
  "A result column -> its keyword; a name outside the rules (\"?column?\")
   stays a string rather than failing the query."
  [s]
  (or (names/as-key s) s))

(defn- json-key [^String s]
  (or (when (str/includes? s "__") (names/as-key s)) s))

(defn- json-key-out [k]
  (cond
    (string? k)                      k
    (and (keyword? k) (namespace k)) (or (names/as-column k) (subs (str k) 1))
    (keyword? k)                     (name k)
    :else                            (str k)))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn json-key
                       :encode-key-fn json-key-out}))

;; =============================================================================
;; Start / stop
;; =============================================================================

(def ^:private settings
  "Every setting start needs. None has a default: the app's config says all."
  [:host :port :database :user :password :pool-size :statement-timeout-ms])

(defn- check-settings [opts]
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str "pg/start needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (let [{:keys [pool-size statement-timeout-ms]} opts]
    (when-not (pos-int? pool-size)
      (throw (ex-info ":pool-size must be a positive integer"
                      {:type ::bad-setting :setting :pool-size :value pool-size})))
    (when-not (or (= :none statement-timeout-ms) (pos-int? statement-timeout-ms))
      (throw (ex-info ":statement-timeout-ms must be a positive integer or :none"
                      {:type ::bad-setting :setting :statement-timeout-ms :value statement-timeout-ms})))))

(defn start
  "A db: a HikariCP connection pool plus Groundwork's hooks. Every setting is
   required; a missing one throws.

     :host :port :database :user
     :password             nil for none
     :pool-size            a fixed number of connections, as HikariCP advises
     :statement-timeout-ms Postgres cancels a statement that runs longer; :none for no limit
     :on-transaction (fn [tx] …)   optional, runs first in every with-transaction
     :on-query       (fn [{:keys [file sql ms error]}] …)   optional, after every query"
  [{:keys [host port database user password pool-size statement-timeout-ms
           on-transaction on-query]
    :as   opts}]
  (check-settings opts)
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (connection/jdbc-url {:dbtype "postgresql"
                                                    :host   host
                                                    :port   port
                                                    :dbname database}))
                 (.setUsername user)
                 (.setMaximumPoolSize pool-size)
                 (.setPoolName (str "groundwork-" database))
                 ;; set by Postgres when a connection opens; :none is 0, so a
                 ;; limit on the database role doesn't apply behind the config's back
                 (.addDataSourceProperty "options"
                                         (str "-c statement_timeout="
                                              (if (= :none statement-timeout-ms) 0 statement-timeout-ms))))]
    (when password (.setPassword config password))
    {::datasource     (HikariDataSource. config)
     ::on-transaction on-transaction
     ::on-query       on-query}))

(defn stop
  "Close the pool's connections."
  [db]
  (.close ^HikariDataSource (::datasource db)))

;; =============================================================================
;; Queries are files
;; =============================================================================

(defrecord Query [path])

(defmethod print-method Query [q ^java.io.Writer w]
  (.write w (str "#groundwork/sql " (pr-str (:path q)))))

(defn- ns-folder
  "Classpath folder of a namespace: app.posts.db -> app/posts."
  [ns-name]
  (->> (str/split (str/replace (str ns-name) "-" "_") #"\.")
       butlast
       (str/join "/")))

(defn- leading-comment
  "The -- lines at the top of a .sql file, as one string."
  [text]
  (some->> (str/split-lines text)
           (take-while #(str/starts-with? (str/triml %) "--"))
           (map #(str/trim (subs (str/triml %) 2)))
           seq
           (str/join "\n")))

(defmacro defsql
  "Def a var for a .sql file. With no path, the file sits next to this
   namespace and is named after the var: (defsql list-recent) in app.posts.db
   is app/posts/list_recent.sql. The file's leading comment is the docstring.
   A missing file fails here, when the namespace loads."
  ([sym]
   (let [folder (ns-folder (ns-name *ns*))
         file   (str (str/replace (name sym) "-" "_") ".sql")]
     `(defsql ~sym ~(if (str/blank? folder) file (str folder "/" file)))))
  ([sym path]
   (let [url (io/resource path)]
     (when-not url
       (throw (ex-info (str "No SQL file " path " for " *ns* "/" sym)
                       {:type ::missing-file :path path})))
     `(def ~(vary-meta sym assoc :doc (leading-comment (slurp url)))
        (->Query ~path)))))

(defonce ^:private ^ConcurrentHashMap cache (ConcurrentHashMap.))

(defn- file-mtime [^URL url]
  (when (= "file" (.getProtocol url))
    (.lastModified (io/file url))))

(defn- load-query
  "The parsed statement for a query. A file on disk is re-read when it
   changes; a file inside a jar is read once."
  [{:keys [path]}]
  (let [cached (.get cache path)]
    (if (and cached (nil? (:mtime cached)))
      cached
      (let [url   (or (io/resource path)
                      (throw (ex-info (str "No SQL file " path) {:type ::missing-file :path path})))
            mtime (file-mtime url)]
        (if (and cached (= mtime (:mtime cached)))
          cached
          (let [parsed (assoc (sql/parse (slurp url)) :mtime mtime :path path)]
            (.put cache path parsed)
            parsed))))))

(defn statement
  "[sql values] a query would send, for the REPL and tests."
  [query params]
  (let [stmt (load-query query)]
    [(:sql stmt) (sql/bind stmt params (:path query))]))

;; =============================================================================
;; Values in
;; =============================================================================

(defrecord Json [value])

(defn json
  "Send x as jsonb, whatever it is: (json [1 2 3])."
  [x]
  (->Json x))

(defn- jsonb [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-value-as-string x json-mapper))))

(defn- value-in [v]
  (cond
    (instance? Json v)    (jsonb (:value v))
    (map? v)              (jsonb v)
    (instance? Instant v) (.atOffset ^Instant v ZoneOffset/UTC)
    :else                 v))

;; =============================================================================
;; Values out
;; =============================================================================

(defn- reader
  "How to read one column, chosen once from its Postgres type."
  [^String type-name]
  (case type-name
    ("json" "jsonb") (fn [^ResultSet rs i]
                       (some-> (.getString rs (int i)) (json/read-value json-mapper)))
    "timestamptz"    (fn [^ResultSet rs i] (.getObject rs (int i) OffsetDateTime))
    "timestamp"      (fn [^ResultSet rs i] (.getObject rs (int i) LocalDateTime))
    "date"           (fn [^ResultSet rs i] (.getObject rs (int i) LocalDate))
    "time"           (fn [^ResultSet rs i] (.getObject rs (int i) LocalTime))
    "timetz"         (fn [^ResultSet rs i] (.getObject rs (int i) OffsetTime))
    (if (str/starts-with? type-name "_")
      (fn [^ResultSet rs i] (some-> (.getArray rs (int i)) (#(.getArray ^java.sql.Array %)) vec))
      (fn [^ResultSet rs i] (.getObject rs (int i))))))

(defn- read-rows [^ResultSet rs]
  (let [meta    (.getMetaData rs)
        indexes (range 1 (inc (.getColumnCount meta)))
        ks      (mapv #(column-key (.getColumnLabel meta (int %))) indexes)
        readers (mapv #(reader (.getColumnTypeName meta (int %))) indexes)
        width   (count ks)]
    (loop [rows (transient [])]
      (if (.next rs)
        (recur (conj! rows (loop [i 0, row (transient {})]
                             (if (< i width)
                               (recur (inc i) (assoc! row (nth ks i) ((nth readers i) rs (inc i))))
                               (persistent! row)))))
        (persistent! rows)))))

;; =============================================================================
;; Errors
;; =============================================================================

(def ^:private error-types
  {"23505" ::unique-violation
   "23503" ::foreign-key-violation
   "23514" ::check-violation
   "23502" ::not-null-violation
   "40001" ::serialization-failure
   "40P01" ::deadlock
   "57014" ::query-canceled})

(defn- translate [^PSQLException e path]
  (let [server (.getServerErrorMessage e)
        code   (.getSQLState e)
        type   (get error-types code ::error)]
    (ex-info (str (name type) ": " (if server (.getMessage server) (.getMessage e))
                  (when path (str " (" path ")")))
             {:type       type
              :sqlstate   code
              :constraint (some-> server .getConstraint)
              :detail     (some-> server .getDetail)
              :file       path}
             e)))

;; =============================================================================
;; Running
;; =============================================================================

(defn- execute-on
  "Rows as a vector when the statement returns rows, else {:count n}."
  [^Connection conn sql values]
  (with-open [^PreparedStatement ps (jdbc/prepare conn (into [sql] (map value-in) values))]
    (if (.execute ps)
      (with-open [rs (.getResultSet ps)]
        (read-rows rs))
      {:count (.getLargeUpdateCount ps)})))

(defn- run [db query params]
  (let [{:keys [sql path] :as stmt} (load-query query)
        values   (sql/bind stmt params path)
        on-query (::on-query db)
        started  (System/nanoTime)
        ms       #(/ (- (System/nanoTime) started) 1e6)]
    (try
      (let [result (if-let [conn (::conn db)]
                     (execute-on conn sql values)
                     (with-open [conn (jdbc/get-connection (::datasource db))]
                       (execute-on conn sql values)))]
        (when on-query (on-query {:file path :sql sql :ms (ms)}))
        result)
      (catch PSQLException e
        (let [error (translate e path)]
          (when on-query (on-query {:file path :sql sql :ms (ms) :error error}))
          (throw error))))))

(defn rows
  "All rows of a query that returns rows (SELECT, or a write with RETURNING)."
  ([db query] (rows db query {}))
  ([db query params]
   (let [result (run db query params)]
     (when-not (vector? result)
       (throw (ex-info (str (:path query) " returns no rows, use execute!")
                       {:type ::no-rows :file (:path query)})))
     result)))

(defn row
  "The one row of a query, or nil. More than one row throws."
  ([db query] (row db query {}))
  ([db query params]
   (let [[first-row more] (rows db query params)]
     (when more
       (throw (ex-info (str (:path query) " returned more than one row")
                       {:type ::more-than-one-row :file (:path query)})))
     first-row)))

(defn execute!
  "Run a query for its effect. Returns the number of rows it touched."
  ([db query] (execute! db query {}))
  ([db query params]
   (let [result (run db query params)]
     (if (vector? result) (count result) (:count result)))))

;; =============================================================================
;; Transactions
;; =============================================================================

(defn transact
  "Run (f tx) in a transaction; see with-transaction."
  [db opts f]
  (if-let [outer (::tx-opts db)]
    (if (or (nil? opts) (= opts outer))
      (f db)
      (throw (ex-info "A nested with-transaction joins the outer one; its options must match"
                      {:type ::nested-options :outer outer :inner opts})))
    (let [opts (or opts {})]
      (with-open [conn (jdbc/get-connection (::datasource db))]
        (jdbc/with-transaction [conn conn {:isolation (:isolation opts)
                                           :read-only (boolean (:read-only? opts))}]
          (let [tx (assoc db ::conn conn ::tx-opts opts)]
            (when-let [hook (::on-transaction db)]
              (hook tx))
            (f tx)))))))

(defmacro with-transaction
  "Run body in one transaction, with tx bound to a db on its connection.
   Returns the body's value; any exception rolls back and is rethrown.

     (with-transaction [tx db] …)
     (with-transaction [tx db {:read-only? true :isolation :repeatable-read}] …)

   A nested with-transaction joins the outer one."
  [[tx db opts] & body]
  `(transact ~db ~opts (fn [~tx] ~@body)))
