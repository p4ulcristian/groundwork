(ns groundwork.server.main.files
  "The upload routes: groundwork-file-storage's upload handler (make-upload-handler), one
   for each storage of the :file-storage :storages settings whose :upload is a
   map, at its :path, a POST, multipart, one part per file; a storage whose
   :upload is nil has no route. Each route stores into its own storage.
   Who may upload is asked before the body is read, as a message's asker
   is found, once: groundwork-access's session step (by the session's cookie, the
   request's Origin must be the :access settings' :allowed-origin, else
   403; or by a token groundwork-access made; or by an outside issuer's, one
   that fails its check refused 401, WWW-Authenticate: Bearer
   error=\"invalid_token\"), then the app's own :may-upload?, (fn [asker
   storage-name] true-or-false), the asker {:person-id :visitor-id :session-flags
   :token-id :outside-issuer} groundwork-access found (nils and #{} for nobody)
   and the name of the storage the route stores into, so the app says who
   may upload to which:

     POST /groundwork/files/public
     Origin: https://shop.example
     Cookie: app_session=…
     200 {\"stored\":[{\"field\":\"photo\",\"key\":\"shops/logos/….png\",…}],\"rejected\":[…]}

   The files are groundwork-file-storage's: content-named, recorded in the storage's files table
   (groundwork_file_storage.<its :storage settings' :table's name>), each
   told by its bytes, one of groundwork-file-storage's kinds or the app's own (:file-kinds),
   which :allowed-content-types names; the app keeps a file's key in its own rows by a
   message, as any write."
  (:require
   [groundwork-file-storage.api :as file-storage]
   [groundwork-steps.api :as steps]
   [groundwork.server.main.asker :as asker]))

(defn- make-may-upload
  "groundwork-file-storage's :may-upload? for one storage's route: the app's
   (fn [asker storage-name]) on the asker the route's handler found (::asker
   on the request) and that storage's name; a request the session step
   refused (::refused) may not."
  [app-may-upload? storage-name]
  (fn [request]
    (and (not (::refused request))
         (boolean (app-may-upload? (::asker request) storage-name)))))

(defn- make-upload-handler
  "groundwork-access's session step once, on the request, then
   groundwork-file-storage's upload handler with what it found: an outside token
   that failed its check answered 401 here (asker's answer-refused-request),
   any other refusal (another site's request, a cookie and a token at once)
   handed on as ::refused, which groundwork-file-storage answers 403."
  [upload-handler log find-session-step]
  (fn [request]
    (let [context ((:enter find-session-step) {:groundwork-steps/request request :groundwork-steps/log log})
          reason  (get-in context [:groundwork-steps/answer :groundwork-steps/refused])]
      (cond
        (= :groundwork-access/token-refused reason) (asker/answer-refused-request reason {})
        (steps/does-context-have-answer? context)  (upload-handler (assoc request ::refused reason))
        :else                                       (upload-handler (assoc request ::asker (asker/read-asker context)))))))

(defn make-upload-routes
  "The upload routes as reitit data, by the storages' names in order: each
   storage of the :file-storage :storages settings whose :upload is a map
   at its :path, storing into that storage (running-file-storages, by
   name); none for a storage whose :upload is nil."
  [running-file-storages shaper log pool find-session-step app-may-upload? {{:keys [storages]} :file-storage}]
  (vec (for [[storage-name {:keys [upload]}] (sort-by key storages)
             :when upload]
         [(:path upload)
          {:post {:handler (make-upload-handler (file-storage/make-upload-handler (get running-file-storages storage-name)
                                                                                  (dissoc upload :path)
                                                                                  {:pool        pool
                                                                                   :shaper      shaper
                                                                                   :may-upload? (make-may-upload app-may-upload? storage-name)})
                                                log find-session-step)}}])))
