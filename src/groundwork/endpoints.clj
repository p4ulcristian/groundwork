(ns groundwork.endpoints
  "The endpoints: everything the browser, or a tool, can ask the server. The
   app lists them, gathered from its areas; a name in two areas stops the
   start. parquery carries the asks here; this namespace checks and runs them.

     {:jobs/list {:read       #'jobs/list          ; (fn [ctx params] answer)
                  :permission {:action :jobs/see :scope [:shop :shop/id]}
                  :input      [:map [:shop/id :uuid]]
                  :answer     [:vector job]}
      :jobs/move {:write      #'jobs/move
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :uuid]
                                    [:machine/id {:groundwork/record :machine} :uuid]
                                    [:job/stage :keyword]]
                  :answer     job}}

   Every endpoint declares, or the app doesn't start:

     :read or :write   its handler, (fn [ctx params] answer). ctx holds :db
                       (the transaction), :person, :flags, :scopes (the
                       verified ones) and :request-id.
     :permission       :public, :signed-in, or an action:
                         {:action … :record [kind param]}  one record, its id in params
                         {:action … :scope [kind param]}   a scope, its id in params
                         {:action … :list kind}            every record of a kind the
                                                           person may
     :input            what may be sent, a malli :map. Every field holding ids
                       (a key named id, …-id or …-ids) says which kind of
                       record they are, {:groundwork/record :machine}, or that
                       they aren't one, {:groundwork/record false}; each id is
                       checked like the main record, so nothing from another
                       scope gets in. The permission's own param needs no mark.
     :answer           what may be sent back, a malli shape

   Optional: :reads [:table …], tables a read uses that Postgres can't see
   in its SQL (a database function), for the bells.

   A read runs in a read-only transaction; a write in one transaction that
   commits only when everything succeeded, its answer fitting its shape
   included. Both see only the verified scopes (the database wall). A write
   carrying a one-time key runs once: a repeat with the same key gets the
   first answer back.

   An answer: {:status :ok :data …}, or :invalid (with :errors, from the input
   shape), :signed-out, :not-found (not allowed looks the same), :refused
   (with the app's own :message, see refuse), :too-many (with
   :retry-after-seconds), :error (a plain message and the :request-id; the
   details go to the :on-error hook, or the log).

   Settings, the app's :parquery:

     :endpoints    [app.jobs/endpoints app.orders/endpoints …]   maps, gathered
     :max-asks     50       the most asks one request may carry
     :max-running  16       the most asks running at once, across the server;
                            keep it below the database pool's size
     :bearer       (fn [token] person-id-or-nil), for tools calling with a
                   token (see call-with-token), or nil
     :rate-limit   (fn [{:keys [endpoint person client-ip]}] nil-or-{:retry-after-seconds n}),
                   or nil
     :bells        {:global #{:table …} :personal {:table :entity/person-column}}
                   tables outside the permission description that ring too:
                   global ones every watcher of them, personal ones only
                   their person (see groundwork.parquery.websocket)"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as permissions]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [malli.core :as m])
  (:import
   (java.lang System$Logger System$Logger$Level)))

(pg/defsql claim-key "groundwork/endpoints/claim_key.sql")
(pg/defsql claimed-key "groundwork/endpoints/claimed_key.sql")
(pg/defsql keep-answer "groundwork/endpoints/keep_answer.sql")
(pg/defsql forget-old-keys "groundwork/endpoints/forget_old_keys.sql")
(pg/defsql grants-sql "groundwork/endpoints/grants.sql")

(def ^:private ^System$Logger logger (System/getLogger "groundwork.endpoints"))

(defn refuse
  "An exception whose message the person may read, for a business rule:
   (throw (endpoints/refuse \"This part has no price\")). The write rolls
   back; the answer is :refused with this message."
  ([message] (refuse message {}))
  ([message data]
   (ex-info message (assoc data :type ::refused))))

;; =============================================================================
;; Checking the list
;; =============================================================================

(defn- bad [k message]
  (throw (ex-info (str "Endpoint " k ": " message) {:type ::bad-endpoint :endpoint k})))

(defn- shape [k what s]
  (try (wire/schema s)
       (catch Exception e
         (bad k (str what " isn't a malli shape: " (ex-message e))))))

(defn- entries
  "Every map entry of a shape, at any depth: [key properties child]."
  [s]
  (let [kids (m/children s)]
    (if (= :map (m/type s))
      (mapcat (fn [[k props child]] (cons [k props child] (entries child))) kids)
      (mapcat (fn [c] (when (m/schema? c) (entries c))) kids))))

(defn- id-key? [k]
  (and (keyword? k) (boolean (re-find #"(^|-)ids?$" (name k)))))

(defn- permission-param [{:keys [record scope]}]
  (second (or record scope)))

(defn- check-permission [perms k permission input]
  (let [kinds   (set (keys (get-in perms [:description :records])))
        scopes  (set (keys (get-in perms [:description :scopes])))
        in-map? (fn [param] (some #(= param (first %)) (m/children input)))]
    (cond
      (#{:public :signed-in} permission) permission

      (not (and (map? permission) (keyword? (:action permission))
                (= 2 (count permission))
                (some #{:record :scope :list} (keys permission))))
      (bad k ":permission must be :public, :signed-in, {:action … :record [kind param]}, {:action … :scope [kind param]} or {:action … :list kind}")

      :else
      (let [{:keys [action record scope list]} permission]
        (when-not (permissions/action-kind perms action)
          (bad k (str "no action " action " in the permission description")))
        (when record
          (let [[kind param] record]
            (when-not (permissions/declared? perms action kind)
              (bad k (str "no permission rule for " action " on " kind)))
            (when-not (in-map? param)
              (bad k (str "the record's param " param " isn't in :input")))))
        (when scope
          (let [[kind param] scope]
            (when-not (scopes kind)
              (bad k (str "no scope kind " kind)))
            (when-not (in-map? param)
              (bad k (str "the scope's param " param " isn't in :input")))))
        (when list
          (when-not (kinds list)
            (bad k (str "no record kind " list)))
          (when-not (permissions/declared? perms action list)
            (bad k (str "no permission rule for " action " on " list))))
        permission))))

(defn- id-fields
  "The fields of the input holding ids of records, {key kind}; throws on an
   id field that isn't marked."
  [perms k permission input]
  (let [kinds (set (keys (get-in perms [:description :records])))
        own   (permission-param permission)]
    (reduce (fn [found [key props _]]
              (let [mark (get props :groundwork/record ::unmarked)]
                (cond
                  (= key own)              found
                  (false? mark)            found
                  (= ::unmarked mark)      (if (id-key? key)
                                             (bad k (str "the field " key " holds ids: mark it {:groundwork/record <kind>}, "
                                                         "or {:groundwork/record false} when it isn't a record's"))
                                             found)
                  (not (kinds mark))       (bad k (str "the field " key " names an unknown record kind " mark))
                  (and (found key) (not= mark (found key)))
                  (bad k (str "the field " key " is marked as two kinds"))
                  (#{:public :signed-in} permission)
                  (bad k (str "the field " key " holds ids, so the endpoint needs an action permission"))
                  :else                    (assoc found key mark))))
            {}
            (entries input))))

(defn- check-entry [perms k entry]
  (let [allowed #{:read :write :permission :input :answer :reads}]
    (when-not (qualified-keyword? k)
      (bad k "the name must be namespaced, like :jobs/list"))
    (when-let [unknown (seq (remove allowed (keys entry)))]
      (bad k (str "unknown keys " (str/join " " unknown))))
    (when-let [missing (seq (remove #(contains? entry %) [:permission :input :answer]))]
      (bad k (str "needs " (str/join " " missing))))
    (when-not (= 1 (count (filter #(contains? entry %) [:read :write])))
      (bad k "needs one of :read or :write, its handler"))
    (when-not (ifn? (or (:read entry) (:write entry)))
      (bad k "its handler must be a function"))
    (when-not (or (nil? (:reads entry)) (and (sequential? (:reads entry)) (every? keyword? (:reads entry))))
      (bad k ":reads must be a list of tables, like [:jobs]"))
    (let [input      (shape k ":input" (:input entry))
          answer     (shape k ":answer" (:answer entry))
          _          (when-not (= :map (m/type input))
                       (bad k ":input must be a malli :map"))
          permission (check-permission perms k (:permission entry) input)]
      (assoc entry
             :name       k
             :input      input
             :answer     answer
             :permission permission
             :ids        (id-fields perms k permission input)
             :read?      (contains? entry :read)
             :handler    (or (:read entry) (:write entry))))))

(defn- built-in
  "Groundwork's own endpoints. :groundwork/grants is behind the browser's
   \"can I?\": every action the person holds, and in which scopes."
  [perms]
  (let [actions (mapv #(subs (str %) 1)
                      (conj (keys (rules/action-kinds (:description perms))) rules/team-action))]
    {:groundwork/grants {:read       (fn [{:keys [db person]} _]
                                       (mapv (fn [{:grant/keys [scope-id action]}]
                                               {:grant/scope-id scope-id :grant/action (keyword action)})
                                             (pg/rows db grants-sql {:actions actions :person (str person)})))
                         :permission :signed-in
                         :input      [:map]
                         :answer     [:vector [:map [:grant/scope-id :uuid] [:grant/action :keyword]]]}}))

(defn- gather
  "The app's endpoint maps as one, refusing a name twice."
  [maps]
  (reduce (fn [all m]
            (when-not (map? m)
              (throw (ex-info ":endpoints must be a list of maps" {:type ::bad-setting :setting :endpoints})))
            (when-let [twice (seq (filter #(contains? all %) (keys m)))]
              (throw (ex-info (str "Endpoints named twice: " (str/join " " twice))
                              {:type ::bad-endpoint :endpoints (vec twice)})))
            (merge all m))
          {}
          maps))

;; =============================================================================
;; Settings
;; =============================================================================

(def ^:private settings [:endpoints :max-asks :max-running :bearer :rate-limit :bells])

(defn- check-bells [{:keys [global personal] :as bells}]
  (when-not (and (map? bells) (= #{:global :personal} (set (keys bells)))
                 (set? global) (every? keyword? global)
                 (map? personal) (every? keyword? (keys personal)) (every? qualified-keyword? (vals personal)))
    (throw (ex-info ":bells must be {:global #{:table …} :personal {:table :entity/person-column}}"
                    {:type ::bad-setting :setting :bells :value bells}))))

(defn- check-settings [opts]
  (when-not (map? opts)
    (throw (ex-info ":parquery must be a map" {:type ::bad-setting :setting :parquery :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str ":parquery needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (let [{:keys [endpoints max-asks max-running bearer rate-limit bells]} opts]
    (when-not (sequential? endpoints)
      (throw (ex-info ":endpoints must be a list of the app's endpoint maps"
                      {:type ::bad-setting :setting :endpoints})))
    (doseq [[k v] [[:max-asks max-asks] [:max-running max-running]]]
      (when-not (pos-int? v)
        (throw (ex-info (str k " must be a positive integer") {:type ::bad-setting :setting k :value v}))))
    (doseq [[k v] [[:bearer bearer] [:rate-limit rate-limit]]]
      (when-not (or (nil? v) (ifn? v))
        (throw (ex-info (str k " must be a function or nil") {:type ::bad-setting :setting k :value v}))))
    (check-bells bells)))

;; =============================================================================
;; Running one
;; =============================================================================

(defn- answer [status & {:as more}]
  (merge {:status status} more))

(defn- ids-in
  "Every id in the params under the marked fields, {kind [ids]}."
  [fields params]
  (let [found (volatile! {})]
    (walk/postwalk (fn [x]
                     (when (map? x)
                       (doseq [[key kind] fields
                               :let [v (get x key)]
                               :when (some? v)]
                         (vswap! found update kind (fnil into []) (if (coll? v) v [v]))))
                     x)
                   params)
    @found))

(defn- also-belongs!
  "Every other id must belong to one of the verified scopes."
  [perms scopes also]
  (doseq [[kind ids] also
          id         ids]
    (when (empty? (set/intersection scopes (permissions/scopes-of perms [kind id])))
      (throw (ex-info "Not found" {:type ::permissions/not-found :record [kind id]})))))

(defn- verified-scopes
  "The scopes a request may see, or throws not-found."
  [perms {:keys [permission ids]} {:keys [person flags]} params]
  (let [also (ids-in ids params)]
    (case permission
      (:public :signed-in) #{}
      (let [{:keys [action record scope list]} permission]
        (cond
          record (permissions/check! perms {:person person :flags flags :action action
                                            :record [(first record) (get params (second record))]
                                            :also   also})
          scope  (let [scopes (permissions/scopes-under perms {:person person :flags flags :action action
                                                               :scope  (get params (second scope))})]
                   (when (empty? scopes)
                     (throw (ex-info "Not found" {:type ::permissions/not-found :scope scope})))
                   (also-belongs! perms scopes also)
                   scopes)
          list   (let [scopes (permissions/scopes-listed perms {:person person :flags flags :action action :kind list})]
                   (also-belongs! perms scopes also)
                   scopes))))))

(defn- run-write-once
  "Run a write's handler once per key: a key seen before gives its answer."
  [{:keys [name answer]} tx {:keys [key person]} run]
  (if (nil? key)
    (run)
    (if (pg/row tx claim-key {:run-once/key key :run-once/person-id person :run-once/endpoint (wire/endpoint-name name)})
      (let [data (run)]
        (pg/execute! tx keep-answer {:run-once/key key
                                     :run-once/answer (wire/write-json (wire/encode answer data))})
        data)
      (let [{:run-once/keys [person-id endpoint] stored :run-once/answer} (pg/row tx claimed-key {:run-once/key key})]
        (when-not (and (= person-id person) (= endpoint (wire/endpoint-name name)) stored)
          (throw (refuse "This key was used for something else")))
        (:value (wire/decode answer (wire/read-json stored)))))))

(defn- failed [{:keys [on-error]} e about]
  (if on-error
    (try (on-error (assoc about :error e))
         (catch Throwable hook-error
           (.log logger System$Logger$Level/WARNING "The on-error hook threw" hook-error)))
    (.log logger System$Logger$Level/ERROR (str "Endpoint " (:endpoint about) " failed") e))
  (answer :error :message "Something went wrong. Please try again." :request-id (:request-id about)))

(defn call
  "Run one ask for a person: {:endpoint :params :key :person :flags
   :request-id :client-ip}. Returns the answer; never throws for the ask's
   own reasons."
  [{:keys [perms endpoints rate-limit] :as part} {:keys [endpoint params person flags request-id client-ip] :as ask}]
  (let [entry (get endpoints endpoint)
        flags (or flags #{})]
    (if (nil? entry)
      (answer :not-found)
      (let [{decoded :value errors :errors} (wire/decode (:input entry) (or params {}))]
        (cond
          errors
          (answer :invalid :errors errors)

          (and (nil? person) (not= :public (:permission entry)))
          (answer :signed-out)

          :else
          (try
            (let [scopes (verified-scopes perms entry {:person person :flags flags} decoded)]
              (if-let [{:keys [retry-after-seconds]}
                       (when rate-limit (rate-limit {:endpoint endpoint :person person :client-ip client-ip}))]
                (answer :too-many :retry-after-seconds retry-after-seconds)
                (let [data (permissions/in-wall
                            perms {:person person :scopes scopes :read-only? (:read? entry)}
                            (fn [tx]
                              (let [ctx (assoc ask :db tx :scopes scopes :flags flags :params decoded)
                                    run (fn []
                                          (let [data ((:handler entry) ctx decoded)]
                                            (when-not (wire/fits? (:answer entry) data)
                                              (throw (ex-info (str "Endpoint " endpoint "'s answer doesn't fit its shape")
                                                              {:type ::bad-answer :endpoint endpoint
                                                               :errors (wire/explain (:answer entry) data)})))
                                            data))]
                                (if (:read? entry) (run) (run-write-once entry tx ask run)))))]
                  (answer :ok :data data))))
            (catch clojure.lang.ExceptionInfo e
              (case (:type (ex-data e))
                ::permissions/not-found (answer :not-found)
                ::refused               (answer :refused :message (ex-message e))
                (failed part e {:endpoint endpoint :person person :request-id request-id :client-ip client-ip})))
            (catch Throwable e
              (failed part e {:endpoint endpoint :person person :request-id request-id :client-ip client-ip}))))))))

(defn call-with-token
  "Run one ask for a tool calling with a token instead of a browser session:
   the :bearer setting says whose token it is. An unknown token is
   :signed-out."
  [{:keys [bearer] :as part} token ask]
  (when-not bearer
    (throw (ex-info "Calling with a token needs the :bearer setting" {:type ::no-bearer})))
  (call part (assoc ask :person (when (string? token) (bearer token)) :flags #{})))

(defn endpoint
  "One endpoint, as checked at start, or nil."
  [part k]
  (get-in part [:endpoints k]))

(defn encode-answer
  "An answer made ready for JSON: its data by the endpoint's answer shape."
  [part k {:keys [status data] :as a}]
  (if (= :ok status)
    (assoc a :data (wire/encode (:answer (endpoint part k)) data))
    a))

;; =============================================================================
;; The part
;; =============================================================================

(defn start
  "The endpoints part: every endpoint checked against the permission
   description; old one-time keys forgotten."
  [{:keys [settings db permissions app-settings]}]
  (check-settings settings)
  (let [app       (gather (:endpoints settings))
        _         (when-let [taken (seq (filter #(= "groundwork" (namespace %)) (keys app)))]
                    (throw (ex-info (str "Endpoint names under groundwork/ are Groundwork's: " (str/join " " taken))
                                    {:type ::bad-endpoint :endpoints (vec taken)})))
        endpoints (into {}
                        (map (fn [[k entry]] [k (check-entry permissions k entry)]))
                        (merge app (built-in permissions)))]
    (pg/execute! db forget-old-keys)
    {:db          db
     :perms       permissions
     :endpoints   endpoints
     :settings    settings
     :rate-limit  (:rate-limit settings)
     :bearer      (:bearer settings)
     :on-error    (get-in app-settings [:web :on-error])}))
