(ns groundwork.migrations.installs
  "What Groundwork installs in the database from an app's settings, by
   part: :wall (row-level security, its policies, the vias' functions and
   guards, the functions that narrow a list, the fill rules) and :bells
   (the ring triggers). Each part makes its objects from the settings, each
   object the statements that make it.

   An install changes only the objects that differ
   (decisions/2026-10-22-migrate-changes-only-the-objects-that-differ.md).
   It reads what the database holds of the part in one query, each object
   with a fingerprint of how Postgres prints it back, and what the last
   install recorded of each (groundwork.installed_objects: the fingerprint
   of the statements that made it, and of it as printed right after). An
   object whose statements and printed form are both the recorded ones is
   left alone. Any other is made: created when missing, replaced when it is
   there (changed by the settings, by Groundwork or by hand). An object of
   Groundwork's the settings no longer make is dropped. Nothing differs:
   no statement is sent, nothing is locked. What differs is sent together
   (groundwork.migrations.batch), in one transaction with the records.

   groundwork.installs keeps, per part, one fingerprint of all its
   statements. In development and tests the start installs. In production
   migrate installs, and the start makes the same statements from its own
   settings and compares that fingerprint: a difference (the settings or
   Groundwork changed since migrate ran) refuses the start."
  (:require
   [clojure.string :as str]
   [groundwork.migrations :as migrations]
   [groundwork.migrations.batch :as batch]
   [groundwork.postgres :as pg]))

(pg/defsql installed "groundwork/migrator/installed.sql")
(pg/defsql record-install "groundwork/migrator/record_install.sql")
(pg/defsql installed-objects "groundwork/migrator/installed_objects.sql")
(pg/defsql record-installed-objects "groundwork/migrator/record_installed_objects.sql")
(pg/defsql forget-installed-objects "groundwork/migrator/forget_installed_objects.sql")
(pg/defsql wall-objects "groundwork/migrator/wall_objects.sql")
(pg/defsql bells-objects "groundwork/migrator/bells_objects.sql")

(def ^:private what
  {:wall  "the wall (its policies, functions, guards and fill rules)"
   :bells "the bells' triggers"})

(def ^:private held-query
  {:wall  wall-objects
   :bells bells-objects})

(defn fingerprint
  "One fingerprint for a part's statements, whatever their order."
  [statements]
  (migrations/fingerprint (str/join "\n;\n" (sort statements))))

;; =============================================================================
;; Objects
;; =============================================================================

;; An object is {:kind :table :name :statements [made by] :replace [statements
;; that make it again when it is there, when not the same]}: :kind one of
;; :function :row-security :policy :trigger; :table "" for a function.

(defn- quote-id [s] (str "\"" s "\""))

(defn identifier
  "A name as Postgres keeps it: at most 63 bytes, cut at a character's
   boundary. A longer name in a statement works, cut the same way, so an
   object's name is compared cut."
  [^String s]
  (let [limit 63]
    (if (<= (count (.getBytes s "UTF-8")) limit)
      s
      (loop [n (min (count s) limit)]
        (let [cut (subs s 0 n)]
          (if (<= (count (.getBytes cut "UTF-8")) limit) cut (recur (dec n))))))))

(defn statement-object
  "The object a function's or trigger's statement makes, from the statement
   Groundwork made: a function in the schema groundwork, or a trigger on an
   app table. A function that narrows a list or answers a state's deny is
   dropped before it is made again, as its return type may change."
  [statement]
  (or (when-let [[_ n] (re-find #"^CREATE OR REPLACE FUNCTION groundwork\.\"([^\"]+)\"\(" statement)]
        {:kind       :function
         :table      ""
         :name       (identifier n)
         :statements [statement]
         :replace    (if (re-find #"^(may |denied scopes )" n)
                       [(str "DROP FUNCTION IF EXISTS groundwork." (quote-id n) "()") statement]
                       [statement])})
      (when-let [[_ n t] (re-find #"^CREATE OR REPLACE TRIGGER \"?([a-z0-9_]+)\"? (?:BEFORE|AFTER) (?:INSERT|UPDATE|DELETE) ON \"([a-z0-9_]+)\""
                                  statement)]
        {:kind :trigger :table (identifier t) :name (identifier n) :statements [statement]})
      (throw (ex-info (str "Groundwork made a statement whose object it can't name:\n" statement)
                      {:type ::unnamed-object :statement statement}))))

(defn- key-of [{:keys [kind table name]}]
  [kind table name])

(defn- label
  "An object as people say it: \"policy groundwork_read on orders\"."
  [{:keys [kind table name]}]
  (case kind
    :function     (str "function groundwork." (quote-id name))
    :row-security (str "row-level security on " table)
    (str (clojure.core/name kind) " " name " on " table)))

(def ^:private steps
  "When an object is made, and when one is dropped, in one batch: functions
   first (the policies and triggers call them), then row-level security,
   policies and triggers; functions no longer made after the policies and
   triggers that called them are gone; row-level security off last."
  {:function     [1 5]
   :row-security [2 6]
   :policy       [3 3]
   :trigger      [4 4]})

(defn- drop-statements [{:keys [kind table name signature]}]
  (case kind
    :function     [(str "DROP FUNCTION " signature)]
    :row-security [(str "ALTER TABLE " (quote-id table) " NO FORCE ROW LEVEL SECURITY")
                   (str "ALTER TABLE " (quote-id table) " DISABLE ROW LEVEL SECURITY")]
    :policy       [(str "DROP POLICY " (quote-id name) " ON " (quote-id table))]
    :trigger      [(str "DROP TRIGGER IF EXISTS " (quote-id name) " ON " (quote-id table))]))

(defn- object-fingerprint [{:keys [statements]}]
  (migrations/fingerprint (str/join "\n;\n" statements)))

(defn changes
  "What brings the objects the database holds to the ones wanted, in the
   order they run: [{:done :created|:replaced|:dropped :object …
   :statements […]}]. An object wanted is left alone when the database
   holds it as the last install recorded it, made by the same statements.
   An object held and not wanted is dropped; row-level security only when
   an install of Groundwork's switched it on (it is recorded)."
  [wanted held recorded]
  (let [held-by   (into {} (map (juxt key-of identity)) held)
        wanted-ks (set (map key-of wanted))]
    (->> (concat
          (for [o     wanted
                :let  [h (held-by (key-of o))
                       r (recorded (key-of o))]
                :when (not (and h r
                                (= (:statement r) (object-fingerprint o))
                                (= (:printed r) (:printed h))))]
            {:done       (if h :replaced :created)
             :object     o
             :statements (if h (or (:replace o) (:statements o)) (:statements o))
             :step       (first (steps (:kind o)))})
          (for [h     held
                :when (and (not (wanted-ks (key-of h)))
                           (or (not= :row-security (:kind h)) (recorded (key-of h))))]
            {:done       :dropped
             :object     h
             :statements (drop-statements h)
             :step       (second (steps (:kind h)))}))
         (sort-by :step)
         vec)))

;; =============================================================================
;; Reading and recording
;; =============================================================================

(defn held
  "The objects of a part the database holds now: [{:kind :table :name
   :signature :printed}], in one query."
  [db part]
  (mapv (fn [{:database-object/keys [kind table name signature printed]}]
          {:kind (keyword kind) :table table :name name :signature signature :printed printed})
        (pg/rows db (held-query part))))

(defn recorded
  "What the last installs recorded of a part's objects: {[kind table name]
   {:statement :printed}}."
  [db part]
  (into {}
        (map (fn [{:installed-object/keys [kind table name statement printed]}]
               [[(keyword kind) table name] {:statement statement :printed printed}]))
        (pg/rows db installed-objects {:installed-object/part (name part)})))

(defn- columns [objects ks]
  (into {} (map (fn [[param f]] [param (mapv f objects)])) ks))

(defn- record-objects!
  "In the install's transaction, after its statements: the objects made,
   as the database prints them now, remembered; the ones dropped
   forgotten. An object made that the database doesn't hold throws."
  [tx part made dropped]
  (when (seq made)
    (let [now     (into {} (map (juxt key-of :printed)) (held tx part))
          printed (fn [o] (or (now (key-of o))
                              (throw (ex-info (str "Installed " (label o) ", but the database doesn't hold it")
                                              {:type ::not-held :object (key-of o)}))))]
      (pg/execute! tx record-installed-objects
                   (merge {:installed-object/part (name part)}
                          (columns made {:kinds      (comp name :kind)
                                         :tables     :table
                                         :names      :name
                                         :statements object-fingerprint
                                         :printed    printed})))))
  (when (seq dropped)
    (pg/execute! tx forget-installed-objects
                 (merge {:installed-object/part (name part)}
                        (columns dropped {:kinds (comp name :kind) :tables :table :names :name})))))

(defn record!
  "Remember the fingerprint of all of a part's statements."
  [db part statements]
  (pg/execute! db record-install {:install/part        (name part)
                                  :install/fingerprint (fingerprint statements)}))

;; =============================================================================
;; Installing
;; =============================================================================

(defn install!
  "Bring a part's objects in the database to `objects` (see changes): the
   statements of what differs, in one transaction with the records of the
   objects made and dropped and of the part's whole fingerprint. Nothing
   differs and the fingerprint is the recorded one: nothing is written.
   Returns what changed, [[:created|:replaced|:dropped \"policy … on …\"] …],
   empty when nothing did."
  [db part objects]
  (doseq [[k same] (group-by key-of objects)
          :when (< 1 (count same))]
    (throw (ex-info (str "Two of the objects Groundwork makes from the settings are one in Postgres, "
                         (label (first same)) ": their names are the same in their first 63 bytes,"
                         " where Postgres cuts a name")
                    {:type ::same-name :object k})))
  (let [statements (mapcat :statements objects)
        todo       (changes objects (held db part) (recorded db part))
        stored     (:install/fingerprint (pg/row db installed {:install/part (name part)}))]
    (when (or (seq todo) (not= stored (fingerprint statements)))
      (pg/with-transaction [tx db]
        (batch/execute-all! tx (mapcat :statements todo))
        (record-objects! tx part
                         (map :object (remove (comp #{:dropped} :done) todo))
                         (map :object (filter (comp #{:dropped} :done) todo)))
        (record! tx part statements)))
    (mapv (fn [{:keys [done object]}] [done (label object)]) todo)))

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
