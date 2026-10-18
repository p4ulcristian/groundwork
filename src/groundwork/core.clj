(ns groundwork.core
  "Starting and stopping an app, and migrating its database.

     (def app (gw/start settings))
     (gw/stop app)

     (gw/migrate! settings {:user \"app_owner\" :password …})   ; production, before the start

   In development and tests the start migrates and installs what Groundwork
   puts into the database. In production migrate does, as the role that owns
   the tables, and the start only checks the database is current; see
   migrate!.

   The settings map holds what is the app's own, one key per part; every key
   but :styles is required and an unknown key throws, so a typo can't go unnoticed. Every
   problem is reported at once, with its place (see groundwork.settings, and
   docs/example-settings.edn for every key with an example):

     :environment  :development, :test or :production
     :db           the database, see groundwork.postgres/start
     :migrations   see groundwork.migrations/start
     :page         see groundwork.web.page
     :styles       the app's stylesheets, or left out; see groundwork.build.styles
     :web          the app's routes and how requests are handled; see groundwork.web
     :server       see groundwork.web.server
     :sessions     see groundwork.sessions
     :permissions  the app's permission description, see groundwork.permissions.description
     :limits       how often a caller may ask, see groundwork.limits
     :jobs         background jobs and schedules, or nil; see groundwork.jobs
     :endpoints    the app's endpoints and how they're asked, see groundwork.endpoints

   Inside, each part is an Integrant key and Integrant starts them in order,
   hands them to each other and stops them in reverse. That wiring is
   Groundwork's own: an app's settings don't name parts or replace them."
  (:require
   [clojure.string :as str]
   [groundwork.endpoints :as endpoints]
   [groundwork.endpoints.bells :as bells]
   [groundwork.jobs :as jobs]
   [groundwork.limits :as limits]
   [groundwork.migrations :as migrations]
   [groundwork.migrations.roles :as roles]
   [groundwork.web.page :as page]
   [groundwork.endpoints.route :as route]
   [groundwork.permissions :as permissions]
   [groundwork.postgres :as pg]
   [groundwork.web.server :as server]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [groundwork.web :as web]
   [integrant.core :as ig]))

;; =============================================================================
;; Parts
;; =============================================================================

(def ^:private parts
  "Every part, in the order it starts: the settings key it reads, the parts it
   needs, and how it starts and stops. A part's start gets
   {:settings … :environment … :app-settings … <needed part> <its value> …},
   a needed part under its name (:db for :groundwork/db)."
  (array-map
   :groundwork/db         {:settings :db
                           :needs    []
                           :start    (fn [{:keys [settings]}] (pg/start settings))
                           :stop     pg/stop}
   :groundwork/migrations {:settings :migrations
                           :needs    [:groundwork/db]
                           :start    (fn [{:keys [app-settings] :as config}]
                                       (migrations/start (assoc config :db-settings (:db app-settings))))}
   :groundwork/permissions {:settings :permissions
                            :needs    [:groundwork/db :groundwork/migrations]
                            :start    permissions/start}
   ;; sign-in moves a visitor's records to the person: the tables with a
   ;; visitor column are the permission description's
   :groundwork/sessions   {:settings :sessions
                           :needs    [:groundwork/db :groundwork/migrations :groundwork/permissions]
                           :start    sessions/start}
   :groundwork/limits     {:settings :limits
                           :needs    [:groundwork/db :groundwork/migrations]
                           :start    limits/start}
   :groundwork/jobs       {:settings :jobs
                           :needs    [:groundwork/db :groundwork/migrations :groundwork/permissions]
                           :start    jobs/start
                           :stop     jobs/stop}
   :groundwork/endpoints  {:settings :endpoints
                           :needs    [:groundwork/db :groundwork/migrations :groundwork/permissions
                                      :groundwork/limits :groundwork/jobs]
                           :start    endpoints/start}
   :groundwork/endpoints-transport {:settings :endpoints
                                    :needs    [:groundwork/db :groundwork/endpoints :groundwork/sessions]
                                    :start    route/start
                                    :stop     route/stop}
   :groundwork/page       {:settings :page
                           :needs    []
                           :start    page/start}
   :groundwork/router     {:settings :web
                           :needs    [:groundwork/migrations :groundwork/sessions :groundwork/permissions
                                      :groundwork/page :groundwork/endpoints-transport :groundwork/limits]
                           :start    web/start}
   :groundwork/server     {:settings :server
                           :needs    [:groundwork/router]
                           :start    server/start
                           :stop     server/stop}))

(doseq [k (keys parts)]
  (derive k ::part))

(defmethod ig/init-key ::part [k config]
  (if (contains? config ::fake)
    (::fake config)
    ((:start (parts k)) config)))

(defmethod ig/halt-key! ::part [k value]
  (when-let [stop (:stop (parts k))]
    (stop value)))

;; =============================================================================
;; Settings
;; =============================================================================

(defn- config
  "The Integrant config for an app's settings."
  [settings]
  (into {}
        (map (fn [[k {setting :settings needs :needs}]]
               [k (into {:settings     (get settings setting)
                         :environment  (:environment settings)
                         :app-settings settings}
                        (map (fn [need] [(keyword (name need)) (ig/ref need)]))
                        needs)]))
        parts))

;; =============================================================================
;; Start / stop
;; =============================================================================

(defonce ^:private current-app (atom nil))

(defn current
  "The app started last in this JVM and still running, for the REPL."
  []
  @current-app)

(defn- halt-parts!
  "Stop the running parts of a system in reverse start order, all of them
   even when one throws; the first error is thrown after the last part.

   The order is the parts list's own, not Integrant's halt!, which works the
   order out again by searching every part's settings for references: an
   app's whole settings (its routes, endpoints and their schemas) under every
   part, a million values or more, took a large app over ten seconds to
   stop."
  [system skip]
  (let [failure (reduce (fn [failure k]
                          (if (and (contains? system k) (not (contains? skip k)))
                            (try (ig/halt-key! k (get system k)) failure
                                 (catch Throwable t (or failure t)))
                            failure))
                        nil
                        (reverse (keys parts)))]
    (when failure (throw failure))))

(defn- init
  "Start the parts. When one refuses, the parts started before it are
   stopped again and the part's own error is thrown, not Integrant's around
   it: the message says what to change."
  [config]
  (try
    (ig/init config)
    (catch clojure.lang.ExceptionInfo e
      (when-let [system (:system (ex-data e))]
        (try (halt-parts! system #{}) (catch Throwable _ nil)))
      (throw (or (ex-cause e) e)))))

(defn- snapshot!
  "The schema snapshot, when the start is done: everything Groundwork
   installs in the database is in it. A snapshot that can't be written stops
   the app again and throws."
  [settings system fakes]
  (when-not (some #{:groundwork/db :groundwork/migrations} fakes)
    (try (migrations/snapshot! settings)
         (catch Throwable e
           (try (halt-parts! system (set fakes)) (catch Throwable _ nil))
           (throw e)))))

(defn start
  "Start an app from its settings. Returns the running app; give it to stop."
  [settings]
  (settings/check! settings)
  (let [system (init (config settings))]
    (snapshot! settings system #{})
    (reset! current-app {::system system})))

(defn start-with
  "Start an app with some parts replaced by fakes, for tests:

     (start-with settings {:groundwork/server nil})

   A fake is used as the part's value as it is, and it isn't stopped."
  [settings fakes]
  (settings/check! settings)
  (let [config (reduce-kv (fn [config k fake]
                            (when-not (contains? parts k)
                              (throw (ex-info (str "No part " k) {:type ::unknown-part :part k})))
                            (assoc config k {::fake fake}))
                          (config settings)
                          fakes)
        system (init config)]
    (snapshot! settings system (set (keys fakes)))
    (reset! current-app {::system system
                         ::fakes  (set (keys fakes))})))

(defn- owner-problems [{:keys [user password] :as owner}]
  (cond
    (not (map? owner))
    "must be a map {:user … :password …}"

    (not (and (string? user) (not (str/blank? user))))
    "needs :user, the name of the role that owns the tables"

    (not (or (nil? password) (string? password)))
    ":password must be text, or nil for none"))

(defn migrate!
  "Bring the database up to the app's settings, before a production start.
   `owner` is the role that owns the tables, {:user … :password …}
   (:password nil for none: a password file or trust); the rest of the
   connection, its TLS too, is the settings' :db. The app's own role is
   :db :user, and it must be another.

   Under the migration lock, so two never run at once:
   1. the app's role is checked: it exists, owns nothing of the app's or
      Groundwork's and can't pass by row-level security;
   2. Groundwork's new migrations run, then the app's;
   3. everything Groundwork puts into the database from the settings is
      installed and its fingerprint recorded: the wall (row-level security,
      its policies, the vias' functions and guards, the functions that
      narrow a list), the fill rules, the bells' triggers;
   4. the app's role is granted what it needs, and checked to hold it
      (groundwork.migrations.roles).

   Returns {:ran [[source file] …]}. The app's own hooks (:on-transaction,
   :on-query) don't run here."
  [settings owner]
  (settings/check! settings)
  (when-let [problem (owner-problems owner)]
    (throw (ex-info (str "migrate's owner role " problem) {:type ::bad-owner})))
  (let [app-role (get-in settings [:db :user])
        bells    (get-in settings [:endpoints :bells])
        db       (pg/start (-> (:db settings)
                               (dissoc :on-transaction :on-query)
                               (assoc :user (:user owner) :password (:password owner)
                                      :pool-size 2 :statement-timeout-ms :none)))]
    (try
      (roles/check-before-migrate! db app-role)
      (migrations/with-lock db
        (fn [conn-db]
          (let [ran    (migrations/run-new! db conn-db)
                perms  (permissions/install! db (:permissions settings))
                tables (bells/ringing-tables (:description perms) bells)]
            (bells/check-every-table-rings! db tables bells)
            (bells/install-bells! db tables)
            (roles/grant! db app-role)
            {:ran ran})))
      (finally
        (pg/stop db)))))

(defn migrate
  "migrate! from the command line, with the Clojure CLI:

     clojure -X:migrate     ; {:exec-fn groundwork.core/migrate :exec-args {:settings app.settings/production}}
     clojure -X groundwork.core/migrate :settings app.settings/production

   :settings names a var holding the app's settings, or a function of no
   arguments that returns them. The owner role comes from the environment
   of this command alone, never from the running app's: GROUNDWORK_OWNER_USER,
   and GROUNDWORK_OWNER_PASSWORD (unset for none). Prints the migrations
   that ran."
  [{:keys [settings]}]
  (let [v (when (qualified-symbol? settings) (requiring-resolve settings))
        _ (when-not v
            (throw (ex-info (str ":settings must name the var holding the app's settings, like app.settings/production; got "
                                 (pr-str settings))
                            {:type ::bad-settings-var :settings settings})))
        s (if (fn? @v) (@v) @v)
        {:keys [ran]} (migrate! s {:user     (System/getenv "GROUNDWORK_OWNER_USER")
                                   :password (System/getenv "GROUNDWORK_OWNER_PASSWORD")})]
    (println (str "Migrated: " (count ran) (if (= 1 (count ran)) " migration" " migrations") " ran"
                  (when (seq ran) (str ": " (str/join ", " (map (fn [[source file]] (str source "/" file)) ran))))
                  "; the wall, the fill rules and the bells' triggers installed; "
                  (get-in s [:db :user]) " granted."))))

(defn stop
  "Stop a running app, its parts in reverse order."
  [app]
  (let [system (::system app)]
    (swap! current-app #(when-not (identical? (::system %) system) %))
    (halt-parts! system (::fakes app #{}))))

(defn part
  "A running part's value, for the REPL and tests: (part app :groundwork/db)."
  [app k]
  (get (::system app) k))

(defn db
  "The app's db, for groundwork.postgres."
  [app]
  (part app :groundwork/db))

(defn permissions
  "The app's permissions part, for groundwork.permissions."
  [app]
  (part app :groundwork/permissions))

(defn sessions
  "The app's sessions part, for groundwork.sessions (sign-out-everywhere!,
   on-request)."
  [app]
  (part app :groundwork/sessions))

(defn jobs
  "The app's jobs part, for groundwork.jobs (queued, dead, retry!), or nil."
  [app]
  (part app :groundwork/jobs))

(defn endpoints
  "The app's endpoints part, for groundwork.endpoints: (endpoints/call …)."
  [app]
  (part app :groundwork/endpoints))

(defn swap-routes!
  "Serve new routes without restarting, for the REPL:
   (gw/swap-routes! app (app.routes/routes)). Checked like at start; wrong
   routes throw and the old ones stay."
  [app routes]
  (web/swap-routes! (part app :groundwork/router) routes))

(defn port
  "The port the app's server listens on."
  [app]
  (server/port (part app :groundwork/server)))
