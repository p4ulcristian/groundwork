(ns groundwork.migrations.installs
  "What Groundwork installs in the database from an app's settings, by
   part: :wall (row-level security, its policies, the vias' functions and
   guards, the functions that narrow a list, the fill rules) and :bells
   (the ring triggers). Each part makes its statements from the settings;
   installing them records their fingerprint in groundwork.installs.

   In development and tests the start installs. In production migrate
   installs, and the start makes the same statements from its own settings
   and compares fingerprints: a difference (the settings or Groundwork
   changed since migrate ran) refuses the start."
  (:require
   [clojure.string :as str]
   [groundwork.migrations :as migrations]
   [groundwork.postgres :as pg]))

(pg/defsql installed "groundwork/migrator/installed.sql")
(pg/defsql record-install "groundwork/migrator/record_install.sql")

(def ^:private what
  {:wall  "the wall (its policies, functions, guards and fill rules)"
   :bells "the bells' triggers"})

(defn fingerprint
  "One fingerprint for a part's statements, whatever their order."
  [statements]
  (migrations/fingerprint (str/join "\n;\n" (sort statements))))

(defn record!
  "Remember that a part's statements are installed."
  [db part statements]
  (pg/execute! db record-install {:install/part        (name part)
                                  :install/fingerprint (fingerprint statements)}))

(defn check!
  "Production's start: what migrate installed for a part is what these
   statements, made from the running settings, would install. Throws
   otherwise."
  [db part statements]
  (let [found (:install/fingerprint (pg/row db installed {:install/part (name part)}))]
    (when-not (= found (fingerprint statements))
      (throw (ex-info (str "The database doesn't hold " (what part) " as this app's settings make it: "
                           (if found
                             "the settings or Groundwork changed since migrate ran."
                             "migrate never installed it.")
                           " Production starts on a migrated database: run migrate first"
                           " (groundwork.core/migrate!, see the README's Production).")
                      {:type ::not-installed :part part})))))
