(ns groundwork.permissions.install
  "The description against the database, at start: the role the app
   connects as, the tables and columns it names, each record's scope
   columns, the links of records under records, the vias' indexes. Then
   the wall (row-level security, a policy per table and command), the vias'
   functions and guards, the functions that narrow a list by an action, and
   the fill rules. Safe to run again at every start."
  (:require
   [clojure.string :as str]
   [groundwork.permissions :as-alias permissions]
   [groundwork.permissions.description :as description]
   [groundwork.permissions.sql :as sql]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc]))

(pg/defsql columns-of "groundwork/permissions/install/columns_of.sql")
(pg/defsql indexes-of "groundwork/permissions/install/indexes_of.sql")
(pg/defsql groundwork-policies "groundwork/permissions/install/groundwork_policies.sql")
(pg/defsql groundwork-guards "groundwork/permissions/install/groundwork_guards.sql")
(pg/defsql groundwork-functions "groundwork/permissions/install/groundwork_functions.sql")
(pg/defsql links-between "groundwork/permissions/install/links_between.sql")
(pg/defsql connecting-role "groundwork/permissions/install/connecting_role.sql")

(defn- bad [message data sql]
  (throw (ex-info (str message (when sql (str "\nIn a migration:\n" sql)))
                  (assoc data :type ::permissions/bad-description :sql sql))))

(defn- link-sql
  "The SQL that adds a parent link for one carried column: the parent's
   unique pair, if it has none, and the child's foreign key."
  [{t :table :keys [parent-column parent-table parent-id]} {c :column p :parent-column}]
  (str "ALTER TABLE " parent-table " ADD UNIQUE (" parent-id ", " p ");  -- if it has none\n"
       "ALTER TABLE " t " ADD FOREIGN KEY (" parent-column ", " c ")\n"
       "  REFERENCES " parent-table " (" parent-id ", " p ") ON UPDATE CASCADE;"))

(defn- not-null-of [db t]
  (into {} (map (juxt :column/name :column/not-null)) (pg/rows db columns-of {:table t})))

(defn- check-scope-columns!
  "Every record table has its scope columns. A scope column a record
   carries from its parent, and the parent's column it is carried from, are
   NOT NULL: their matching link holds only then. A record on its own, and
   a column that is a record's own beside what it carries, may be empty:
   the row is in no scope through it yet, and only its people and the
   system see it, or it is universal when a sentence says so."
  [db description]
  (let [links      (description/parent-links description)
        carried    (into {} (for [{:keys [table carried] :as link} links
                                  {:keys [column person?] :as c} carried
                                  :when (not person?)]
                              [[table column] [link c]]))
        handed     (into #{} (for [{:keys [parent-table carried]} links
                                   {:keys [parent-column person?]} carried
                                   :when (not person?)]
                               [parent-table parent-column]))]
    (doseq [{:keys [table column]} (description/record-scope-columns description)]
      (let [have     (not-null-of db table)
            [link c] (carried [table column])]
        (cond
          (not (contains? have column))
          (bad (str "Table " table " needs its own scope column " column)
               {:table table :column column}
               (str "ALTER TABLE " table " ADD COLUMN " column " uuid;\n"
                    (if link
                      (str "UPDATE " table " c SET " column " = p." (:parent-column c)
                           " FROM " (:parent-table link) " p WHERE p." (:parent-id link)
                           " = c." (:parent-column link) ";\n"
                           "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;")
                      (str "-- fill " column " for the rows already there\n"
                           "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;"))))

          (and (or link (handed [table column])) (not (have column)))
          (bad (str "Scope column " table "." column " must be NOT NULL: records hang under " table
                    ", or it hangs under another")
               {:table table :column column}
               (str "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;")))))
    (doseq [{:keys [table column]} (description/none-columns description)
            :when (get (not-null-of db table) column)]
      (bad (str "A sentence says [:none …] of " table "." column ", which is NOT NULL: no row of it can belong"
                " to nobody")
           {:table table :column column}
           (str "ALTER TABLE " table " ALTER COLUMN " column " DROP NOT NULL;")))))

(defn- check-parent-links!
  "Every record under another record is tied to it by a matching link, for
   each column it carries: a foreign key that holds its (parent column,
   column) to the parent's (id, column) and cascades. A key with more
   columns in it counts only while none of them can be empty, since one
   empty column switches a whole key off; a column that may be empty (a
   person) has a key of its own two."
  [db description]
  (doseq [{t :table :keys [parent-column parent-table parent-id carried] :as link} (description/parent-links description)
          :let [not-null (not-null-of db t)
                keys     (pg/rows db links-between {:child t :parent parent-table})]
          {c :column p :parent-column :as one} carried]
    (let [wanted  #{[parent-column parent-id] [c p]}
          pairs   (fn [k] (set (map vector (:link/child-columns k) (:link/parent-columns k))))
          holding (filter (fn [k] (let [ps (pairs k)]
                                    (and (every? ps wanted)
                                         (or (= wanted ps) (every? not-null (:link/child-columns k))))))
                          keys)
          found   (or (first (filter :link/cascades holding)) (first holding))]
      (cond
        (nil? found)
        (bad (str "Table " t " needs a link to " parent-table " that keeps its " c " its parent's: "
                  "a foreign key (" parent-column ", " c ") → " parent-table
                  " (" parent-id ", " p ") ON UPDATE CASCADE")
             {:table t :parent parent-table :column c}
             (link-sql link one))

        (not (:link/cascades found))
        (bad (str "The link from " t " to " parent-table " must be ON UPDATE CASCADE, so "
                  t " follows its parent when the parent moves to another scope")
             {:table t :parent parent-table :column c}
             (str "-- drop the existing foreign key (" parent-column ", " c "), then:\n"
                  (link-sql link one)))))))

(defn- implied-by-match?
  "Whether a partial index's predicate (as Postgres prints it) holds for
   every row a lookup by these columns finds: it says only that some of
   them are not empty, as an equality match already does."
  [predicate columns]
  (let [parts (-> predicate
                  (str/replace #"[()]" "")
                  (str/split #"(?i)\s+AND\s+"))]
    (every? (fn [part]
              (when-let [[_ c] (re-matches #"(?i)\s*\"?([a-z_][a-z0-9_]*)\"?\s+IS\s+NOT\s+NULL\s*" part)]
                (contains? columns c)))
            parts)))

(defn- check-via-indexes!
  "A via looks rows up by what it matches, on both sides, and finds the
   other rows by their who column: each needs an index that starts with
   those columns, or the lookup reads the whole table on every check. A
   partial index counts when it leaves out only rows with those columns
   empty (WHERE col IS NOT NULL): no lookup by them finds such a row."
  [db description]
  (doseq [{t :table :keys [columns why]} (description/via-indexes description)
          :let [wanted  (set columns)
                indexes (keep (fn [{:index/keys [columns predicate]}]
                                ;; a partial index counts when all it leaves out is a row
                                ;; whose matched column is empty, which a match never finds
                                (when (or (nil? predicate) (implied-by-match? predicate wanted)) columns))
                              (pg/rows db indexes-of {:table t}))]
          :when (not-any? #(and (<= (count wanted) (count %)) (= wanted (set (take (count wanted) %)))) indexes)]
    (bad (str "Table " t " needs an index on " (str/join ", " columns) ": " why)
         {:table t :columns columns}
         (str "CREATE INDEX ON " t " (" (str/join ", " columns) ");"))))

(defn role-problem
  "Why the role the app connects as can't carry the wall, or nil: a superuser
   or a role with BYPASSRLS passes by every row-level policy, so every query
   would see every scope's rows, with no error anywhere. A hosted database
   often makes its owner such a role."
  [{:role/keys [name bypasses-rls]}]
  (when bypasses-rls
    (str "The database role " name " passes by row-level security (it is a superuser or has BYPASSRLS),"
         " so the wall would let every query see every scope's rows. Connect as a role without it.")))

(defn check-database!
  "The role the app connects as must not pass by row-level security; every
   table and column the description names must exist; every record table
   names its scope; every record under another is tied to it; every via
   can find its rows by an index."
  [db description]
  (when-let [problem (role-problem (pg/row db connecting-role {}))]
    (throw (ex-info problem {:type ::permissions/bad-database})))
  (doseq [t (keys (description/columns-needed description))]
    (when (empty? (pg/rows db columns-of {:table t}))
      (throw (ex-info (str "Permissions name a table " t " that doesn't exist")
                      {:type ::permissions/bad-description :table t}))))
  (check-scope-columns! db description)
  (doseq [[t needed] (description/columns-needed description)]
    (let [have (set (map :column/name (pg/rows db columns-of {:table t})))]
      (doseq [c needed
              :when (not (have c))]
        (throw (ex-info (str "Permissions name a column " t "." c " that doesn't exist")
                        {:type ::permissions/bad-description :table t :column c})))))
  (check-parent-links! db description)
  (check-via-indexes! db description))

(defn- exec! [tx sql]
  (jdbc/execute! (::pg/conn tx) [sql]))

(defn install-wall!
  "Row-level security on every record kind's table, a policy per command
   made from the sentences (sql/policies). Forced, so the tables' owner
   is held to it too. The vias' functions first, which the policies call;
   then the guards on the rows the vias look for. Run at start; safe to run
   again."
  [{:keys [db description]}]
  (pg/with-transaction [tx db]
    (doseq [via (description/vias description)]
      (let [types (into {} (map (juxt :column/name :column/type)) (pg/rows tx columns-of {:table (:table via)}))]
        (exec! tx (sql/via-sql-statement via types))))
    (doseq [[kind {t :table}] (:records description)
            :let [table                                 (sql/quote-table t)
                  {:keys [select insert update delete]} (sql/policies description kind)]]
      (exec! tx (str "ALTER TABLE " table " ENABLE ROW LEVEL SECURITY"))
      (exec! tx (str "ALTER TABLE " table " FORCE ROW LEVEL SECURITY"))
      (doseq [{:policy/keys [name]} (pg/rows tx groundwork-policies {:table (clojure.core/name t)})]
        (exec! tx (str "DROP POLICY \"" name "\" ON " table)))
      (exec! tx (str "CREATE POLICY groundwork_read ON " table " FOR SELECT USING (" select ")"))
      (exec! tx (str "CREATE POLICY groundwork_insert ON " table " FOR INSERT WITH CHECK (" insert ")"))
      (exec! tx (str "CREATE POLICY groundwork_update ON " table " FOR UPDATE USING (" (first update)
                     ") WITH CHECK (" (second update) ")"))
      (exec! tx (str "CREATE POLICY groundwork_delete ON " table " FOR DELETE USING (" delete ")")))
    (let [guarded (set (map #(name (get-in description [:records % :table])) (description/guarded-kinds description)))]
      ;; a guard from an earlier start on a table no via looks into any more goes
      (doseq [{:guard/keys [table name]} (pg/rows tx groundwork-guards {})
              :when (not (guarded table))]
        (exec! tx (str "DROP TRIGGER IF EXISTS \"" name "\" ON \"" table "\"")))
      (doseq [kind      (description/guarded-kinds description)
              statement (sql/guard-sql description kind)]
        (exec! tx statement)))
    ;; the functions a query file narrows a list with, one per action and
    ;; kind, and the state denies they leave out
    (let [denies (filter :scope (description/denies description))
          mays   (distinct (map (juxt :action :kind) (description/sentences description)))]
      ;; made afresh each start: an id column's type may have changed
      (doseq [{:function/keys [name]} (pg/rows tx groundwork-functions {})]
        (exec! tx (str "DROP FUNCTION IF EXISTS groundwork.\"" name "\"()")))
      (doseq [d denies]
        (exec! tx (sql/deny-sql-statement description d)))
      (doseq [[action kind] mays
              :let [{t :table id :id} (get-in description [:records kind])
                    id-type (some #(when (= (description/column id) (:column/name %)) (:column/type %))
                                  (pg/rows tx columns-of {:table (clojure.core/name t)}))]]
        (exec! tx (sql/may-sql-statement description action kind id-type))))))

(defn install-fill!
  "On every record table under another record, a rule that fills a row's
   scope from its parent when it's inserted without one. Run at start; safe
   to run again."
  [{:keys [db description]}]
  (pg/with-transaction [tx db]
    (doseq [link      (description/parent-links description)
            statement (sql/fill-sql link)]
      (exec! tx statement))))
