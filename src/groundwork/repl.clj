(ns groundwork.repl
  "Talking to a running app, in development (see the decision on REPLs by
   environment): endpoints asked as a person, permissions explained, and
   peeks into the lines, the bells, sessions and migrations. The app comes
   from the development start (groundwork.repl.dev).

     (def dev (dev/start! settings {}))   ; server, the browser build's watcher, nREPL, log
     (repl/ask (:app dev) :jobs/list {:shop/id acme} :as peter)
     (repl/why (:app dev) peter :orders/edit [:order order-id])
     (repl/lines (:app dev))  (repl/recent-bells (:app dev))
     (repl/sessions (:app dev) peter)  (repl/sign-out-everywhere! (:app dev) peter)
     (repl/migrations (:app dev))
     (repl/queued-jobs (:app dev))  (repl/dead-jobs (:app dev))  (repl/retry-job! (:app dev) id)
     (repl/explain-setting [:sessions :visitor-hours])  (repl/settings)
     (dev/stop! dev)

   The browser's side is groundwork.repl.browser (ClojureScript): the
   store's contents, from the browser build's REPL.

   What's tried here and not saved to a file is gone on the next start; the
   files are the truth."
  (:require
   [clojure.string :as str]
   [groundwork.core :as gw]
   [groundwork.endpoints :as endpoints]
   [groundwork.jobs :as jobs]
   [groundwork.migrations :as migrations]
   [groundwork.parquery.websocket :as websocket]
   [groundwork.permissions.check :as check]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]))

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
