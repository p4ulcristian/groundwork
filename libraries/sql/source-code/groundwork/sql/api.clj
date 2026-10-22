(ns groundwork.sql.api
  "Postgres through a pool, with every query in a .sql file. The api of
   groundwork.sql.

     (def find-orders-of-shop (sql/make-query-from-sql-file \"app/orders/find_orders_of_shop.sql\"))
     (sql/find-rows pool find-orders-of-shop {:order/shop-id 7})
     ;=> [{:order/id 42 :order/total 120.50M} …]

   In a .sql file a param is :name (:order/shop-id for a field of the data,
   :limit for a control of the query) and a column is named entity__field,
   which comes back as :entity/field. Values come back as real types: UUID,
   BigDecimal, java.time.

   Every function that runs a query takes a pool, or the transaction that
   run-in-transaction! hands its function: the same calls work in both."
  (:require
   [clojure.java.io :as io]
   [groundwork.sql.server-code.params :as params]
   [groundwork.sql.server-code.rows :as rows]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection])
  (:import
   (com.zaxxer.hikari HikariConfig HikariDataSource)
   (java.sql Connection PreparedStatement)))

;; -----------------------------------------------------------------------------
;; The pool

(defn- statement-timeout-as-postgres-takes-it
  "A :statement-timeout-ms as Postgres takes it: :none is 0, no limit."
  [value]
  (cond
    (= :none value)  0
    (pos-int? value) value
    :else (throw (ex-info ":statement-timeout-ms must be a positive integer or :none"
                          {:type :groundwork.sql/bad-setting :setting :statement-timeout-ms :value value}))))

(defn- tls-properties
  ;; :verified is verify-full: encrypted, and the server's certificate must
  ;; lead to a trusted one (the Java runtime's, or :tls-root-cert) and name
  ;; :host; a server without TLS is refused, never used plain
  [{:keys [tls tls-root-cert]}]
  (case tls
    :off      {"sslmode" "disable"}
    :verified (if tls-root-cert
                {"sslmode"     "verify-full"
                 "sslfactory"  "org.postgresql.ssl.LibPQFactory"
                 "sslrootcert" tls-root-cert}
                {"sslmode"     "verify-full"
                 "sslfactory"  "org.postgresql.ssl.DefaultJavaSSLFactory"})))

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
  [{:keys [host port database user password pool-size statement-timeout-ms time-zone tls-root-cert]
    :as   db-settings}]
  (when (and tls-root-cert (not (.canRead (io/file tls-root-cert))))
    (throw (ex-info (str ":tls-root-cert names no file that can be read: " tls-root-cert)
                    {:type :groundwork.sql/bad-setting :setting :tls-root-cert :value tls-root-cert})))
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (connection/jdbc-url {:dbtype "postgresql" :host host :port port :dbname database}))
                 (.setUsername user)
                 (.setMaximumPoolSize pool-size)
                 (.setPoolName (str "groundwork-" database))
                 ;; Postgres sets it when a connection opens; :none is 0, so a
                 ;; limit on the database role can't apply behind the settings' back
                 (.addDataSourceProperty "options"
                                         (str "-c statement_timeout="
                                              (statement-timeout-as-postgres-takes-it statement-timeout-ms)))
                 ;; run on each new connection: PgJDBC sends the JVM's zone when
                 ;; it connects, so the zone is set right after; the search path
                 ;; is only public, so no other schema catches an app's table
                 (.setConnectionInitSql (str "SET TIME ZONE '" time-zone "'; SET search_path = public")))]
    (when password (.setPassword config password))
    (doseq [[k v] (tls-properties db-settings)]
      (.addDataSourceProperty config k v))
    {:groundwork.sql/datasource (HikariDataSource. config)}))

(defn stop-pool!
  "Closes the pool's connections."
  [pool]
  (.close ^HikariDataSource (:groundwork.sql/datasource pool)))

;; -----------------------------------------------------------------------------
;; Queries

(defn make-query-from-sql-file
  "A query from a .sql file on the classpath, read now: a missing file fails
   when the namespace that defs the query loads. A changed file is read
   again when the def is evaluated again."
  [path]
  (let [url (or (io/resource path)
                (throw (ex-info (str "No SQL file " path) {:type :groundwork.sql/missing-file :path path})))]
    (assoc (params/turn-sql-text-into-statement (slurp url))
           :groundwork.sql/path path)))

(defn- run-on-connection
  "Rows as a vector when the statement returns rows, else {:count n}. With
   max-rows, Postgres sends at most that many rows."
  [^Connection connection query params max-rows]
  (let [path   (:groundwork.sql/path query)
        values (mapv rows/turn-param-value-into-jdbc-value (params/list-param-values query params path))]
    (with-open [^PreparedStatement statement (jdbc/prepare connection (into [(:text-with-question-marks query)] values))]
      (when max-rows (.setMaxRows statement (int max-rows)))
      (if (.execute statement)
        (with-open [rs (.getResultSet statement)]
          (rows/list-rows-in-result-set rs path))
        {:count (.getLargeUpdateCount statement)}))))

(defn- run-query [pool-or-transaction query params max-rows]
  (if-let [connection (:groundwork.sql/connection pool-or-transaction)]
    (run-on-connection connection query params max-rows)
    (with-open [connection (jdbc/get-connection (:groundwork.sql/datasource pool-or-transaction))]
      (run-on-connection connection query params max-rows))))

(defn- throw-when-no-rows [result query]
  (when-not (vector? result)
    (throw (ex-info (str (:groundwork.sql/path query) " returns no rows; use run-statement!")
                    {:type :groundwork.sql/no-rows :file (:groundwork.sql/path query)}))))

(defn find-rows
  "Every row of a query that returns rows (a SELECT, or a write with
   RETURNING): a vector, empty when none."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params nil)]
    (throw-when-no-rows result query)
    result))

(defn find-one-row
  "The one row of a query, or nil. A second row throws; Postgres sends at
   most two, so a runaway query isn't read in full first."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params 2)]
    (throw-when-no-rows result query)
    (when (second result)
      (throw (ex-info (str (:groundwork.sql/path query) " returned more than one row")
                      {:type :groundwork.sql/more-than-one-row :file (:groundwork.sql/path query)})))
    (first result)))

(defn run-statement!
  "Runs a statement for what it changes (an INSERT, UPDATE, DELETE).
   Returns how many rows it changed."
  [pool-or-transaction query params]
  (let [result (run-query pool-or-transaction query params nil)]
    (if (vector? result) (count result) (:count result))))

(defn run-in-transaction!
  "Runs (f transaction) in one transaction: everything f runs on the
   transaction is kept when f returns, and undone when f throws (the throw
   goes on). Returns what f returns. Inside a transaction, it joins that one."
  [pool-or-transaction f]
  (if (:groundwork.sql/connection pool-or-transaction)
    (f pool-or-transaction)
    (with-open [connection (jdbc/get-connection (:groundwork.sql/datasource pool-or-transaction))]
      (jdbc/with-transaction [connection connection]
        (f {:groundwork.sql/connection connection})))))
