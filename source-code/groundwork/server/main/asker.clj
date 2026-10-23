(ns groundwork.server.main.asker
  "Who is asking, off a message: on the app's own routes, a page, an
   upload. doorman's session step finds the asker in the request (the
   session's cookie, or a token doorman made); groundwork reads what it
   found, and runs a transaction as the asker doorman finds in the same
   request. No code here names a person."
  (:require
   [doorman.api :as doorman]))

(defn read-asker
  "The asker doorman's session step put in the context, to read:
   {:person-id :visitor-id :session-flags}."
  [context]
  {:person-id     (:doorman/person-id context)
   :visitor-id    (:doorman/visitor-id context)
   :session-flags (:doorman/session-flags context)})

(defn make-run-as-asker
  "(fn [{:keys [read-only?]} f]): f in a transaction as the asker doorman
   finds in the request (doorman's run-as-asker-of-request!), held by the
   gates, carrying log, the request's (cellar's :log: work kept with
   run-after-commit! that fails is written there, saying who asked)."
  [pool request settings scope-kinds log]
  (fn [{:keys [read-only?]} f]
    (doorman/run-as-asker-of-request! pool request {:settings    (:sessions settings)
                                                    :read-only?  read-only?
                                                    :scope-kinds scope-kinds
                                                    :log         log}
                                      f)))
