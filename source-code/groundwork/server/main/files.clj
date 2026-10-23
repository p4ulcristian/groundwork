(ns groundwork.server.main.files
  "The upload route: attic's upload handler (make-upload-handler) at the
   :attic :upload settings' :path, a POST, multipart, one part per file.
   Who may upload is asked before the body is read, as a message's asker
   is found: doorman's session step (by the session's cookie, the
   request's Origin must be the :sessions settings' :allowed-origin, else
   403; or by a token doorman made), then the app's own :may-upload?, (fn
   [asker] true-or-false), the asker {:person-id :visitor-id
   :session-flags} doorman found (nils and #{} for nobody):

     POST /groundwork/files
     Origin: https://shop.example
     Cookie: app_session=…
     200 {\"stored\":[{\"field\":\"photo\",\"key\":\"shops/photos/….png\",…}],\"rejected\":[…]}

   The files are attic's: content-named, recorded in attic.files; the
   app keeps a file's key in its own rows by a message, as any write."
  (:require
   [attic.api :as attic]
   [bridge.api :as bridge]
   [groundwork.server.main.asker :as asker]))

(defn- make-may-upload
  "attic's :may-upload?: doorman's session step on the request, then the
   app's (fn [asker])."
  [log find-session-step app-may-upload?]
  (fn [request]
    (let [context ((:enter find-session-step) {:bridge/request request :bridge/log log})]
      (and (not (bridge/does-context-have-answer? context))
           (boolean (app-may-upload? (asker/read-asker context)))))))

(defn make-upload-routes
  "The upload route, at the :attic :upload settings' :path, as reitit data."
  [running-attic wire log pool find-session-step app-may-upload? {{:keys [upload]} :attic}]
  [[(:path upload)
    {:post {:handler (attic/make-upload-handler running-attic
                                                (dissoc upload :path)
                                                {:pool        pool
                                                 :wire        wire
                                                 :may-upload? (make-may-upload log find-session-step app-may-upload?)})}}]])
