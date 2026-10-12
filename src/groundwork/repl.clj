(ns groundwork.repl
  "Talking to a running app, in development (see the decision on REPLs by
   environment): one start for everything, endpoints asked as a person,
   permissions explained, and peeks into the lines, the bells, sessions and
   migrations.

     (def dev (repl/start! settings {}))   ; server, the browser build's watcher, nREPL, log
     (repl/ask (:app dev) :jobs/list {:shop/id acme} :as peter)
     (repl/why (:app dev) peter :orders/edit [:order order-id])
     (repl/lines (:app dev))  (repl/recent-bells (:app dev))
     (repl/sessions (:app dev) peter)  (repl/sign-out-everywhere! (:app dev) peter)
     (repl/migrations (:app dev))
     (repl/queued-jobs (:app dev))  (repl/dead-jobs (:app dev))  (repl/retry-job! (:app dev) id)
     (repl/explain-setting [:sessions :visitor-hours])  (repl/settings)
     (repl/stop! dev)

   The browser's side has groundwork.repl too (ClojureScript): the store's
   contents, from the browser build's REPL.

   What's tried here and not saved to a file is gone on the next start; the
   files are the truth."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.build :as build]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.jobs :as jobs]
   [groundwork.migrations :as migrations]
   [groundwork.parquery.websocket :as websocket]
   [groundwork.permissions :as perm]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.io FileOutputStream OutputStream PrintStream)))

(pg/defsql memberships-above "groundwork/repl/memberships_above.sql")

;; =============================================================================
;; One start for development
;; =============================================================================

(defn- tee
  "An output stream writing to both."
  ^OutputStream [^OutputStream a ^OutputStream b]
  (proxy [OutputStream] []
    (write
      ([x]
       (if (int? x)
         (do (.write a (int x)) (.write b (int x)))
         (do (.write a ^bytes x) (.write b ^bytes x))))
      ([x off len]
       (.write a ^bytes x (int off) (int len))
       (.write b ^bytes x (int off) (int len))))
    (flush [] (.flush a) (.flush b))
    (close [] (.flush a) (.close b))))

(defn- log-to!
  "Everything printed from now on also goes to dir/.groundwork/dev.log; the
   previous run's log is kept as dev.log.1. Returns how to put it back."
  [dir]
  (let [folder (io/file dir ".groundwork")
        log    (io/file folder "dev.log")
        _      (.mkdirs folder)
        _      (when (.exists log) (.renameTo log (io/file folder "dev.log.1")))
        file   (FileOutputStream. log)
        out    System/out
        err    System/err
        out'   (PrintStream. (tee out file) true "UTF-8")
        err'   (PrintStream. (tee err file) true "UTF-8")
        roots  [*out* *err*]]
    (System/setOut out')
    (System/setErr err')
    (alter-var-root #'*out* (constantly (io/writer out')))
    (alter-var-root #'*err* (constantly (io/writer err')))
    {:log  (.getPath log)
     :undo (fn []
             (System/setOut out)
             (System/setErr err)
             (alter-var-root #'*out* (constantly (first roots)))
             (alter-var-root #'*err* (constantly (second roots)))
             (.close file))}))

(defn- needs [sym what]
  (or (requiring-resolve sym)
      (throw (ex-info (str "The development start needs " what " on the classpath (an app's :dev alias)")
                      {:type ::missing-dependency :needs sym}))))

(defn- start-nrepl!
  "An nREPL server, with the browser build's middleware when shadow-cljs is
   there, so the same port has a ClojureScript REPL: (shadow.cljs.devtools.api/repl :app)."
  [dir port]
  (let [start   (needs 'nrepl.server/start-server "nrepl/nrepl")
        default (needs 'nrepl.server/default-handler "nrepl/nrepl")
        shadow  (try (requiring-resolve 'shadow.cljs.devtools.server.nrepl/middleware) (catch Exception _ nil))
        server  (start :port port :handler (if shadow (default shadow) (default)))
        port    (:port server)]
    (spit (io/file dir ".nrepl-port") (str port))
    server))

(defn write-mcp-json!
  "Point a clojure-mcp server at the nREPL in the app's .mcp.json (Claude
   Code's project MCP servers), keeping every other server there. Written
   only when it changes. Returns the file."
  [dir port]
  (let [file    (io/file dir ".mcp.json")
        current (when (.exists file)
                  (try (json/read-value file) (catch Exception _ nil)))
        server  {"type"    "stdio"
                 "command" "clojure"
                 "args"    ["-Tmcp" "start" ":config-profile" ":repl" ":port" (str port)]
                 "env"     {}}
        wanted  (assoc-in (if (map? current) current {}) ["mcpServers" "clojure-mcp"] server)]
    (when-not (= current wanted)
      (spit file (str (json/write-value-as-string wanted (json/object-mapper {:pretty true})) "\n")))
    file))

(defn start!
  "Start everything for development, from one place: the app, an nREPL for
   both sides, the browser build's watcher (when the :browser settings say
   :watch? true; see groundwork.build), and a log of everything printed at
   .groundwork/dev.log (the last run's kept as dev.log.1). Only for
   :environment :development.

     options  :dir           where the log, .nrepl-port and .mcp.json go, \".\" by default
              :nrepl-port    0 for any free port (default); a fixed one keeps
                             .mcp.json the same from start to start
              :mcp-json?     whether to point clojure-mcp at the nREPL in
                             .mcp.json (default true), so Claude Code
                             evaluates in the running app

   clojure-mcp itself is installed once per machine, as a Clojure tool
   named mcp (see its README); the user's own Claude config isn't touched.

   Needs nrepl/nrepl (and thheller/shadow-cljs for the watcher) on the
   classpath, as an app's :dev alias has them."
  [settings {:keys [dir nrepl-port mcp-json?] :or {dir "." nrepl-port 0 mcp-json? true}}]
  (when-not (= :development (:environment settings))
    (throw (ex-info "The development start is for :environment :development only"
                    {:type ::not-development :environment (:environment settings)})))
  (let [{:keys [log undo]} (log-to! dir)]
    (try
      (let [nrepl  (start-nrepl! dir nrepl-port)
            _      (when mcp-json? (write-mcp-json! dir (:port nrepl)))
            shadow (when (get-in settings [:browser :watch?])
                     (needs 'shadow.cljs.devtools.server/start! "thheller/shadow-cljs")
                     (build/watch! (:browser settings)))
            app    (gw/start settings)]
        (println (str "Groundwork is running: port " (gw/port app) ", nREPL " (:port nrepl)
                      (when shadow (str ", watching " shadow)) ", log " log))
        {:app app :settings settings :nrepl nrepl :shadow shadow :dir dir :log log :undo undo})
      (catch Throwable e
        (undo)
        (throw e)))))

(defn restart!
  "Stop the app and start it again with these settings (or the same ones),
   keeping the nREPL, the watcher and the log. Returns the new dev map."
  ([dev] (restart! dev (:settings dev)))
  ([{:keys [app] :as dev} settings]
   (gw/stop app)
   (assoc dev :app (gw/start settings) :settings settings)))

(defn stop!
  "Stop the app, the watcher and the nREPL, and stop logging."
  [{:keys [app nrepl shadow dir undo]}]
  (try
    (some-> app gw/stop)
    (when shadow ((requiring-resolve 'shadow.cljs.devtools.server/stop!)))
    (when nrepl ((requiring-resolve 'nrepl.server/stop-server) nrepl))
    (io/delete-file (io/file dir ".nrepl-port") true)
    (finally
      (some-> undo (apply [])))))

;; =============================================================================
;; Asking as a person
;; =============================================================================

(defn ask
  "Ask an endpoint as a person, through every check a browser's ask goes
   through: input shape, permission, the wall, the answer's shape. A write
   runs too (with a fresh one-time key unless :key is given).

     (ask app :jobs/list {:shop/id acme} :as peter)
     (ask app :jobs/move {…} :as peter :flags #{:impersonated})"
  [app endpoint params & {:keys [as flags key]}]
  (let [part (gw/endpoints app)]
    (endpoints/call part {:endpoint   endpoint
                          :params     params
                          :person     as
                          :flags      (or flags #{})
                          :key        (or key (when-not (:read? (endpoints/endpoint part endpoint))
                                                    (str (random-uuid))))
                          :request-id "repl"})))

;; =============================================================================
;; Why
;; =============================================================================

(defn- query-one [perms sql params]
  (perm/as-system perms (fn [tx] (jdbc/execute-one! (::pg/conn tx) (into [sql] params)
                                                    {:builder-fn rs/as-unqualified-maps}))))

(defn- place [kind id] (str (name kind) " " id))

(defn- membership-lines
  "What the person holds in a scope and above it."
  [perms person action scope]
  (let [rows (pg/rows (:db perms) memberships-above {:scope/id scope :person (str person)
                                                     :action   (rules/action-name action)})
        here (first rows)
        said (for [{:why/keys [scope-id kind depth role owner role-holds extra]} rows
                   :when (or role extra)
                   :let [where (str (place kind scope-id) (when (pos? depth) " (above it)"))]]
               (str/join " "
                         (remove nil?
                                 [(cond
                                    owner      (str person " is the owner of " where ": an owner holds everything.")
                                    role-holds (str person " is " role " in " where ", and " role " holds " action ".")
                                    role       (str person " is " role " in " where ", but " role " doesn't hold " action "."))
                                  (when extra (str person " has " action " as an extra action in " where "."))])))]
    (if (seq said)
      said
      [(str person " has no membership in " (place (:why/kind here) scope) " or above.")])))

(defn- link-lines
  "What the links into a scope give the person."
  [perms person action scope-kind scope]
  (for [{:keys [grants from to] t :table condition :when :as link} (get-in perms [:description :links])
        :let  [needed (grants action)]
        :when (and needed (= scope-kind (first to)))]
    (let [linked? (:yes (query-one perms (str "SELECT CAST(? AS uuid) IN (SELECT " (rules/link-function link)
                                              "(CAST(? AS uuid), ?)) AS yes")
                                   [scope (str person) (rules/action-name needed)]))]
      (if linked?
        (str "A link through " (name t) " gives " action " in " (place scope-kind scope) ": " person
             " holds " needed " in a " (name (first from)) " linked to it while " (pr-str condition) ".")
        (str "A link through " (name t) " would give " action " with " needed " in a linked "
             (name (first from)) ", but none links " person " to " (place scope-kind scope) " now"
             " (it needs " (pr-str condition) ").")))))

(defn- blocked-lines [perms action flags]
  (for [[flag actions] (get-in perms [:description :session-blocks])
        :when (and (contains? flags flag) (contains? actions action))]
    (str "The session is " (name flag) ", which blocks " action ".")))

(defn- record-lines
  "Why a person may or may not do an action on one record."
  [perms person action [kind id] flags]
  (let [{t :table id-col :id :keys [people only-when] :as spec} (get-in perms [:description :records kind])
        scopes (when spec (perm/scopes-of perms [kind id]))
        arrows (when spec (let [in (:in spec)] (if (keyword? (first in)) [in] in)))]
    (cond
      (nil? spec)
      [(str "There's no record kind " kind " in the permission description.")]

      (not (perm/declared? perms action kind))
      [(str "Nothing gives " action " on a " (name kind) ": no endpoint can declare it.")]

      (empty? scopes)
      [(str "There's no " (name kind) " " id ".")]

      :else
      (concat
       (for [[scope-kind col] arrows
             :let [scope (:s (query-one perms (str "SELECT " (rules/quote-column col) " AS s FROM "
                                                   (rules/quote-table t) " WHERE " (rules/quote-column id-col) " = ?")
                                        [id]))]
             :when scope
             line (concat [(str "The " (name kind) " " id " is in " (place scope-kind scope) ".")]
                          (membership-lines perms person action scope)
                          (link-lines perms person action scope-kind scope))]
         line)
       (when-let [col (get people action)]
         (let [owner (:o (query-one perms (str "SELECT " (rules/quote-column col) " AS o FROM " (rules/quote-table t)
                                               " WHERE " (rules/quote-column id-col) " = ?")
                                    [id]))]
           [(if (= (str owner) (str person))
              (str "The " (name kind) "'s " (name col) " is " person ": it's theirs.")
              (str "The " (name kind) "'s " (name col) " isn't " person "."))]))
       (when-let [condition (get only-when action)]
         (let [[sql params] (rules/condition-sql "r" condition)
               holds?       (:h (query-one perms (str "SELECT " sql " AS h FROM " (rules/quote-table t) " r WHERE r."
                                                      (rules/quote-column id-col) " = ?")
                                           (conj params id)))]
           [(if holds?
              (str action " only when " (pr-str condition) ": it holds now.")
              (str action " only when " (pr-str condition) ": it doesn't hold now, so nobody may."))]))
       (blocked-lines perms action flags)))))

(defn- scope-lines
  "Why a person may or may not do an action in a scope."
  [perms person action scope flags]
  (let [kind (some-> (query-one perms "SELECT scope__kind AS k FROM groundwork.scopes WHERE scope__id = ?" [scope])
                     :k keyword)]
    (if (nil? kind)
      [(str "There's no scope " scope ".")]
      (concat (membership-lines perms person action scope)
              (link-lines perms person action kind scope)
              (blocked-lines perms action flags)))))

(defn why
  "Why a person may or may not do an action, on one record ([kind id]) or in
   a scope (its id), in plain words: which scope the record is in, the
   person's memberships, roles and extra actions there and above, the links
   followed, a person column, an only-when, a session's flags.

     (why app peter :orders/edit [:order order-id])
     ;=> {:allowed? false
     ;    :because [\"The order … is in shop ….\" \"… is Production in shop …, but Production doesn't hold :orders/edit.\" …]}
     (why app pia :orders/see acme :flags #{})"
  [app person action target & {:keys [flags] :or {flags #{}}}]
  (let [perms    (gw/permissions app)
        record?  (vector? target)
        allowed? (if record?
                   (and (some? (get-in perms [:description :records (first target)]))
                        (perm/declared? perms action (first target))
                        (perm/allowed? perms {:person person :flags flags :action action :record target}))
                   (contains? (perm/scopes-under perms {:person person :flags flags :action action :scope target})
                              target))]
    {:allowed? (boolean allowed?)
     :because  (vec (if record?
                      (record-lines perms person action target flags)
                      (scope-lines perms person action target flags)))}))

;; =============================================================================
;; Peeks
;; =============================================================================

(defn- bells-part [app]
  (:bells (gw/part app :groundwork/parquery)))

(defn lines
  "The open websocket lines on this server: who, their session, what they watch."
  [app]
  (websocket/lines-of (bells-part app)))

(defn recent-bells
  "The last bells this server heard, newest last."
  [app]
  (websocket/recent-bells (bells-part app)))

(defn sessions
  "A person's live sessions."
  [app person]
  (sessions/of-person (gw/part app :groundwork/sessions) person))

(defn sign-out-everywhere!
  "End every session of a person; their open lines close too."
  [app person]
  (sessions/sign-out-everywhere! (gw/part app :groundwork/sessions) person))

(defn migrations
  "Which migrations ran, which wait, which changed or went missing since
   (those two would stop the next start)."
  [app]
  (migrations/status (gw/db app)))

;; =============================================================================
;; Settings
;; =============================================================================

(defn explain-setting
  "What goes at a place in the settings: its type, what it is, an example.
   (explain-setting [:sessions :visitor-hours])"
  [path]
  (settings/explain path))

(defn settings
  "Every setting gw/start takes, in order, each a line: its place, its type
   and what it is. docs/example-settings.edn has them with examples."
  []
  (for [{:keys [path type description]} (settings/all)]
    (str (str/join " " (map pr-str path)) "  (" type ")  " description)))

;; =============================================================================
;; Jobs
;; =============================================================================

(defn queued-jobs
  "Jobs waiting to run, newest first."
  [app]
  (some-> (gw/jobs app) jobs/queued))

(defn dead-jobs
  "Jobs that had all their attempts, with their last error."
  [app]
  (some-> (gw/jobs app) jobs/dead))

(defn retry-job!
  "Run a dead job again now."
  [app id]
  (jobs/retry! (gw/jobs app) id))
