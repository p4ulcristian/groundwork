(ns groundwork.migrations
  "Migrations are numbered plain SQL files:

     resources/migrations/001_create_shops.sql
     resources/migrations/002_add_shop_country.sql

   Groundwork takes a lock in Postgres, so two servers never migrate at
   once, and runs its own new migrations first, then the app's, in number
   order. Each file runs in its own transaction, with no time limit, and is
   remembered in groundwork.migrations with the fingerprint of its text.
   In development and tests the start migrates; in production migrate does,
   before the start (groundwork.core/migrate!), and the start only checks
   that every migration ran (check-ready!).

   - A file that fails rolls back, and the app doesn't start.
   - A file that already ran and changed since stops the start: a fix is a
     new file.
   - A file that ran and is gone stops the start too: the database and the
     files disagree.
   - In development, a snapshot of the whole schema is written to one file
     when the whole start is done (groundwork.core calls snapshot!), when
     pg_dump is on the PATH: after everything Groundwork installs in the
     database, the wall's policies, the fill rules and the bells' triggers,
     so it shows the database as it runs and comes out the same on a
     database's first start as on every later one."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.migrations.roles :as roles]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings]
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
(pg/defsql insert-migration "groundwork/migrator/insert.sql")
(pg/defsql no-time-limit "groundwork/migrator/no_time_limit.sql")
(pg/defsql ran "groundwork/migrator/ran.sql")
(pg/defsql record-exists "groundwork/migrator/record_exists.sql")

(def default-folder
  "The classpath folder of the app's migrations when :migrations :folder
   doesn't name one."
  "migrations/")

(def sources
  "Where migrations come from, in the order they run: Groundwork's own, then
   the app's in the default folder. Paths are on the classpath."
  [["groundwork" "groundwork/migrations/"]
   ["app"        default-folder]])

(defn sources-of
  "Where an app's migrations come from: Groundwork's own, then the app's,
   from its :migrations settings' :folder (the default folder when it names
   none)."
  [migrations-settings]
  [["groundwork" "groundwork/migrations/"]
   ["app"        (or (:folder migrations-settings) default-folder)]])

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

(defn fingerprint
  "The SHA-256 of a text, in hex."
  [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn files
  "The migration files of a classpath folder, in number order. A .sql file
   whose name isn't NNN_snake_case.sql, or two files with one number, throw.
   The folder is read wherever the classpath has it: a source folder of the
   same name (groundwork/migrations/ holds code too) doesn't hide the files."
  [path]
  (if-let [urls (seq (enumeration-seq (.getResources (.getContextClassLoader (Thread/currentThread)) path)))]
    (let [files (->> urls
                     (mapcat #(names-in % path))
                     (filter #(str/ends-with? % ".sql"))
                     distinct
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
        (pg/execute! tx-db insert-migration {:migration/source      source
                                             :migration/file        file
                                             :migration/fingerprint fingerprint})))
    (catch Exception e
      (throw (ex-info (str "Migration " source "/" file " failed: " (ex-message e))
                      {:type ::migration-failed :source source :file file}
                      e)))))

(defn with-lock
  "Run (f conn-db) holding the migration lock, on a connection of its own
   (conn-db is the db on it): two servers, or two migrates, never migrate at
   once. Returns what f returns."
  [db f]
  (with-open [conn (jdbc/get-connection (::pg/datasource db))]
    (let [conn-db (assoc db ::pg/conn conn)]
      (pg/execute! conn-db lock)
      (try
        (f conn-db)
        (finally
          (pg/execute! conn-db unlock))))))

(defn run-new!
  "Run every new migration of every source, in order, on conn-db, the
   connection with-lock holds. Returns the files that ran, as [source file]
   pairs."
  ([db conn-db] (run-new! db conn-db sources))
  ([db conn-db sources]
   (let [conn (::pg/conn conn-db)]
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
             sources)))))

(defn migrate!
  "Run every new migration of every source, in order, under the lock.
   Returns the files that ran, as [source file] pairs."
  ([db] (migrate! db sources))
  ([db sources]
   (with-lock db #(run-new! db % sources))))

(defn status
  "Where the migrations stand, for the REPL and production's start: {:ran [{:source :file :ran-at}]
   :waiting [{:source :file}] :changed [{:source :file}] :missing [{:source
   :file}]}. Changed and missing files would stop the next start."
  ([db] (status db sources))
  ([db sources]
   (let [ran (group-by :migration/source (pg/rows db ran))]
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
  [{:keys [host port database user password] :as db-settings} path]
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
      (doseq [[k v] (pg/libpq-environment db-settings)]
        (.put (.environment builder) k v))
      (.redirectErrorStream builder true)
      (let [process (.start builder)
            output  (slurp (.getInputStream process))]
        (when-not (zero? (.waitFor process))
          (throw (ex-info (str "pg_dump failed: " output) {:type ::snapshot-failed})))
        true))))

;; =============================================================================
;; Production: the database is current
;; =============================================================================

(def ^:private run-migrate
  "Production starts on a migrated database: run migrate first (groundwork.core/migrate!, see the README's Production).")

(defn- names [entries]
  (str/join ", " (map #(str (:source %) "/" (:file %)) entries)))

(defn check-current!
  "Production's start migrates nothing. It checks that every migration of
   every source ran, unchanged and still there, and throws ::not-current
   (or what a changed or missing file throws at a migration) otherwise."
  ([db] (check-current! db sources))
  ([db sources]
   (let [{:keys [waiting changed missing]} (status db sources)]
     (when-let [{:keys [source file]} (first changed)]
       (throw (ex-info (str "Migration " source "/" file " changed after it ran. A fix is a new file.")
                       {:type ::changed-migration :source source :file file})))
     (when-let [{:keys [source file]} (first missing)]
       (throw (ex-info (str "Migration " source "/" file " ran, but its file is gone")
                       {:type ::missing-migration :source source :file file})))
     (when (seq waiting)
       (throw (ex-info (str (count waiting) (if (= 1 (count waiting)) " migration hasn't" " migrations haven't")
                            " run: " (names waiting) ". " run-migrate)
                       {:type ::not-current :waiting (mapv (juxt :source :file) waiting)}))))))

(defn check-ready!
  "Production's start, before any other part touches the database: the
   migrations ran (Groundwork's record of them is there); the app's role
   owns nothing and holds what migrate grants (groundwork.migrations.roles);
   every migration of `sources` ran, unchanged."
  ([db] (check-ready! db sources))
  ([db sources]
   (when-not (:migration-record/exists (pg/row db record-exists))
     (throw (ex-info (str "No migration has run in this database. " run-migrate)
                     {:type ::not-current :waiting :all})))
   (roles/check-app-role! db)
   (check-current! db sources)))

;; =============================================================================
;; The part
;; =============================================================================

(defn start
  "The migrations part. In development and tests: migrate. In production:
   migrate nothing, check the database is ready (check-ready!); migrate ran
   before the start (groundwork.core/migrate!).

     :schema-snapshot  the file the snapshot goes to in development, or nil for none;
                       written when the whole start is done, see snapshot!
     :folder           the classpath folder of the app's migrations (optional,
                       migrations/ when left out), see sources-of"
  [{:keys [settings db environment]}]
  (settings/check-part! :migrations settings)
  (let [sources (sources-of settings)]
    (if (= :production environment)
      (do (check-ready! db sources)
          {:ran []})
      {:ran (migrate! db sources)})))

(defn snapshot!
  "In development, write the schema snapshot the app's settings name. Called
   when every part has started, so the file holds what the start installed
   too. Returns true when it wrote one."
  [{:keys [environment migrations db]}]
  (boolean (when (and (= :development environment) (:schema-snapshot migrations))
             (write-snapshot! db (:schema-snapshot migrations)))))
