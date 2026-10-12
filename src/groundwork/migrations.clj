(ns groundwork.migrations
  "Migrations are numbered plain SQL files:

     resources/migrations/001_create_shops.sql
     resources/migrations/002_add_shop_country.sql

   At start Groundwork takes a lock in Postgres, so two servers never migrate
   at once, and runs its own new migrations first, then the app's, in number
   order. Each file runs in its own transaction, with no time limit, and is
   remembered in groundwork.migrations with the fingerprint of its text.

   - A file that fails rolls back, and the app doesn't start.
   - A file that already ran and changed since stops the start: a fix is a
     new file.
   - A file that ran and is gone stops the start too: the database and the
     files disagree.
   - In development, a snapshot of the whole schema is written to one file
     after migrating, when pg_dump is on the PATH."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc])
  (:import
   (java.io File)
   (java.lang System$Logger System$Logger$Level)
   (java.net JarURLConnection URL)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.sql Connection)))

(pg/defsql lock "groundwork/migrator/lock.sql")
(pg/defsql unlock "groundwork/migrator/unlock.sql")
(pg/defsql applied "groundwork/migrator/applied.sql")
(pg/defsql record "groundwork/migrator/record.sql")
(pg/defsql no-time-limit "groundwork/migrator/no_time_limit.sql")
(pg/defsql ran-sql "groundwork/migrator/ran.sql")

(def sources
  "Where migrations come from, in the order they run: Groundwork's own, then
   the app's. Paths are on the classpath."
  [["groundwork" "groundwork/migrations/"]
   ["app"        "migrations/"]])

;; =============================================================================
;; Files
;; =============================================================================

(def ^:private file-name #"(\d+)_[a-z0-9_]+\.sql")

(defn- names-in
  "The file names directly inside a classpath folder, from disk or from a jar."
  [^URL url ^String path]
  (case (.getProtocol url)
    "file" (->> (.listFiles (io/file url))
                (filter #(.isFile ^File %))
                (map #(.getName ^File %)))
    "jar"  (let [conn ^JarURLConnection (.openConnection url)]
             (.setUseCaches conn false)
             (with-open [jar (.getJarFile conn)]
               (->> (enumeration-seq (.entries jar))
                    (map #(.getName ^java.util.jar.JarEntry %))
                    (filter #(and (str/starts-with? % path) (not= % path)))
                    (map #(subs % (count path)))
                    (remove #(str/includes? % "/"))
                    doall)))))

(defn- fingerprint [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn files
  "The migration files of a classpath folder, in number order. A .sql file
   whose name isn't NNN_snake_case.sql, or two files with one number, throw."
  [path]
  (if-let [url (io/resource path)]
    (let [files (->> (names-in url path)
                     (filter #(str/ends-with? % ".sql"))
                     (map (fn [name]
                            (let [[_ number] (re-matches file-name name)]
                              (when-not number
                                (throw (ex-info (str "Migration " path name " isn't named like 001_create_shops.sql")
                                                {:type ::bad-file-name :file (str path name)})))
                              (let [text (slurp (io/resource (str path name)))]
                                {:file        name
                                 :number      (parse-long number)
                                 :text        text
                                 :fingerprint (fingerprint text)}))))
                     (sort-by :number))]
      (doseq [[number same] (group-by :number files)
              :when (< 1 (count same))]
        (throw (ex-info (str "Migrations in " path " share the number " number ": "
                             (str/join ", " (map :file same)))
                        {:type ::same-number :files (mapv :file same)})))
      (vec files))
    []))

;; =============================================================================
;; Running
;; =============================================================================

(defn- execute-text! [^Connection conn ^String text]
  (with-open [statement (.createStatement conn)]
    (.execute statement text)))

(defn- check-applied!
  "Every file of a source that already ran must still be there, unchanged."
  [source files applied]
  (let [by-name (into {} (map (juxt :file identity)) files)]
    (doseq [{:migration/keys [file] ran-with :migration/fingerprint} applied]
      (let [now (by-name file)]
        (cond
          (nil? now)
          (throw (ex-info (str "Migration " source "/" file " ran, but its file is gone")
                          {:type ::missing-migration :source source :file file}))

          (not= ran-with (:fingerprint now))
          (throw (ex-info (str "Migration " source "/" file " changed after it ran. A fix is a new file.")
                          {:type ::changed-migration :source source :file file})))))))

(defn- run-file! [db ^Connection conn source {:keys [file text fingerprint]}]
  (try
    (jdbc/with-transaction [tx conn]
      (let [tx-db (assoc db ::pg/conn tx)]
        (pg/execute! tx-db no-time-limit)
        (execute-text! tx text)
        (pg/execute! tx-db record {:migration/source      source
                                   :migration/file        file
                                   :migration/fingerprint fingerprint})))
    (catch Exception e
      (throw (ex-info (str "Migration " source "/" file " failed: " (ex-message e))
                      {:type ::migration-failed :source source :file file}
                      e)))))

(defn migrate!
  "Run every new migration of every source, in order, under the lock.
   Returns the files that ran, as [source file] pairs."
  ([db] (migrate! db sources))
  ([db sources]
   (with-open [conn (jdbc/get-connection (::pg/datasource db))]
     (let [conn-db (assoc db ::pg/conn conn)]
       (pg/execute! conn-db lock)
       (try
         (execute-text! conn (slurp (io/resource "groundwork/migrator/bootstrap.sql")))
         (let [applied (group-by :migration/source (pg/rows conn-db applied))]
           (into []
                 (mapcat (fn [[source path]]
                           (let [files     (files path)
                                 ran       (applied source)
                                 ran-names (set (map :migration/file ran))]
                             (check-applied! source files ran)
                             (doall
                              (for [f files
                                    :when (not (ran-names (:file f)))]
                                (do (run-file! db conn source f)
                                    [source (:file f)]))))))
                 sources))
         (finally
           (pg/execute! conn-db unlock)))))))

(defn status
  "Where the migrations stand, for the REPL: {:ran [{:source :file :ran-at}]
   :waiting [{:source :file}] :changed [{:source :file}] :missing [{:source
   :file}]}. Changed and missing files would stop the next start."
  ([db] (status db sources))
  ([db sources]
   (let [ran (group-by :migration/source (pg/rows db ran-sql))]
     (reduce (fn [report [source path]]
               (let [files   (into {} (map (juxt :file identity)) (files path))
                     applied (ran source)
                     names   (set (map :migration/file applied))]
                 (-> report
                     (update :ran into (map (fn [{:migration/keys [file ran-at]}]
                                              {:source source :file file :ran-at ran-at}))
                             applied)
                     (update :waiting into (for [[file _] (sort-by (comp :number val) files)
                                                 :when (not (names file))]
                                             {:source source :file file}))
                     (update :changed into (for [{:migration/keys [file fingerprint]} applied
                                                 :let [now (files file)]
                                                 :when (and now (not= fingerprint (:fingerprint now)))]
                                             {:source source :file file}))
                     (update :missing into (for [{:migration/keys [file]} applied
                                                 :when (not (files file))]
                                             {:source source :file file})))))
             {:ran [] :waiting [] :changed [] :missing []}
             sources))))

;; =============================================================================
;; Snapshot
;; =============================================================================

(defn- on-path? [program]
  (some #(.canExecute (io/file % program))
        (str/split (or (System/getenv "PATH") "") (re-pattern File/pathSeparator))))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.migrations"))

(defn write-snapshot!
  "Write the whole schema, without data, owners or grants, to path with
   pg_dump. Returns true, or false when pg_dump isn't on the PATH."
  [{:keys [host port database user password]} path]
  (if-not (on-path? "pg_dump")
    (do (.log logger System$Logger$Level/WARNING
              "No schema snapshot: pg_dump isn't on the PATH")
        false)
    (let [builder (ProcessBuilder. ^java.util.List
                   ["pg_dump" "--schema-only" "--no-owner" "--no-privileges"
                                    ;; a fixed key, so the same schema writes the same file
                    "--restrict-key=groundwork"
                    "-h" (str host) "-p" (str port) "-U" (str user)
                    "-f" (str path) (str database)])]
      (when password (.put (.environment builder) "PGPASSWORD" (str password)))
      (.redirectErrorStream builder true)
      (let [process (.start builder)
            output  (slurp (.getInputStream process))]
        (when-not (zero? (.waitFor process))
          (throw (ex-info (str "pg_dump failed: " output) {:type ::snapshot-failed})))
        true))))

;; =============================================================================
;; The part
;; =============================================================================

(defn start
  "The migrations part: migrate, then in development write the snapshot.

     :schema-snapshot  the file the snapshot goes to in development, or nil for none"
  [{:keys [settings environment db db-settings]}]
  (when-not (and (map? settings) (contains? settings :schema-snapshot))
    (throw (ex-info ":migrations needs :schema-snapshot (a file, or nil for none)"
                    {:type ::missing-settings :missing [:schema-snapshot]})))
  (let [ran (migrate! db)]
    (when (and (= :development environment) (:schema-snapshot settings))
      (write-snapshot! db-settings (:schema-snapshot settings)))
    {:ran ran}))
