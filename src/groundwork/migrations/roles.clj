(ns groundwork.migrations.roles
  "Production's two roles (decisions/2026-10-18-production-migrates-before-it-starts.md):
   the owner, which migrate connects as, owns the tables; the app's role,
   which serves requests, owns nothing and holds only what migrate grants
   it. What it is granted, and how it is checked, is in two files:

     groundwork/migrator/grants.sql          the statements migrate runs
     groundwork/migrator/missing_grants.sql  what a role lacks of them

   USAGE on the schemas public and groundwork; SELECT, INSERT, UPDATE and
   DELETE on every table, but only SELECT on Groundwork's records of
   migrations and installs; SELECT on every view; USAGE, SELECT and UPDATE
   on every sequence; EXECUTE on every function."
  (:require
   [clojure.string :as str]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc]))

(pg/defsql current-role "groundwork/migrator/current_role.sql")
(pg/defsql role-by-name "groundwork/migrator/role.sql")
(pg/defsql owned-by "groundwork/migrator/owned_by.sql")
(pg/defsql missing-grants "groundwork/migrator/missing_grants.sql")
(pg/defsql grants "groundwork/migrator/grants.sql")

(def ^:private run-migrate
  "Production starts on a migrated database: run migrate first (groundwork.core/migrate!, see the README's Production).")

(defn- listed
  "Things in a message: the first ten, and how many more."
  [things]
  (str (str/join ", " (take 10 things))
       (when (< 10 (count things)) (str " and " (- (count things) 10) " more"))))

(defn connecting
  "The name of the role this db connects as."
  [db]
  (:role/name (pg/row db current-role)))

(defn owned
  "What a role owns of public and groundwork, itself or through a role it
   is a member of: [\"table public.orders\" …]."
  [db role]
  (mapv :owned/what (pg/rows db owned-by {:role/name role})))

(defn missing
  "What a role lacks of what migrate grants the app's role: [\"INSERT on public.orders\" …]."
  [db role]
  (mapv :missing-grant/what (pg/rows db missing-grants {:role/name role})))

(defn- owns-nothing! [db role]
  (when-let [things (seq (owned db role))]
    (throw (ex-info (str "The app's database role " role " owns " (listed things)
                         " (itself or through a role it is a member of). The app connects as a role that owns"
                         " nothing; migrate connects as the role that owns the tables. Hand them to that role,"
                         " e.g. REASSIGN OWNED BY " role " TO <the owner role>.")
                    {:type ::app-role-owns :role role :owned (vec things)}))))

(defn check-app-role!
  "Production's start: the role it connects as owns nothing of the app's or
   Groundwork's, and holds every grant migrate gives. Throws otherwise."
  [db]
  (let [role (connecting db)]
    (owns-nothing! db role)
    (when-let [lacks (seq (missing db role))]
      (throw (ex-info (str "The app's database role " role " lacks " (listed lacks) ". " run-migrate)
                      {:type ::app-role-lacks :role role :missing (vec lacks)})))))

(defn check-before-migrate!
  "migrate, before it changes anything: the app's role (:db :user) is
   another role than the owner it connects as, exists, can't pass by
   row-level security and owns nothing."
  [db role]
  (let [owner (connecting db)
        found (pg/row db role-by-name {:role/name role})]
    (cond
      (= owner role)
      (throw (ex-info (str "migrate connects as " owner ", the app's own role (:db :user). It connects as the role"
                           " that owns the tables, and the app as another that owns nothing.")
                      {:type ::same-role :role role}))

      (nil? found)
      (throw (ex-info (str "There is no database role " role " (:db :user): make it before migrating, LOGIN,"
                           " with no BYPASSRLS and no superuser.")
                      {:type ::no-app-role :role role}))

      (:role/bypasses-rls found)
      (throw (ex-info (str "The database role " role " (:db :user) passes by row-level security (it is a superuser"
                           " or has BYPASSRLS), so the wall would let every query see every scope's rows.")
                      {:type ::app-role-bypasses :role role}))

      :else
      (owns-nothing! db role))))

(defn grant!
  "Grant the app's role what it needs (grants.sql), then check it holds it:
   a grant the owner may not give (an object it neither owns nor holds WITH
   GRANT OPTION) is only a warning in Postgres, so it throws here."
  [db role]
  (pg/with-transaction [tx db]
    (doseq [{:grant/keys [statement]} (pg/rows tx grants {:role/name role})]
      (jdbc/execute! (::pg/conn tx) [statement])))
  (when-let [lacks (seq (missing db role))]
    (throw (ex-info (str "migrate couldn't grant " role " " (listed lacks)
                         ": the role it connects as neither owns them nor may grant them.")
                    {:type ::not-granted :role role :missing (vec lacks)}))))
