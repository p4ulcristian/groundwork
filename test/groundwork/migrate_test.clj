(ns groundwork.migrate-test
  "Production migrates before it starts: migrate, as the role that owns the
   tables, runs the migrations, installs what Groundwork puts into the
   database and grants the app's role; production's start, as the app's
   role, changes nothing and refuses a database migrate hasn't brought up
   to its settings. Needs the second role (see groundwork.test-support)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.core :as gw]
   [groundwork.migrations :as migrations]
   [groundwork.migrations.batch :as batch]
   [groundwork.migrations.installs :as installs]
   [groundwork.migrations.roles :as roles]
   [groundwork.permissions.install :as install]
   [groundwork.postgres :as pg]
   [groundwork.test-support :as support :refer [app-settings db-settings owner sql!]]
   [next.jdbc :as jdbc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private tables
  "DROP TABLE IF EXISTS mig_things, mig_others, mig_later;
   CREATE TABLE mig_things (mig_thing__id uuid PRIMARY KEY, mig_thing__shop_id uuid NOT NULL);
   CREATE TABLE mig_others (mig_other__id uuid PRIMARY KEY, mig_other__shop_id uuid NOT NULL)")

(use-fixtures :each
  (fn [t]
    (sql! tables)
    (try (t)
         (finally (sql! "DROP TABLE IF EXISTS mig_things, mig_others, mig_later")))))

(def ^:private settings
  (assoc app-settings
         :permissions {:scopes  {:shop {:actions {:things/see #{:read} :things/edit #{:read :update}}
                                        :roles {} :parent nil}}
                       :records {:mig-thing {:table :mig_things :id :mig-thing/id
                                             :whose {:mig-thing/shop-id :shop}}
                                 :mig-other {:table :mig_others :id :mig-other/id
                                             :whose {:mig-other/shop-id :shop}}}
                       :rules   [{:allow :things/see :on :mig-thing :to [:member :mig-thing/shop-id]}
                                 {:allow :things/see :on :mig-other :to [:member :mig-other/shop-id]}]}))

(def ^:private production (support/production settings))

(defn- error [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-owner-db [f]
  (let [db (pg/start db-settings)]
    (try (f db) (finally (pg/stop db)))))

(defn- status-of [app path]
  (.statusCode (.send (HttpClient/newHttpClient)
                      (.build (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port app) path))))
                      (HttpResponse$BodyHandlers/discarding))))

(defn- as-app! [app sql]
  (jdbc/execute! (::pg/datasource (gw/db app)) [sql]))

(deftest production-starts-after-migrate
  (testing "migrate, as the owner: the migrations, the wall, the bells, the grants"
    (is (vector? (:ran (gw/migrate! production owner))))
    (with-owner-db
      (fn [db]
        (is (= [] (roles/missing db (:user support/app-role))) "the app's role holds every grant")
        (is (= [] (roles/owned db (:user support/app-role))) "and owns nothing")
        (is (empty? (:waiting (migrations/status db))) "every migration ran")))
    (is (= 1 (count (sql! "SELECT 1 FROM pg_policy WHERE polrelid = 'mig_things'::regclass AND polname = 'groundwork_read'")))
        "the wall is up"))
  (testing "a second migrate has no migration to run and nothing to change"
    (is (= {:ran [] :changed {:wall [] :bells [] :grants []}} (gw/migrate! production owner))))
  (testing "the start, as the app's role, serves"
    (let [app (gw/start production)]
      (try
        (is (= 200 (status-of app "/health")))
        (testing "the app's role can't change the tables or the wall"
          (is (thrown? Exception (as-app! app "ALTER TABLE mig_things DISABLE ROW LEVEL SECURITY")))
          (is (thrown? Exception (as-app! app "DROP TABLE mig_things")))
          (is (thrown? Exception (as-app! app "DROP POLICY groundwork_read ON mig_things")))
          (is (thrown? Exception (as-app! app "CREATE TABLE mig_sneaky (x int)"))))
        (testing "it reads Groundwork's record of migrations, and doesn't write it"
          (is (seq (as-app! app "SELECT migration__file FROM groundwork.migrations")))
          (is (thrown? Exception (as-app! app "DELETE FROM groundwork.migrations"))))
        (finally (gw/stop app))))))

(deftest production-refuses-what-migrate-has-not-brought-up
  (gw/migrate! production owner)
  (testing "settings that make another wall"
    (is (= ::installs/not-installed
           (:type (error #(gw/start (assoc-in production [:permissions :rules]
                                              [{:allow :things/edit :on :mig-thing :to [:member :mig-thing/shop-id]}
                                               {:allow :things/see :on :mig-other :to [:member :mig-other/shop-id]}])))))))
  (testing "a table migrate never granted"
    (sql! "CREATE TABLE mig_later (mig_later__id uuid PRIMARY KEY)")
    (let [{:keys [type missing]} (error #(gw/start production))]
      (is (= ::roles/app-role-lacks type))
      (is (some #{"SELECT on public.mig_later"} missing)))
    (testing "until migrate grants it"
      (gw/migrate! production owner)
      (gw/stop (gw/start production))))
  (testing "a migration that hasn't run"
    (with-owner-db
      (fn [db]
        (is (= ::migrations/not-current
               (:type (error #(migrations/check-current! db (conj migrations/sources
                                                                  ["test" "fixtures/migrations/first/"])))))))))
  (testing "the role that owns the tables can't serve"
    (let [{:keys [type owned]} (error #(gw/start (update production :db merge owner)))]
      (is (= ::roles/app-role-owns type))
      (is (some #{"table public.mig_things"} owned)))))

(defn- one-value [sql]
  (some-> (first (sql! sql)) vals first))

(defn- oids
  "Each of the wall's and the bells' objects in the database by name, with
   its oid: a new oid is an object made again."
  []
  (into {}
        (map (juxt :what :oid))
        (sql! "SELECT format('policy %s on %s', polname, polrelid::regclass) AS what, oid::text AS oid
                 FROM pg_policy WHERE polrelid IN ('mig_things'::regclass, 'mig_others'::regclass)
               UNION ALL
               SELECT format('trigger %s on %s', tgname, tgrelid::regclass), oid::text
                 FROM pg_trigger WHERE tgrelid IN ('mig_things'::regclass, 'mig_others'::regclass)
                  AND tgname LIKE 'groundwork%'
               UNION ALL
               SELECT format('function groundwork.%s', quote_ident(proname)), oid::text
                 FROM pg_proc WHERE pronamespace = 'groundwork'::regnamespace AND proname LIKE 'may %'")))

(defn- changed [settings]
  (:changed (gw/migrate! settings owner)))

(def ^:private nothing {:wall [] :bells [] :grants []})

(deftest migrate-changes-only-the-objects-that-differ
  (gw/migrate! production owner)
  (testing "a migrate with nothing new sends nothing: every object is the one it left"
    (let [before (oids)]
      (is (= {:ran [] :changed nothing} (gw/migrate! production owner)))
      (is (= before (oids)))))
  (testing "one rule changed: only its table's objects are made again"
    (let [before (oids)
          edit   (assoc-in production [:permissions :rules 0 :allow] :things/edit)
          wall   (:wall (changed edit))]
      (is (seq wall))
      (is (every? (fn [[_ object]] (re-find #"mig_things|on mig-thing\"" object)) wall)
          "nothing of mig_others")
      (is (= (into {} (filter #(re-find #"mig_others" (key %))) before)
             (into {} (filter #(re-find #"mig_others" (key %))) (oids)))
          "mig_others' objects are the same ones")
      (is (some #{[:dropped "function groundwork.\"may things/see on mig-thing\""]} wall)
          "the function the description no longer makes is dropped")
      (is (some #{[:created "function groundwork.\"may things/edit on mig-thing\""]} wall))
      (is (= nothing (changed edit)))
      (is (seq (:wall (changed production))))
      (is (= nothing (changed production)))))
  (testing "an object changed in the database by hand is put back, and only it"
    (testing "a policy dropped"
      (sql! "DROP POLICY groundwork_read ON mig_things")
      (is (= {:wall [[:created "policy groundwork_read on mig_things"]] :bells [] :grants []} (changed production)))
      (is (contains? (oids) "policy groundwork_read on mig_things")))
    (testing "a policy edited"
      (sql! "ALTER POLICY groundwork_read ON mig_things USING (true)")
      (is (= [[:replaced "policy groundwork_read on mig_things"]] (:wall (changed production)))))
    (testing "row-level security no longer forced"
      (sql! "ALTER TABLE mig_things NO FORCE ROW LEVEL SECURITY")
      (is (= [[:replaced "row-level security on mig_things"]] (:wall (changed production))))
      (is (= true (one-value "SELECT relforcerowsecurity FROM pg_class WHERE oid = 'mig_things'::regclass"))))
    (testing "a function of the wall dropped"
      (sql! "DROP FUNCTION groundwork.\"may things/see on mig-thing\"()")
      (is (= [[:created "function groundwork.\"may things/see on mig-thing\""]] (:wall (changed production)))))
    (testing "a ring trigger dropped, or switched off"
      (sql! "DROP TRIGGER groundwork_ring_insert ON mig_things")
      (is (= {:wall [] :bells [[:created "trigger groundwork_ring_insert on mig_things"]] :grants []}
             (changed production)))
      (sql! "ALTER TABLE mig_things DISABLE TRIGGER groundwork_ring_update")
      (is (= [[:replaced "trigger groundwork_ring_update on mig_things"]] (:bells (changed production)))))
    (testing "a policy Groundwork doesn't make any more"
      (sql! "CREATE POLICY groundwork_old ON mig_things USING (true)")
      (is (= [[:dropped "policy groundwork_old on mig_things"]] (:wall (changed production))))))
  (testing "a table made again: its wall, its triggers, its grants"
    (sql! "DROP TABLE mig_others; CREATE TABLE mig_others (mig_other__id uuid PRIMARY KEY, mig_other__shop_id uuid NOT NULL)")
    (let [{:keys [wall bells grants]} (changed production)]
      (is (= 5 (count wall)) "row-level security and four policies")
      (is (every? (fn [[done object]] (and (= :created done) (re-find #"mig_others" object))) wall))
      (is (= 3 (count bells)))
      (is (every? (fn [[done _]] (= :granted done)) grants))))
  (testing "a record kind the description no longer has: its wall comes down"
    (let [fewer (-> production
                    (update-in [:permissions :records] dissoc :mig-other)
                    (update-in [:permissions :rules] pop)
                    (assoc-in [:endpoints :bells :silent :mig_others] "no longer a record"))
          {:keys [wall bells]} (changed fewer)]
      (is (= #{[:dropped "row-level security on mig_others"]
               [:dropped "policy groundwork_read on mig_others"]
               [:dropped "policy groundwork_insert on mig_others"]
               [:dropped "policy groundwork_update on mig_others"]
               [:dropped "policy groundwork_delete on mig_others"]
               [:dropped "function groundwork.\"may things/see on mig-other\""]}
             (set wall)))
      (is (= 3 (count bells)))
      (is (= false (one-value "SELECT relrowsecurity FROM pg_class WHERE oid = 'mig_others'::regclass")))
      (is (= nothing (changed fewer)))
      (is (seq (:wall (changed production))))))
  (testing "a grant revoked, or a write on Groundwork's records granted"
    (sql! (str "REVOKE SELECT ON mig_things FROM " (:user support/app-role)))
    (is (= [[:granted "SELECT on public.mig_things"]] (:grants (changed production))))
    (sql! (str "GRANT INSERT ON groundwork.installs TO " (:user support/app-role)))
    (is (= [[:revoked "INSERT on groundwork.installs"]] (:grants (changed production))))
    (with-owner-db
      (fn [db] (is (= [] (roles/extra db (:user support/app-role)))))))
  (testing "and then nothing again, and production starts"
    (is (= nothing (changed production)))
    (gw/stop (gw/start production))))

(deftest a-name-longer-than-postgres-keeps
  (testing "Postgres keeps 63 bytes of a name, cut at a character"
    (is (= "short" (installs/identifier "short")))
    (is (= 63 (count (installs/identifier (apply str (repeat 70 "a"))))))
    (is (= (str (apply str (repeat 62 "a"))) (installs/identifier (str (apply str (repeat 62 "a")) "é")))))
  (testing "a function whose name Postgres cuts is installed once, and left alone after"
    (let [long-action :things/see-every-single-thing-there-is-about-it
          settings    (-> production
                          (assoc-in [:permissions :scopes :shop :actions long-action] #{:read})
                          (update-in [:permissions :rules] conj
                                     {:allow long-action :on :mig-other :to [:member :mig-other/shop-id]}))]
      (is (some (fn [[done object]] (and (= :created done) (re-find #"see-every-single" object)))
                (:wall (changed settings))))
      (is (= nothing (changed settings)))
      (is (seq (:wall (changed production)))))))

(deftest installing-stops-at-the-first-failing-statement
  (with-owner-db
    (fn [db]
      (let [e (try (pg/with-transaction [tx db]
                     (batch/execute-all! tx ["CREATE TABLE mig_later (mig_later__id uuid PRIMARY KEY)"
                                             "CREATE POLICY groundwork_read ON mig_no_such_table USING (true)"
                                             "CREATE TABLE mig_never (x int)"]))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::batch/failed (:type (ex-data e))))
        (is (= "CREATE POLICY groundwork_read ON mig_no_such_table USING (true)" (:statement (ex-data e))))
        (is (re-find #"^Statement 2 of 3 failed: .*mig_no_such_table" (ex-message e)))
        (is (nil? (one-value "SELECT to_regclass('mig_later')::text")) "the transaction rolled back")))))

(deftest migrate-refuses
  (testing "the app's own role as the owner"
    (is (= ::roles/same-role (:type (error #(gw/migrate! production support/app-role))))))
  (testing "an app role that doesn't exist"
    (is (= ::roles/no-app-role
           (:type (error #(gw/migrate! (assoc-in production [:db :user] "groundwork_no_such_role") owner))))))
  (testing "an app role that owns the tables (here the tests' own role, checked from the second one)"
    (let [db (pg/start (merge db-settings support/app-role))]
      (try
        (is (= ::roles/app-role-owns (:type (error #(roles/check-before-migrate! db (:user owner))))))
        (finally (pg/stop db)))))
  (testing "no owner role"
    (is (= ::gw/bad-owner (:type (error #(gw/migrate! production {:password "x"})))))))

(deftest development-still-migrates-at-start
  (testing "one role: the start migrates, installs and records what it installed"
    (let [app (gw/start settings)]
      (try
        (is (nil? (installs/check! (gw/db app) :wall
                                   (install/statements (gw/db app) (:description (gw/permissions app))))))
        (finally (gw/stop app))))))
