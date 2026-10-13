(ns groundwork.core
  "Starting and stopping an app.

     (def app (gw/start settings))
     (gw/stop app)

   The settings map holds what is the app's own, one key per part; every key
   is required and an unknown key throws, so a typo can't go unnoticed. Every
   problem is reported at once, with its place (see groundwork.settings, and
   docs/example-settings.edn for every key with an example):

     :environment  :development, :test or :production
     :db           the database, see groundwork.postgres/start
     :migrations   see groundwork.migrations/start
     :page         see groundwork.page
     :web          the app's routes and how requests are handled; see groundwork.web
     :server       see groundwork.server
     :sessions     see groundwork.sessions
     :permissions  the app's permission description, see groundwork.permissions.rules
     :limits       how often a caller may ask, see groundwork.limits
     :jobs         background jobs and schedules, or nil; see groundwork.jobs
     :parquery     the app's endpoints and how they're asked, see groundwork.endpoints

   Inside, each part is an Integrant key and Integrant starts them in order,
   hands them to each other and stops them in reverse. That wiring is
   Groundwork's own: an app's settings don't name parts or replace them."
  (:require
   [groundwork.endpoints :as endpoints]
   [groundwork.jobs :as jobs]
   [groundwork.limits :as limits]
   [groundwork.migrations :as migrations]
   [groundwork.page :as page]
   [groundwork.parquery.route :as parquery]
   [groundwork.permissions :as permissions]
   [groundwork.postgres :as pg]
   [groundwork.server :as server]
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
   :groundwork/sessions   {:settings :sessions
                           :needs    [:groundwork/db :groundwork/migrations]
                           :start    sessions/start}
   :groundwork/permissions {:settings :permissions
                            :needs    [:groundwork/db :groundwork/migrations]
                            :start    permissions/start}
   :groundwork/limits     {:settings :limits
                           :needs    [:groundwork/db :groundwork/migrations]
                           :start    limits/start}
   :groundwork/jobs       {:settings :jobs
                           :needs    [:groundwork/db :groundwork/migrations :groundwork/permissions]
                           :start    jobs/start
                           :stop     jobs/stop}
   :groundwork/endpoints  {:settings :parquery
                           :needs    [:groundwork/db :groundwork/migrations :groundwork/permissions
                                      :groundwork/limits :groundwork/jobs]
                           :start    endpoints/start}
   :groundwork/parquery   {:settings :parquery
                           :needs    [:groundwork/db :groundwork/endpoints :groundwork/sessions]
                           :start    parquery/start
                           :stop     parquery/stop}
   :groundwork/page       {:settings :page
                           :needs    []
                           :start    page/start}
   :groundwork/router     {:settings :web
                           :needs    [:groundwork/migrations :groundwork/sessions :groundwork/permissions
                                      :groundwork/page :groundwork/parquery :groundwork/limits]
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
  "The app started last in this JVM and still running, for code evaluated
   from outside it (groundwork.eval-bridge) and the REPL."
  []
  @current-app)

(defn- init
  "Start the parts. When one refuses, the parts started before it are
   stopped again and the part's own error is thrown, not Integrant's around
   it: the message says what to change."
  [config]
  (try
    (ig/init config)
    (catch clojure.lang.ExceptionInfo e
      (when-let [system (:system (ex-data e))]
        (try (ig/halt! system) (catch Throwable _ nil)))
      (throw (or (ex-cause e) e)))))

(defn start
  "Start an app from its settings. Returns the running app; give it to stop."
  [settings]
  (settings/check! settings)
  (reset! current-app {::system (init (config settings))}))

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
                          fakes)]
    (reset! current-app {::system (init config)
                         ::fakes  (set (keys fakes))})))

(defn stop
  "Stop a running app, its parts in reverse order."
  [app]
  (let [system (::system app)]
    (swap! current-app #(when-not (identical? (::system %) system) %))
    (ig/halt! system (remove (::fakes app #{}) (keys system)))))

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
