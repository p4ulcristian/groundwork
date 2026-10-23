(ns groundwork.server.main.asker
  "Who is asking, off a message: on the app's own routes, a page, an
   upload. groundwork-access's session step finds the asker in the request (the
   session's cookie, or a token groundwork-access made); groundwork reads what it
   found, and runs a transaction as the asker groundwork-access finds in the same
   request. No code here names a person."
  (:require
   [groundwork-access.api :as access]))

(defn read-asker
  "The asker groundwork-access's session step put in the context, to read:
   {:person-id :visitor-id :session-flags}."
  [context]
  {:person-id     (:groundwork-access/person-id context)
   :visitor-id    (:groundwork-access/visitor-id context)
   :session-flags (:groundwork-access/session-flags context)})

(defn make-run-as-asker
  "(fn [{:keys [read-only?]} f]): f in a transaction as the asker groundwork-access
   finds in the request (groundwork-access's run-as-asker-of-request!), held by the
   gates, carrying log, the request's (groundwork-postgres's :log: work kept with
   run-after-commit! that fails is written there, saying who asked)."
  [pool request settings scope-kinds log]
  (fn [{:keys [read-only?]} f]
    (access/run-as-asker-of-request! pool request {:settings    (:access settings)
                                                    :read-only?  read-only?
                                                    :scope-kinds scope-kinds
                                                    :log         log}
                                      f)))
