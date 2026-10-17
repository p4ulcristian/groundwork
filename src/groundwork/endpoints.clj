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
                       verified ones), :request-id, :jobs, for
                       (jobs/enqueue! ctx …) in a write, and :request, the
                       Ring request the ask came in (for what Groundwork
                       doesn't carry: a header, the user agent); nil for a
                       tool's call. (:after-commit ctx) takes (fn [db] …),
                       run once the transaction has committed and not at all
                       after a rollback: work that may be lost with the
                       server (work that must happen is a job). A failure
                       there goes to :on-error and never changes the answer.
     :permission       :public, :signed-in, or an action:
                         {:action … :record [kind param]}  one record, its id in params
                         {:action … :scope [kind param]}   a scope, its id in params
                         {:action … :scope [kind #uuid \"…\"]}  one fixed scope, for an
                                                           app-wide area (an admin
                                                           area every member shares)
                         {:action … :list kind}            every record of a kind the
                                                           person may
                       A write declares an action that writes (inserts,
                       updates or deletes). An action a sentence gives
                       :anyone may be asked signed out; one it gives
                       :signed-in, by anyone signed in. Asked about a scope,
                       such an action verifies that scope for the request
                       and the wall shows what the sentences give there.
     :input            what may be sent, a malli :map. Every field holding ids
                       (a key named id, …-id or …-ids) says which kind of
                       record they are, {:groundwork/record :machine}, or that
                       they aren't one, {:groundwork/record false}; each id is
                       checked like the main record, so nothing from another
                       scope gets in. The permission's own param needs no mark.
     :answer           what may be sent back, a malli shape

   A visitor's record: an action a sentence gives a visitor ([:me <visitor
   column>]) may be asked by someone not signed in.
   ctx holds :visitor, the id of their visitor session or nil; the rows that
   name it are theirs, behind the wall. A write that keeps something for a
   visitor says :keeps-for-visitor true: asked without a session, the
   visitor gets one first (its cookie goes out with the answer), and
   (:visitor ctx) is its id. The id never comes from the request.

   Optional: :reads [:table …], tables a read uses that Postgres can't see
   in its SQL (a database function), for the bells; :rate-limit, how often a
   caller may ask it (see groundwork.limits). A public endpoint without one
   gets the app's :public limit.

   A read runs in a read-only transaction; a write in one transaction that
   commits only when everything succeeded, its answer fitting its shape
   included. A write's answer knows what it changed: the bells rung in its
   transaction (tables, scopes, ids) and the tag they carry, as the answer's
   metadata (bell); parquery's HTTP part turns them into the reads the asking
   browser shows that changed. Work an endpoint commits outside its own
   transaction (after-commit work, a job, an endpoint with its own
   transactions) isn't in it; that reaches screens through the websocket. Both see only the verified scopes (the database wall). A write
   carrying a one-time key runs once: a repeat with the same key gets the
   first answer back.

   An answer: {:status :ok :data …}, or :invalid (with :errors, from the input
   shape), :signed-out, :not-found (not allowed looks the same), :refused
   (with the app's own :message, see refuse), :too-many (with :limit,
   :per-minute or :per-day, and :retry-after-seconds; see groundwork.limits),
   :error (a plain message and the :request-id; the
   details go to the :on-error hook, or the log).

   Settings, the app's :parquery:

     :endpoints    [app.jobs/endpoints app.orders/endpoints …]   maps, gathered
     :max-asks     50       the most asks one request may carry
     :max-running  16       the most asks running at once, across the server;
                            keep it below the database pool's size
     :bearer       (fn [token] person-id-or-nil), for tools calling with a
                   token (see call-with-token), or nil
     :on-answer    (fn [{:keys [endpoint status ms person request-id]}] …)
                   after every ask over HTTP, for counters and alerts, or nil
     :bells        {:global {:table \"the reason\"} :personal {:table :entity/person-column}
                    :silent {:table \"the reason\"}}
                   how the tables outside the permission description ring:
                   global ones every watcher of them (a table whose rows
                   belong to nobody, or whose every row everyone reads, with
                   the reason said), personal ones only their person, silent
                   ones nothing, on purpose. A table whose rows belong to a
                   scope is in the permission description. Every table
                   of the app is in the description or here, or the start
                   refuses (see groundwork.parquery.websocket)"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.limits :as limits]
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as permissions]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings]
   [malli.core :as m])
  (:import
   (java.lang System$Logger System$Logger$Level)))

(pg/defsql claim-key "groundwork/endpoints/claim_key.sql")
(pg/defsql claimed-key "groundwork/endpoints/claimed_key.sql")
(pg/defsql keep-answer "groundwork/endpoints/keep_answer.sql")
(pg/defsql forget-old-keys "groundwork/endpoints/forget_old_keys.sql")
(pg/defsql grants-sql "groundwork/endpoints/grants.sql")
(pg/defsql remember-file "groundwork/endpoints/remember_file.sql")
(pg/defsql files-of-sql "groundwork/endpoints/files_of.sql")
(pg/defsql set-tag "groundwork/endpoints/set_tag.sql")
(pg/defsql rang-sql "groundwork/endpoints/rang.sql")

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
  (let [p (second (or record scope))]
    (when (keyword? p) p)))

(defn- as-id
  "An id as the database compares it: text that is a uuid becomes one (an
   input shape may take an id as a string), anything else stays as it is."
  [v]
  (or (when (string? v) (parse-uuid v)) v))

(defn- scope-of [{:keys [scope]} params]
  (let [[_ p] scope]
    (if (uuid? p) p (as-id (get params p)))))

(defn- public?
  "Whether an endpoint may be asked by nobody signed in: :public, or an
   action a sentence gives :anyone."
  [perms permission]
  (or (= :public permission)
      (and (map? permission) (= :anyone (permissions/open-level perms (:action permission))))))

(defn- open-only?
  "Whether every sentence giving an action gives it to someone who belongs
   nowhere (:anyone, :signed-in): it verifies only the scope a request names."
  [perms action]
  (let [ss (get-in perms [:by-action action])]
    (and (seq ss) (every? rules/open-word ss))))

(defn- check-permission [perms k permission input read?]
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
      (let [{:keys [action record scope list]} permission
            team? (= rules/team-action action)
            ss    (get-in perms [:by-action action])]
        (when (and team? (not (and scope (= :groundwork/any (first scope)))))
          (bad k (str rules/team-action " goes with {:scope [:groundwork/any param]}: it is held in any kind of scope")))
        (when-not (or team? (permissions/action-kind perms action))
          (bad k (str "no action " action " in the permission description")))
        (when (and (not read?) (not team?) (not (permissions/writes? perms action)))
          (bad k (str action " only reads; a write declares an action that inserts, updates or deletes"
                      " (its commands, in the scope kind's :actions)")))
        (when record
          (let [[kind param] record]
            (when-not (permissions/declared? perms action kind)
              (bad k (str "no sentence gives " action " on " kind)))
            (when-not (in-map? param)
              (bad k (str "the record's param " param " isn't in :input")))))
        (when scope
          (let [[kind param] scope]
            (when-not (or (scopes kind) (and team? (= :groundwork/any kind)))
              (bad k (str "no scope kind " kind)))
            (when-not (or (uuid? param) (in-map? param))
              (bad k (str "the scope's param " param " isn't in :input")))
            (when-not team?
              (when (empty? ss)
                (bad k (str "no sentence gives " action)))
              (when (open-only? perms action)
                (let [open-kinds (set (for [{s-kind :kind} ss
                                            v (vals (rules/scope-columns (get-in perms [:description :records s-kind])))]
                                        v))]
                  (when-not (open-kinds kind)
                    (bad k (str action " is open on rows of a " (str/join " or a " (map name (sort open-kinds)))
                                ", not in a " (name kind)))))))))
        (when list
          (when-not (kinds list)
            (bad k (str "no record kind " list)))
          (when-not (permissions/declared? perms action list)
            (bad k (str "no sentence gives " action " on " list)))
          (when (every? #(and (rules/open-word %) (not (:universal? %)))
                        (get-in perms [:by-action-kind [action list]]))
            (bad k (str action " is open on " list " only in the scope a request names: a :list of every scope's"
                        " has none to show; ask about a :scope or a :record"))))
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
                  (and (permissions/open-level perms (:action permission))
                       (not (permissions/declared? perms (:action permission) mark))
                       (not (permissions/via-kind? perms (:action permission) mark)))
                  (bad k (str "the field " key " holds ids of " mark ", and no sentence gives the open action "
                              (:action permission) " on it"))
                  :else                    (assoc found key mark))))
            {}
            (entries input))))

(defn- check-entry
  "An entry whose shape the settings schema has checked, against the
   permission description."
  [perms k entry]
  (settings/check-value! settings/endpoint entry [:parquery :endpoints k])
  (let [input      (shape k ":input" (:input entry))
        answer     (shape k ":answer" (:answer entry))
        _          (when-not (= :map (m/type input))
                     (bad k ":input must be a malli :map"))
        permission (check-permission perms k (:permission entry) input (contains? entry :read))]
    (assoc entry
           :name       k
           :input      input
           :answer     answer
           :permission permission
           :ids        (id-fields perms k permission input)
           :public?    (public? perms permission)
           ;; an action a sentence gives a visitor: asked signed out too
           :visitors?  (boolean (and (map? permission) (permissions/visitor-action? perms (:action permission))))
           :read?      (contains? entry :read)
           :handler    (or (:read entry) (:write entry)))))

(defn- own-transaction?
  "Groundwork's team endpoints (groundwork.team) open their own transactions
   after their own checks, outside the wall."
  [entry]
  (boolean (::own-transaction (meta entry))))

(defn- built-in
  "Groundwork's own endpoints. :groundwork/grants is behind the browser's
   \"can I?\": every action the sentences give the person, and in which
   scopes (none: every scope)."
  [perms]
  (let [description (:description perms)]
    {:groundwork/grants (with-meta
                          {:read       (fn [{:keys [perms person visitor flags]} _]
                                         (permissions/grants perms {:person person :visitor visitor :flags flags}))
                           :permission :signed-in
                           :input      [:map]
                           :answer     [:vector [:map [:grant/scope-id [:maybe :uuid]] [:grant/action :keyword]]]
                           ;; it runs past the wall, through functions: these are what it reads
                           :reads      (vec (concat [:groundwork.memberships :groundwork.role_actions
                                                     :groundwork.extra_actions :groundwork.roles :groundwork.scopes]
                                                    (map keyword (keys (rules/via-tables description)))
                                                    (distinct (for [{:keys [scope]} (rules/denies description)
                                                                    :when scope]
                                                                (keyword (name (get-in description [:scopes scope :table])))))))}
                          {::own-transaction true})}))

(defn- gather
  "The app's endpoint maps as one, refusing a name twice."
  [maps]
  (reduce (fn [all m]
            (when-let [twice (seq (filter #(contains? all %) (keys m)))]
              (throw (ex-info (str "Endpoints named twice: " (str/join " " twice))
                              {:type ::bad-endpoint :endpoints (vec twice)})))
            (merge all m))
          {}
          maps))

;; =============================================================================
;; Settings
;; =============================================================================

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
                         (vswap! found update kind (fnil into []) (map as-id (if (coll? v) v [v])))))
                     x)
                   params)
    @found))

(defn- also-belongs!
  "Every other id must belong to one of the verified scopes."
  [perms scopes also]
  (doseq [[kind ids] also
          id         ids]
    (when (and (not= :every scopes)
               (empty? (set/intersection scopes (permissions/scopes-of perms [kind id]))))
      (throw (ex-info "Not found" {:type ::permissions/not-found :record [kind id]})))))

(defn- verified-scopes
  "What a request may see and write, or throws not-found: {:read scopes
   :write scopes :open? bool}, scopes a set or :every (a request a fixed
   scope's member makes about every scope). :open? when it was verified
   only because a sentence opens the action to someone who belongs
   nowhere."
  [perms {:keys [permission ids]} {:keys [person visitor flags]} params]
  (let [also (ids-in ids params)]
    (case permission
      (:public :signed-in) {:read #{} :write #{}}
      (let [{:keys [action record scope list]} permission
            open? (some? (permissions/open-level perms action))]
        ;; an open action's other ids are each held to its sentences, not
        ;; only to its scope: an id it doesn't show answers like one that isn't there
        (when open?
          (doseq [[kind ids] also
                  id         ids]
            (when-not (let [request {:person person :visitor visitor :flags flags :action action :record [kind id]}]
                        (or (permissions/allowed? perms request) (permissions/via-allows? perms request)))
              (throw (ex-info "Not found" {:type ::permissions/not-found :record [kind id]})))))
        (cond
          record (let [scopes (permissions/check! perms {:person person :visitor visitor :flags flags :action action
                                                         :record [(first record) (as-id (get params (second record)))]
                                                         :also   also})]
                   {:read scopes :write scopes})
          scope  (let [id      (scope-of permission params)
                       held    (permissions/scopes-under perms {:person person :visitor visitor :flags flags
                                                                :action action :scope id})
                       open    (when open?
                                 (permissions/open-scope perms {:person person :flags flags :action action :scope id
                                                                :kind (first scope)}))
                       found   (into (set held) open)
                       own     (set (filter #{id} found))]
                   (when (empty? found)
                     (throw (ex-info "Not found" {:type ::permissions/not-found :scope scope})))
                   (let [{:keys [read write]} (permissions/with-denies perms {:action action :own own
                                                                               :more (remove own found)})]
                     (also-belongs! perms read also)
                     {:read read :write write :open? (empty? held)}))
          list   (let [{:keys [scopes every?]} (permissions/scopes-listed perms {:person person :visitor visitor
                                                                                 :flags flags :action action :kind list})
                       {:keys [read write]}    (permissions/with-denies perms {:action action :own #{}
                                                                               :more (if every? :every scopes)})]
                   (also-belongs! perms read also)
                   {:read read :write write}))))))

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

(defn- rang
  "The bells rung in this transaction: :any, or the bells as maps, or nil."
  [tx]
  (let [text (:bell/rang (pg/row tx rang-sql))]
    (cond
      (str/blank? text) nil
      (= "any" text)    :any
      :else             (mapv wire/read-json (str/split-lines text)))))

(defn bell
  "What a write's answer knows of what it changed, or nil:
   {:tag … :rang [{:t table :s scopes :p people :i ids} …] or :any}."
  [answer]
  (::bell (meta answer)))

(defn- collecting
  "An on-query hook that notes each SQL file a read runs, and calls the
   app's own hook too."
  [files hook]
  (fn [{:keys [file] :as q}]
    (when file (swap! files conj file))
    (when hook (hook q))))

(defn- remember-files!
  "Note which SQL files a read endpoint ran, here and in the database, so
   every server knows which tables its watchers wait for."
  [{:keys [db known-files]} endpoint files]
  (doseq [file files
          :when (not (contains? (get @known-files endpoint) file))]
    (pg/execute! db remember-file {:endpoint-file/endpoint (wire/endpoint-name endpoint) :endpoint-file/file file})
    (swap! known-files update endpoint (fnil conj #{}) file)))

(defn files-of
  "The SQL files a read endpoint has run, on any server: what its watchers
   wait for. Empty when it has never run."
  [{:keys [db known-files]} endpoint]
  (or (not-empty (get @known-files endpoint))
      (let [files (into #{} (map :endpoint-file/file)
                        (pg/rows db files-of-sql {:endpoint-file/endpoint (wire/endpoint-name endpoint)}))]
        (when (seq files) (swap! known-files assoc endpoint files))
        files)))

(defn- failed [{:keys [on-error]} e about]
  (if on-error
    (try (on-error (assoc about :error e))
         (catch Throwable hook-error
           (.log logger System$Logger$Level/WARNING "The on-error hook threw" hook-error)))
    (.log logger System$Logger$Level/ERROR (str "Endpoint " (:endpoint about) " failed") e))
  (answer :error :message "Something went wrong. Please try again." :request-id (:request-id about)))

(defn- run-after-commit!
  "The work registered with :after-commit, once the transaction committed."
  [part thunks about]
  (doseq [f thunks]
    (try (f (:db part))
         (catch Throwable e
           (failed part e (assoc about :after-commit true))))))

(defn call
  "Run one ask for a person: {:endpoint :params :key :person :flags
   :request-id :client-ip}, or for someone not signed in, :visitor, the id
   of their visitor session. Returns the answer; never throws for the ask's
   own reasons."
  [{:keys [perms endpoints limits] :as part} {:keys [endpoint params person flags request-id client-ip] :as ask}]
  (let [entry   (get endpoints endpoint)
        ;; a visitor is someone not signed in
        visitor (when (nil? person) (:visitor ask))
        ask     (assoc ask :visitor visitor)
        flags   (or flags #{})
        after (atom [])
        ask   (assoc ask :after-commit (fn [f] (swap! after conj f) nil))
        about {:endpoint endpoint :person person :request-id request-id :client-ip client-ip}]
    (if (nil? entry)
      (answer :not-found)
      (let [{decoded :value errors :errors} (wire/decode (:input entry) (or params {}))]
        (cond
          errors
          (answer :invalid :errors errors)

          (and (nil? person) (not (:public? entry)) (not (:visitors? entry)))
          (answer :signed-out)

          :else
          (try
            (let [{:keys [read write]} (verified-scopes perms entry {:person person :visitor visitor :flags flags} decoded)
                  ;; ctx's :scopes is a set; a request that reaches every scope says so in :every-scope?
                  scopes                 (if (= :every read) #{} read)
                  every?                 (= :every read)]
              (if-let [{:keys [limit retry-after-seconds]}
                       (when limits
                         (limits/take! limits {:id       endpoint
                                               :endpoint endpoint
                                               :limit    (limits/limit-of limits {:own     (:rate-limit entry)
                                                                                  :public? (or (:public? entry)
                                                                                               (and (nil? person) (:visitors? entry)))
                                                                                  :read?   (:read? entry)})
                                               :about    {:person person :scopes scopes :client-ip client-ip}}))]
                (answer :too-many :limit limit :retry-after-seconds retry-after-seconds)
                ;; a write that keeps something for a visitor: someone with no
                ;; session gets one now, once the ask is past its limit
                (let [visitor (or visitor
                                  (when (and (nil? person) (:keeps-for-visitor entry) (:make-visitor ask))
                                    ((:make-visitor ask))))
                      ask     (-> ask (assoc :visitor visitor) (dissoc :make-visitor))]
                (if (own-transaction? entry)
                  (let [data ((:handler entry) (assoc ask :perms perms :scopes scopes :every-scope? every?
                                                      :flags flags :params decoded)
                                               decoded)]
                    (when-not (wire/fits? (:answer entry) data)
                      (throw (ex-info (str "Endpoint " endpoint "'s answer doesn't fit its shape")
                                      {:type ::bad-answer :endpoint endpoint :errors (wire/explain (:answer entry) data)})))
                    (run-after-commit! part @after about)
                    (answer :ok :data data))
                (let [files (atom #{})
                      tag   (when-not (:read? entry) (str (random-uuid)))
                      [data rang]
                      (permissions/in-wall
                       perms {:person person :visitor visitor :scopes read :write-scopes write :flags flags
                              :read-only? (:read? entry)}
                       (fn [tx]
                         (let [tx  (cond-> tx (:read? entry) (assoc ::pg/on-query (collecting files (::pg/on-query tx))))
                               ctx (assoc ask :db tx :scopes scopes :every-scope? every? :flags flags :params decoded
                                          :jobs (:jobs part))
                               run (fn []
                                     (let [data ((:handler entry) ctx decoded)]
                                       (when-not (wire/fits? (:answer entry) data)
                                         (throw (ex-info (str "Endpoint " endpoint "'s answer doesn't fit its shape")
                                                         {:type ::bad-answer :endpoint endpoint
                                                          :errors (wire/explain (:answer entry) data)})))
                                       data))]
                           (if (:read? entry)
                             [(run) nil]
                             (do (pg/execute! tx set-tag {:tag tag})
                                 (let [data (run-write-once entry tx ask run)]
                                   [data (rang tx)]))))))]
                  (when (:read? entry) (remember-files! part endpoint @files))
                  (run-after-commit! part @after about)
                  (cond-> (answer :ok :data data)
                    rang (with-meta {::bell {:tag tag :rang rang}})))))))
            (catch clojure.lang.ExceptionInfo e
              (case (:type (ex-data e))
                ::permissions/not-found (answer :not-found)
                (::refused ::permissions/refused ::permissions/unknown-role ::permissions/unknown-action)
                (answer :refused :message (ex-message e))
                (failed part e about)))
            (catch Throwable e
              (failed part e about))))))))

(defn watch
  "What a person watching a read may be told about, or nil when they may
   not ask it: {:scopes set-or-:every :record [kind id]-or-nil :tables
   set-or-nil}. The same check as asking. :tables, for a read verified only
   because its action is open to someone who belongs nowhere: the only
   tables whose bells its scope brings."
  [{:keys [perms endpoints]} {:keys [endpoint params person visitor flags]}]
  (let [entry   (get endpoints endpoint)
        visitor (when (nil? person) visitor)]
    (when (and entry (:read? entry) (or person (:public? entry) (:visitors? entry)))
      (let [{decoded :value} (wire/decode (:input entry) (or params {}))]
        (when decoded
          (try
            (let [{:keys [read open?]} (verified-scopes perms entry {:person person :visitor visitor
                                                                      :flags (or flags #{})}
                                                         decoded)]
              {:scopes read
               :record (when-let [[kind param] (get-in entry [:permission :record])]
                         [kind (get decoded param)])
               ;; someone who belongs nowhere hears of the scope only through
               ;; the tables it shows them
               :tables (when open? (permissions/open-tables perms))})
            (catch clojure.lang.ExceptionInfo e
              (when-not (= ::permissions/not-found (:type (ex-data e)))
                (throw e)))))))))

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
  [{:keys [settings db permissions limits jobs app-settings]}]
  (settings/check-part! :parquery settings)
  (let [app       (gather (:endpoints settings))
        _         (when-let [taken (seq (filter #(= "groundwork" (namespace %)) (keys app)))]
                    (throw (ex-info (str "Endpoint names under groundwork/ are Groundwork's: " (str/join " " taken))
                                    {:type ::bad-endpoint :endpoints (vec taken)})))
        endpoints (into {}
                        (map (fn [[k entry]] [k (check-entry permissions k entry)]))
                        (merge app (built-in permissions)))]
    (pg/execute! db forget-old-keys)
    {:db          db
     :known-files (atom {})
     :perms       permissions
     :endpoints   endpoints
     :settings    settings
     :limits      limits
     :jobs        jobs
     :bearer      (:bearer settings)
     :on-error    (get-in app-settings [:web :on-error])}))
