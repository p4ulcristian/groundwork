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
   [groundwork.permissions.check :as check]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [jsonista.core :as json])
  (:import
   (java.io FileOutputStream OutputStream PrintStream)))

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
                     (build/watch! (:browser settings) (:styles settings)))
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
  [{:keys [app settings nrepl shadow dir undo]}]
  (try
    (some-> app gw/stop)
    (when shadow (build/stop-watch! (:browser settings)))
    (when nrepl ((requiring-resolve 'nrepl.server/stop-server) nrepl))
    (io/delete-file (io/file dir ".nrepl-port") true)
    (finally
      (some-> undo (apply [])))))

(defonce ^{:doc "The development start serve made, for the nREPL: (repl/restart! @repl/running)."}
  running
  (atom nil))

(defn serve
  "The development start until the process is stopped, as a service runs
   (see docs/groundwork-dev.service): no terminal needed, and SIGTERM stops
   the app, the watcher and the nREPL cleanly on the way out.

     clojure -X:dev groundwork.repl/serve :settings app.settings/dev :nrepl-port 7888

   :settings is the settings map or the symbol of a var holding it; :dir and
   :nrepl-port as for start!. The running dev map is in repl/running."
  [{:keys [settings dir nrepl-port mcp-json?] :or {dir "." nrepl-port 0 mcp-json? true}}]
  (let [settings (if (symbol? settings) @(requiring-resolve settings) settings)]
    (reset! running (start! settings {:dir dir :nrepl-port nrepl-port :mcp-json? mcp-json?}))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (when-let [dev @running]
                                             (reset! running nil)
                                             (stop! dev)
                                             (println "Groundwork stopped")))))
    @(promise)))

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

(defn why
  "Why a person may or may not do an action, on one record ([kind id]) or in
   a scope (its id), in plain words: each sentence that gives the action and
   whether it holds, and the deny that said no (see
   groundwork.permissions.check/why).

     (why app peter :orders/edit [:order order-id])
     ;=> {:allowed? false
     ;    :because [\"Rule 2 {:allow :orders/edit …}: it doesn't hold.\" \"The order … is in shop ….\" …]}
     (why app pia :orders/see acme :flags #{})
     (why app nil :designs/mine [:design id] :visitor visitor-id)"
  [app person action target & {:as options}]
  (check/why (gw/permissions app) person action target options))

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
