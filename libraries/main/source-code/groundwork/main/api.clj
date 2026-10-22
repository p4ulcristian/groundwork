(ns groundwork.main.api
  "The main library's api on the server: start, stop and migrate an app.
   For now it opens onto today's Groundwork (groundwork.core); as the parts
   become libraries of their own, this is where they are put together."
  (:require
   [groundwork.core :as core]))

(defn start
  "Start an app from its settings; returns the running app."
  [settings]
  (core/start settings))

(defn stop
  "Stop a running app."
  [app]
  (core/stop app))

(defn migrate!
  "Production's migrate, before the start: {:user :password} of the owner."
  [settings owner]
  (core/migrate! settings owner))
