(ns groundwork.endpoints.websocket
  "parquery over a websocket, the server half: the lines that carry the
   bells (what rings and what each read waits for is
   groundwork.endpoints.bells).

   A browser opens one line to /parquery and says which reads it shows,
   {\"watch\": [[endpoint params] …]}, the whole list each time it changes.
   Each watch is checked like an ask; one the person may not ask is
   dropped. When something they show changes, the server says which,
   {\"changed\": [[endpoint params] …]}, and the browser asks again through
   HTTP. A bell carries no data. A bell rung by an endpoint's write carries
   that write's tag, {\"changed\": […], \"tag\": \"…\"}: the browser that wrote
   got the same news with its answer (changed-for) and doesn't ask twice.

   Every table of the app says how it rings, or the start refuses, naming
   the ones that don't: it is in the permission description (:records), or in
   the :bells setting as :global (with the reason it is nobody's, or why
   every row is read by everyone), :personal or :silent (with the reason it
   rings nothing). A table whose rows belong to a scope is never global: it
   is in the permission description, where who is rung comes from who may
   see. Groundwork's own team tables ring too (memberships, roles,
   a role's actions, extra actions, invitations), under their schema's name,
   \"groundwork.memberships\".

   Postgres rings: at start, every table in the permission description, and
   the :global and :personal tables of the :bells setting, gets a trigger
   (groundwork.ring) that announces, after a statement commits, the table,
   the scopes and people of the changed rows and their ids. Every server
   listens on its own connection. A watch waits for the tables its read's
   SQL uses, learnt from Postgres's plan of each file it ran (plus the
   endpoint's :reads), in its verified scopes; a global table rings
   everyone watching it, a personal one only its person. A read about one
   record rings only when that record changed. A read whose tables aren't
   known yet rings on any change in its scopes.

   When the listening connection drops, it reconnects and rings everything,
   since bells may have been missed.

   A line lives as long as the session it was opened with. A session that
   ends (signed out, signed out everywhere, deleted) rings on the same
   channel, and every server closes that session's lines; each watch message
   checks the session first; and a sweep every minute closes lines whose
   session expired and checks every line's watches again, so a person removed
   from a scope stops hearing about it."
  (:require
   [clojure.set :as set]
   [groundwork.endpoints :as endpoints]
   [groundwork.endpoints.bells :as bells]
   [groundwork.endpoints.wire :as wire]
   [groundwork.permissions.check :as check]
   [groundwork.permissions.description :as description]
   [groundwork.sessions :as sessions]
   [jsonista.core :as json]
   [org.httpkit.server :as http])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection)
   (org.postgresql PGConnection PGNotification)))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.endpoints.websocket"))

;; =============================================================================
;; Lines and their watches
;; =============================================================================

(defn- send! [ch message]
  (http/send! ch (wire/write-json message)))

(defn- read-watch
  "A watch as it arrived: [endpoint params]."
  [x]
  (when (and (vector? x) (= 2 (count x)) (string? (first x)) (map? (second x)))
    [(keyword (first x)) (second x)]))

(defn- close-line!
  "Close a line and forget it."
  [{:keys [lines]} ch]
  (swap! lines dissoc ch)
  (http/close ch))

(defn- checked-watches
  "A line's watches, each checked like an ask; one it may not ask is
   dropped. Each also listens in the scopes where the person could be the
   one a related row names (a via): a change there changes what they see."
  [{:keys [endpoints]} {:keys [person visitor flags]} asks]
  (let [tables    (get-in endpoints [:perms :description :records])
        listening (delay (check/listening (:perms endpoints) person))]
    (vec (keep (fn [[endpoint params]]
                 (when-let [{:keys [scopes record] only :tables}
                            (endpoints/watch endpoints {:endpoint endpoint :params params
                                                        :person person :visitor visitor :flags flags})]
                   {:ask          [(wire/endpoint-name endpoint) params]
                    :endpoint     endpoint
                    :scopes       (if (= :every scopes) :every (set (map str scopes)))
                    :listening    (if (:every? @listening) :every (set (map str (:scopes @listening))))
                    :visitor      visitor
                    :only-tables  only
                    :record-table (some-> record first tables :table name)
                    :record-id    (some-> record second str)}))
               asks))))

(defn- session-ended?
  "Whether the session a line was opened with has ended."
  [{:keys [sessions]} {:keys [session]}]
  (and session (empty? (sessions/live sessions [session]))))

(defn- watch!
  "A line's new list of watches, each checked like an ask, after its session."
  [{:keys [lines] :as part} ch text]
  (when-let [line (get @lines ch)]
    (if (session-ended? part line)
      (close-line! part ch)
      (let [message (try (wire/read-json text) (catch Exception _ nil))
            asks    (vec (keep read-watch (:watch message)))]
        (swap! lines update ch assoc :asks asks :watches (checked-watches part line asks))))))

(defn- end-sessions!
  "Close the lines of these ended sessions; nil means some ended, check them
   all."
  [{:keys [lines sessions] :as part} hashes]
  (let [opened (keep :session (vals @lines))
        ended  (if (nil? hashes)
                 (set/difference (set opened) (sessions/live sessions opened))
                 (set hashes))]
    (doseq [[ch {:keys [session]}] @lines
            :when (contains? ended session)]
      (close-line! part ch))))

(defn recheck!
  "Close every line whose session ended, expiry included, and check every
   other line's watches again: a person removed from a scope stops hearing
   about it. The sweep runs this every minute."
  [{:keys [lines] :as part}]
  (end-sessions! part nil)
  (doseq [[ch line] @lines]
    (swap! lines (fn [ls] (if (contains? ls ch) (assoc-in ls [ch :watches] (checked-watches part line (:asks line))) ls)))))

(defn- meets?
  "Whether a watch's scopes (a set, or :every) meet a bell's (nil: any)."
  [scopes s]
  (or (nil? s)
      (if (= :every scopes) (boolean (seq s)) (boolean (seq (set/intersection scopes (set s)))))))

(defn- rings?
  "Whether a bell concerns a watch. A row's own table rings the watches in
   its scopes and the people it names; a row a via looks for rings the
   reads of what it gives, in its scopes and where the person could be the
   one it names; a row that belongs to nobody rings everyone reading its
   table."
  [part person {:keys [endpoint scopes listening record-table record-id only-tables visitor]} {:keys [t s p i u]}]
  (let [ringing (get (:tables part) t)
        tables  (bells/tables-of-endpoint part endpoint)
        gives   (get (:via-tables part) t)]
    (and (or (nil? tables) (contains? tables t)
             ;; a row a via looks for changes who sees the rows it gives
             (some tables gives))
         (or (:global? ringing)
             (and u (or (nil? only-tables) (contains? only-tables t)))
             ;; a scope's bell reaches a watch verified only by an open word for the tables it shows
             (and (or (nil? only-tables) (contains? only-tables t))
                  (meets? scopes s))
             (and gives (meets? listening s))
             ;; a person's own rows, or a visitor's: the bell names whose
             (let [me (or person visitor)]
               (and me (or (and person (nil? p)) (some #{(str me)} p)))))
         (or (not= t record-table) (nil? i) (some #{record-id} i)))))

(def ^:private recent-kept 50)

(defn- ring! [{:keys [lines recent] :as part} bell]
  (let [rang (atom 0)]
    (doseq [[ch {:keys [person watches]}] @lines
            :let [changed (into [] (comp (filter #(rings? part person % bell)) (map :ask) (distinct)) watches)]
            :when (seq changed)]
      (swap! rang inc)
      ;; a write's bells carry its tag: the browser that wrote has them already
      (send! ch (cond-> {:changed changed} (:g bell) (assoc :tag (:g bell)))))
    (when recent
      (swap! recent #(vec (take-last recent-kept (conj % {:at (java.time.Instant/now) :bell bell :lines @rang})))))))

(defn changed-for
  "Of the reads a browser shows (`watching`, [endpoint params] as they
   travel), the ones a write's own bells concern, each checked like an ask
   first. `rang` is the write's bells (see groundwork.endpoints.bells/bell), or
   :any: everything it shows that it may ask."
  [part {:keys [person visitor flags]} watching rang]
  (let [asks    (vec (keep read-watch watching))
        rung    (when-not (= :any rang)
                  (into (set (map :t rang)) (mapcat #(get (:via-tables part) (:t %))) rang))
        maybe   (if rung
                  (filterv (fn [[endpoint _]]
                             (let [tables (bells/tables-of-endpoint part endpoint)]
                               (or (nil? tables) (seq (set/intersection tables rung)))))
                           asks)
                  asks)
        watches (checked-watches part {:person person :visitor visitor :flags (or flags #{})} maybe)]
    (into []
          (comp (filter (fn [watch] (or (nil? rung) (some #(rings? part person watch %) rang))))
                (map :ask)
                (distinct))
          watches)))

(defn lines-of
  "The open lines, for the REPL: who opened each, its session (the first
   characters of its hash) and the reads it watches."
  [{:keys [lines]}]
  (mapv (fn [{:keys [person flags session watches]}]
          {:person  person
           :flags   flags
           :session (some-> session (subs 0 12))
           :watches (mapv :ask watches)})
        (vals @lines)))

(defn recent-bells
  "The last bells this server heard, newest last: when, the bell (table,
   scopes, people, ids) and how many lines it rang."
  [{:keys [recent]}]
  @recent)

(defn- ring-everything! [{:keys [lines]}]
  (doseq [[ch {:keys [watches]}] @lines
          :when (seq watches)]
    (send! ch {:changed (into [] (comp (map :ask) (distinct)) watches)})))

;; =============================================================================
;; Listening
;; =============================================================================

(defn- listening-connection ^Connection [db-settings]
  (doto (bells/connect db-settings {})
    (-> (.createStatement) (.execute "LISTEN groundwork"))))

(defn- listen!
  "Hear the bells until stopped; on a dropped connection, reconnect and
   ring everything."
  [{:keys [db-settings stopped] :as part}]
  (loop [conn nil, wait 0]
    (when-not @stopped
      (let [conn (or conn
                     (try
                       (when (pos? wait) (Thread/sleep (long wait)))
                       (let [c (listening-connection db-settings)]
                         (when (pos? wait) (ring-everything! part))
                         c)
                       (catch Exception e
                         (.log logger System$Logger$Level/WARNING "Can't listen for bells" e)
                         nil)))]
        (if (nil? conn)
          (recur nil (min 30000 (max 1000 (* 2 wait))))
          (let [result (try
                         (doseq [^PGNotification n (.getNotifications (.unwrap conn PGConnection) 1000)]
                           (try (let [message (json/read-value (.getParameter n) json/keyword-keys-object-mapper)]
                                  (if (contains? message :e)
                                    (end-sessions! part (:e message))
                                    (ring! part message)))
                                (catch Exception e
                                  (.log logger System$Logger$Level/WARNING "A bell couldn't ring" e))))
                         :ok
                         (catch Exception e
                           (when-not @stopped
                             (.log logger System$Logger$Level/WARNING "The bells' connection dropped" e))
                           (try (.close conn) (catch Exception _ nil))
                           :dropped))]
            (if (= :ok result)
              (recur conn 0)
              (recur nil 1000))))))))

;; =============================================================================
;; The route and the part
;; =============================================================================

(defn- sweep!
  "Every minute until stopped: recheck!."
  [{:keys [stopped] :as part}]
  (try
    (loop []
      (Thread/sleep 60000)
      (when-not @stopped
        (try (recheck! part)
             (catch Exception e
               (.log logger System$Logger$Level/WARNING "The lines' sweep failed" e)))
        (recur)))
    (catch InterruptedException _ nil)))

(defn handle
  "The Ring handler for a websocket to /parquery."
  [{:keys [bells sessions]} request]
  (cond
    (not (:websocket? request))
    {:status 426 :headers {"Content-Type" "text/plain; charset=utf-8" "Upgrade" "websocket"}
     :body "Ask with a POST; this address's GET is the websocket for the bells"}

    (not= (get-in sessions [:settings :origin]) (get-in request [:headers "origin"]))
    {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body "Refused: the request didn't come from this app's pages"}

    :else
    (let [{:keys [lines]} bells
          person          (sessions/person request)
          visitor         (sessions/visitor request)
          flags           (sessions/flags request)
          session         (sessions/session-hash request)]
      (http/as-channel request
                       {:on-open    (fn [ch] (swap! lines assoc ch {:person person :visitor visitor :flags flags
                                                                    :session session
                                                                    :asks [] :watches []}))
                        :on-receive (fn [ch text] (watch! bells ch text))
                        :on-close   (fn [ch _] (swap! lines dissoc ch))}))))

(defn start
  "The bells: triggers installed (in production, checked: migrate installed
   them), a connection listening, the lines' sweep."
  [{:keys [endpoints db db-settings settings sessions environment]}]
  (let [description (get-in endpoints [:perms :description])
        tables      (bells/ringing-tables description (:bells settings))
        part        {:endpoints   endpoints
                     :sessions    sessions
                     :db          db
                     :db-settings db-settings
                     :tables      tables
                     :via-tables  (description/via-tables description)
                     :plans       (atom {})
                     :lines       (atom {})
                     :recent      (atom [])
                     :stopped     (atom false)}]
    (bells/check-every-table-rings! db tables (:bells settings))
    (if (= :production environment)
      (bells/check-installed! db tables)
      (bells/install-bells! db tables))
    (assoc part
           :listener (Thread/startVirtualThread #(listen! part))
           :sweeper  (Thread/startVirtualThread #(sweep! part)))))

(defn stop
  "Stop listening and the sweep, and close every line."
  [{:keys [stopped listener ^Thread sweeper lines]}]
  (when stopped
    (reset! stopped true)
    (doseq [ch (keys @lines)] (http/close ch))
    (some-> sweeper .interrupt)
    (.join ^Thread listener 3000)))
