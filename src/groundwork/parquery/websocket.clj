(ns groundwork.parquery.websocket
  "parquery over a websocket, the server half: the bells.

   A browser opens one line to /parquery and says which reads it shows,
   {\"watch\": [[endpoint params] …]}, the whole list each time it changes.
   Each watch is checked like an ask; one the person may not ask is
   dropped. When something they show changes, the server says which,
   {\"changed\": [[endpoint params] …]}, and the browser asks again through
   HTTP. A bell carries no data.

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
   since bells may have been missed."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.endpoints :as endpoints]
   [groundwork.names :as names]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.sessions :as sessions]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [org.httpkit.server :as http])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.sql Connection DriverManager)
   (java.util Properties)
   (org.postgresql PGConnection PGNotification)))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.parquery.websocket"))

;; =============================================================================
;; Which tables ring
;; =============================================================================

(defn- column [k]
  (names/key->column k))

(defn- columns-of [description kind]
  (let [{:keys [in people]} (get-in description [:records kind])
        arrows              (if (keyword? (first in)) [in] in)]
    {:scopes (mapv (comp column second) arrows)
     :people (vec (distinct (map column (vals people))))}))

(defn- ringing-tables
  "Every table that rings: {table {:id col :scopes [cols] :people [cols]}}."
  [description {:keys [global personal]}]
  (merge
   (into {} (map (fn [[kind {t :table id :id}]]
                   [(name t) (assoc (columns-of description kind) :id (column id))]))
         (:records description))
   (into {} (map (fn [t] [(name t) {:id nil :scopes [] :people [] :global? true}])) global)
   (into {} (map (fn [[t col]] [(name t) {:id nil :scopes [] :people [(column col)]}])) personal)))

(defn- install-bells!
  "A trigger per table and kind of change, calling groundwork.ring. Safe to
   run again."
  [db tables]
  (pg/with-transaction [tx db]
    (doseq [[t {:keys [id scopes people]}] tables
            [op referencing] [["INSERT" "NEW TABLE AS new_rows"]
                              ["UPDATE" "NEW TABLE AS new_rows OLD TABLE AS old_rows"]
                              ["DELETE" "OLD TABLE AS old_rows"]]]
      (jdbc/execute! (::pg/conn tx)
                     [(str "CREATE OR REPLACE TRIGGER " "groundwork_ring_" (str/lower-case op)
                           " AFTER " op " ON " (rules/quote-table t)
                           " REFERENCING " referencing " FOR EACH STATEMENT"
                           " EXECUTE FUNCTION groundwork.ring('" (or id "") "', '"
                           (str/join "," scopes) "', '" (str/join "," people) "')")]))))

;; =============================================================================
;; Which tables a read uses
;; =============================================================================

(defn- connect
  "A connection of the bells' own, outside the pool."
  ^Connection [{:keys [host port database user password]} more]
  (let [props (doto (Properties.)
                (.setProperty "user" (str user))
                (.setProperty "ApplicationName" "groundwork-bells"))]
    (when password (.setProperty props "password" password))
    (doseq [[k v] more] (.setProperty props k v))
    (DriverManager/getConnection (connection/jdbc-url {:dbtype "postgresql" :host host :port port :dbname database})
                                 props)))

(defn- numbered
  "JDBC's ? as Postgres's $1, $2…; ?? stays a ?."
  [sql]
  (let [n (atom 0)]
    (str/replace sql #"\?\?|\?" (fn [m] (if (= "??" m) "?" (str "$" (swap! n inc)))))))

(defn- relations
  "Every table a plan reads."
  [plan]
  (let [found (atom #{})]
    (walk/postwalk (fn [x]
                             (when (and (map? x) (get x "Relation Name"))
                               (swap! found conj (get x "Relation Name")))
                             x)
                           plan)
    @found))

(defn- tables-of-file
  "The tables a SQL file reads, from Postgres's plan; nil when Postgres can't
   plan it without values."
  [{:keys [db-settings plans]} file]
  (let [sql   (pg/sql-text (pg/->Query file))
        known (get @plans file)]
    (if (and known (= sql (:sql known)))
      (:tables known)
      (let [tables (try
                     ;; the simple protocol sends $1 as text, for EXPLAIN to plan without values
                     (with-open [conn (connect db-settings {"preferQueryMode" "simple"})
                                 st   (.createStatement conn)]
                       (.execute st "SET search_path = public")
                       (with-open [rs (.executeQuery st (str "EXPLAIN (GENERIC_PLAN, FORMAT JSON) " (numbered sql)))]
                         (.next rs)
                         (relations (json/read-value (.getString rs 1)))))
                     (catch Exception e
                       (.log logger System$Logger$Level/DEBUG (str "No plan for " file ": " (ex-message e)))
                       nil))]
        (swap! plans assoc file {:sql sql :tables tables})
        tables))))

(defn- tables-of-endpoint
  "The tables a read endpoint uses, or nil when that isn't known (yet)."
  [{:keys [endpoints] :as part} endpoint]
  (let [files  (endpoints/files-of endpoints endpoint)
        labels (map name (:reads (endpoints/endpoint endpoints endpoint)))
        each   (map #(tables-of-file part %) files)]
    (when (and (seq files) (every? some? each))
      (into (set labels) cat each))))

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

(defn- watch!
  "A line's new list of watches, each checked like an ask."
  [{:keys [endpoints lines]} ch text]
  (when-let [{:keys [person flags]} (get @lines ch)]
    (let [message (try (wire/read-json text) (catch Exception _ nil))
          asks    (keep read-watch (:watch message))
          tables  (get-in endpoints [:perms :description :records])
          watches (vec (keep (fn [[endpoint params]]
                               (when-let [{:keys [scopes record]} (endpoints/watch endpoints {:endpoint endpoint :params params
                                                                                              :person person :flags flags})]
                                 {:ask          [(wire/endpoint-name endpoint) params]
                                  :endpoint     endpoint
                                  :scopes       (set (map str scopes))
                                  :record-table (some-> record first tables :table name)
                                  :record-id    (some-> record second str)}))
                             asks))]
      (swap! lines assoc-in [ch :watches] watches))))

(defn- rings?
  "Whether a bell concerns a watch."
  [part person {:keys [endpoint scopes record-table record-id]} {:keys [t s p i]}]
  (let [ringing (get (:tables part) t)
        tables  (tables-of-endpoint part endpoint)]
    (and (or (nil? tables) (contains? tables t))
         (or (:global? ringing)
             (nil? s)
             (seq (set/intersection scopes (set s)))
             (and person (or (nil? p) (some #{(str person)} p))))
         (or (not= t record-table) (nil? i) (some #{record-id} i)))))

(defn- ring! [{:keys [lines] :as part} bell]
  (doseq [[ch {:keys [person watches]}] @lines
          :let [changed (into [] (comp (filter #(rings? part person % bell)) (map :ask) (distinct)) watches)]
          :when (seq changed)]
    (send! ch {:changed changed})))

(defn- ring-everything! [{:keys [lines]}]
  (doseq [[ch {:keys [watches]}] @lines
          :when (seq watches)]
    (send! ch {:changed (into [] (comp (map :ask) (distinct)) watches)})))

;; =============================================================================
;; Listening
;; =============================================================================

(defn- listening-connection ^Connection [db-settings]
  (doto (connect db-settings {})
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
                           (try (ring! part (json/read-value (.getParameter n) json/keyword-keys-object-mapper))
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
          flags           (sessions/flags request)]
      (http/as-channel request
                       {:on-open    (fn [ch] (swap! lines assoc ch {:person person :flags flags :watches []}))
                        :on-receive (fn [ch text] (watch! bells ch text))
                        :on-close   (fn [ch _] (swap! lines dissoc ch))}))))

(defn start
  "The bells: triggers installed, a connection listening."
  [{:keys [endpoints db db-settings settings]}]
  (let [description (get-in endpoints [:perms :description])
        tables      (ringing-tables description (:bells settings))
        part        {:endpoints   endpoints
                     :db          db
                     :db-settings db-settings
                     :tables      tables
                     :plans       (atom {})
                     :lines       (atom {})
                     :stopped     (atom false)}]
    (install-bells! db tables)
    (assoc part :listener (Thread/startVirtualThread #(listen! part)))))

(defn stop
  "Stop listening and close every line."
  [{:keys [stopped listener lines]}]
  (when stopped
    (reset! stopped true)
    (doseq [ch (keys @lines)] (http/close ch))
    (.join ^Thread listener 3000)))
