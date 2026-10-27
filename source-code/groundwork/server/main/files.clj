(ns groundwork.server.main.files
  "The upload route: groundwork-file-storage's upload handler (make-upload-handler) at the
   :file-storage :upload settings' :path, a POST, multipart, one part per file.
   Who may upload is asked before the body is read, as a message's asker
   is found: groundwork-access's session step (by the session's cookie, the
   request's Origin must be the :access settings' :allowed-origin, else
   403; or by a token groundwork-access made), then the app's own :may-upload?, (fn
   [asker] true-or-false), the asker {:person-id :visitor-id
   :session-flags} groundwork-access found (nils and #{} for nobody):

     POST /groundwork/files
     Origin: https://shop.example
     Cookie: app_session=…
     200 {\"stored\":[{\"field\":\"photo\",\"key\":\"shops/photos/….png\",…}],\"rejected\":[…]}

   The files are groundwork-file-storage's: content-named, recorded in the store's files table
   (groundwork_file_storage.<the :file-storage :storage settings' :table's name>), each
   told by its bytes, one of groundwork-file-storage's kinds or the app's own (:file-kinds),
   which :allowed-content-types names; the app keeps a file's key in its own rows by a
   message, as any write."
  (:require
   [groundwork-file-storage.api :as file-storage]
   [groundwork-steps.api :as steps]
   [groundwork.server.main.asker :as asker]))

(defn- make-may-upload
  "groundwork-file-storage's :may-upload?: groundwork-access's session step on the request, then the
   app's (fn [asker])."
  [log find-session-step app-may-upload?]
  (fn [request]
    (let [context ((:enter find-session-step) {:groundwork-steps/request request :groundwork-steps/log log})]
      (and (not (steps/does-context-have-answer? context))
           (boolean (app-may-upload? (asker/read-asker context)))))))

(defn make-upload-routes
  "The upload route, at the :file-storage :upload settings' :path, as reitit data."
  [running-file-storage shaper log pool find-session-step app-may-upload? {{:keys [upload]} :file-storage}]
  [[(:path upload)
    {:post {:handler (file-storage/make-upload-handler running-file-storage
                                                (dissoc upload :path)
                                                {:pool        pool
                                                 :shaper        shaper
                                                 :may-upload? (make-may-upload log find-session-step app-may-upload?)})}}]])
