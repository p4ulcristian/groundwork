(ns groundwork.permissions
  "Who may do what, on which record. The app describes its scopes, actions,
   records and its rules, one list of sentences (see
   groundwork.permissions.rules); Groundwork keeps scopes, roles, memberships,
   extra actions and invitations in its own tables, checks the description
   against the database at start, and turns each sentence into one SQL
   condition.

     (allowed? perms {:person p :flags #{} :action :jobs/move :record [:job id]})
     (check! perms {… :also {:machine [machine-id]}})   ; every id in the request
     (list-filter perms {:person p :flags #{} :action :orders/see :kind :order})

   A record is checked through its own columns, never a scope the browser
   names; a record that isn't the person's answers not-found.

   The database wall: at start, every record kind's table gets row-level
   security, a policy per command made from the sentences. A transaction
   sees only rows of the scopes verified for it (in-wall), as the sentences
   allow, or every row when it runs as the system (as-system). Both last one
   transaction and can't be nested, so neither leaks into the next.

   Grants and roles live in Postgres and change at once; nothing is cached."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [groundwork.postgres.names :as names]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(pg/defsql set-bypass "groundwork/permissions/set_bypass.sql")
(pg/defsql set-wall "groundwork/permissions/set_wall.sql")
(pg/defsql set-member-scopes "groundwork/permissions/set_member_scopes.sql")
(pg/defsql columns-of "groundwork/permissions/columns_of.sql")
(pg/defsql indexes-of "groundwork/permissions/indexes_of.sql")
(pg/defsql groundwork-policies "groundwork/permissions/groundwork_policies.sql")
(pg/defsql groundwork-guards "groundwork/permissions/groundwork_guards.sql")
(pg/defsql groundwork-functions "groundwork/permissions/groundwork_functions.sql")
(pg/defsql links-between "groundwork/permissions/links_between.sql")
(pg/defsql connecting-role "groundwork/permissions/connecting_role.sql")
(pg/defsql holds-sql "groundwork/permissions/holds.sql")
(pg/defsql scope-of-kind "groundwork/permissions/scope_of_kind.sql")
(pg/defsql count-of-kind "groundwork/permissions/count_of_kind.sql")
(pg/defsql insert-scope "groundwork/permissions/insert_scope.sql")
(pg/defsql insert-role "groundwork/permissions/insert_role.sql")
(pg/defsql insert-role-action "groundwork/permissions/insert_role_action.sql")
(pg/defsql delete-role-actions "groundwork/permissions/delete_role_actions.sql")
(pg/defsql role-by-name "groundwork/permissions/role_by_name.sql")
(pg/defsql membership "groundwork/permissions/membership.sql")
(pg/defsql upsert-membership "groundwork/permissions/upsert_membership.sql")
(pg/defsql delete-membership "groundwork/permissions/delete_membership.sql")
(pg/defsql count-owners "groundwork/permissions/count_owners.sql")
(pg/defsql insert-extra-action "groundwork/permissions/insert_extra_action.sql")
(pg/defsql delete-extra-action "groundwork/permissions/delete_extra_action.sql")
(pg/defsql insert-invitation "groundwork/permissions/insert_invitation.sql")
(pg/defsql take-invitation "groundwork/permissions/take_invitation.sql")

(def owner-role "Owner")

(defn- not-found [what]
  (ex-info "Not found" (assoc what :type ::not-found)))

(defn- refused [message what]
  (ex-info message (assoc what :type ::refused)))

(defn- uuid-array
  "Scope ids as Postgres's text for uuid[]."
  [ids]
  (str "{" (str/join "," (map str ids)) "}"))

;; =============================================================================
;; Transactions: the system, and the wall
;; =============================================================================

(defn- own-transaction [{:keys [db]} opts f]
  (when (::pg/tx-opts db)
    (throw (ex-info "Permissions open their own transaction; this one can't run inside another"
                    {:type ::nested-transaction})))
  (pg/transact db opts f))

(defn as-system
  "Run (f tx) in its own transaction where the wall lets every row through.
   It ends with the transaction, and can't be called inside another one."
  [perms f]
  (own-transaction perms {} (fn [tx] (pg/execute! tx set-bypass) (f tx))))

(defn- scopes-text [scopes]
  (if (= :every scopes) "*" (uuid-array scopes)))

(defn in-wall
  "Run (f tx) in its own transaction behind the wall: rows show as the
   sentences allow, in `scopes` (the ones verified for this request, or
   :every for a request that reaches every scope), and a person's own rows.
   It writes only in :write-scopes (the verified ones but those a scope's
   state denies; `scopes` unless said). With :read-only? true, it can't
   change anything. :visitor is the id of the visitor's session, for someone
   not signed in: the rows that name it are theirs. :flags are the session's
   flags, for the denies that name one."
  [perms {:keys [person visitor scopes write-scopes flags read-only?]} f]
  (own-transaction perms
                   {:read-only? (boolean read-only?)}
                   (fn [tx]
                     (pg/execute! tx set-wall {:scopes       (scopes-text (or scopes #{}))
                                               :write-scopes (if read-only?
                                                               "{}"
                                                               (scopes-text (or write-scopes scopes #{})))
                                               :person       (if person (str person) "")
                                               :visitor      (if (and visitor (nil? person)) (str visitor) "")
                                               :flags        (str "{" (str/join "," (map name flags)) "}")})
                     (f tx))))

(defn in-scopes
  "Run (f tx) in its own transaction that sees and writes every row of
   `scopes` and nothing else: work done for a scope rather than a person,
   like a job that names its scope (see groundwork.jobs)."
  [perms scopes f]
  (in-wall perms {:scopes scopes}
           (fn [tx]
             (pg/execute! tx set-member-scopes {:scopes (uuid-array scopes)})
             (f tx))))

(defn- query
  "Rows of compiled SQL on a transaction's connection."
  [tx sql params]
  (jdbc/execute! (::pg/conn tx) (into [sql] params)))

;; =============================================================================
;; The sentences, read once
;; =============================================================================

(defn- sentences-of
  "The read sentences giving an action, on one kind or on any."
  ([perms action] (get-in perms [:by-action action]))
  ([perms action kind] (get-in perms [:by-action-kind [action kind]])))

(defn declared?
  "Whether a sentence gives an action on a kind of record."
  [perms action kind]
  (boolean (seq (sentences-of perms action kind))))

(defn- record-spec [{:keys [description]} kind]
  (or (get-in description [:records kind])
      (throw (ex-info (str "No record kind " kind) {:type ::unknown-kind :kind kind}))))

(defn- as-id
  "An id as the database compares it: text that is a uuid becomes one (an id
   out of JSON, or from an open part of a request), anything else stays."
  [v]
  (or (when (string? v) (parse-uuid v)) v))

(defn- id-is
  "The SQL for \"this record's id column is ?\". Text that isn't a uuid (an
   id a client made up) is compared as text, so on a uuid column it matches
   nothing instead of failing the statement."
  [id-col id]
  (str "r." (rules/quote-column id-col) (if (string? id) "::text = ?" " = ?")))

(defn open-level
  "Who an action is open to through its sentences: :anyone, :signed-in, or
   nil."
  [perms action]
  (get-in perms [:open action]))

(defn action-kind
  "The kind of scope an action belongs to, or nil for an unknown action."
  [perms action]
  (get (rules/action-kinds (:description perms)) action))

(defn writes?
  "Whether an action inserts, updates or deletes."
  [perms action]
  (rules/writes? (:description perms) action))

(defn visitor-action?
  "Whether some sentence gives this action to a visitor: its endpoints may
   be asked by someone not signed in."
  [perms action]
  (contains? (:visitor-actions perms) action))

;; =============================================================================
;; Denies
;; =============================================================================

(defn flag-deny
  "The deny a session's flags say no with, for an action, or nil."
  [perms action flags]
  (first (for [{:keys [session] :as d} (:denies perms)
               :when (and session (contains? (set flags) session)
                          (rules/deny-applies? (:description perms) d action))]
           d)))

(defn blocked?
  "Whether a session's flags deny an action."
  [perms action flags]
  (some? (flag-deny perms action flags)))

(defn- state-denies [perms action]
  (filter #(and (:scope %) (rules/deny-applies? (:description perms) % action)) (:denies perms)))

(defn denied-scopes
  "Of these scopes, the ones a scope's state denies the action in:
   {scope the deny}. Read past the wall, on the app's own scope table."
  [perms tx action scopes]
  (let [scopes (seq (remove nil? scopes))]
    (if (or (nil? scopes) (empty? (state-denies perms action)))
      {}
      (into {}
            (for [{:keys [scope] condition :when :as d} (state-denies perms action)
                  :let [{t :table id :id} (get-in perms [:description :scopes scope])
                        [c ps]            (rules/condition-sql "s" condition)]
                  row (query tx (str "SELECT s." (rules/quote-column id) "::text AS denied FROM "
                                     (rules/quote-table t) " s WHERE s." (rules/quote-column id)
                                     " = ANY (CAST(? AS uuid[])) AND " c)
                             (into [(uuid-array scopes)] ps))]
              [(parse-uuid (val (first row))) d])))))

;; =============================================================================
;; Checking
;; =============================================================================

(defn- scope-select
  "The SQL that selects a kind's scope columns over alias r, or a constant
   for a kind that has none."
  [record]
  (let [cols (keys (rules/scope-columns record))]
    (if (seq cols)
      (str/join ", " (map-indexed (fn [i c] (str "r." (rules/quote-column c) " AS s" i)) cols))
      "1 AS none")))

(defn- row-scopes [row]
  (into #{} (comp (filter (fn [[k _]] (str/starts-with? (name k) "s"))) (map val) (remove nil?)) row))

(defn why-not
  "Why a person may not do an action on one record, or nil when they may:
   {:reason :none} (no sentence gives it), {:reason :not-allowed} (none
   holds), or {:reason :denied :deny d}. A deny comes before every yes."
  [perms {:keys [person visitor flags action] [kind id] :record}]
  (let [id                    (as-id id)
        {t :table id-col :id :as spec} (record-spec perms kind)
        {:keys [sql params]}  (get-in perms [:compiled [action kind]])
        ;; a visitor is someone not signed in: signed in, there is none
        visitor               (when (nil? person) visitor)]
    (cond
      (nil? sql)                        {:reason :none}
      (flag-deny perms action flags)    {:reason :denied :deny (flag-deny perms action flags)}
      :else
      (as-system perms
                 (fn [tx]
                   (let [row (first (query tx (str "SELECT " (scope-select spec) " FROM " (rules/quote-table t) " r WHERE "
                                                   (id-is id-col id) " AND " sql)
                                           (into [id] (rules/bind params person visitor))))]
                     (cond
                       (nil? row) {:reason :not-allowed}
                       :else      (when-let [d (first (vals (denied-scopes perms tx action (row-scopes row))))]
                                    {:reason :denied :deny d}))))))))

(defn via-kind?
  "Whether a sentence giving the action reaches its rows through a related
   row of this kind: an id of it may come with a request for the action."
  [perms action kind]
  (boolean (some #(= kind (get-in % [:via :kind])) (sentences-of perms action))))

(defn via-allows?
  "Whether a related row (a :via's own kind, by id) is one a sentence giving
   the action looks for: its who and its when hold for the person. An open
   action's request may carry its id (reply under this ticket); a ticket
   the via doesn't take answers not-found. Read as the system."
  [perms {:keys [person visitor flags action] [kind id] :record}]
  (let [id     (as-id id)
        visitor (when (nil? person) visitor)
        vias   (filter #(= kind (get-in % [:via :kind])) (sentences-of perms action))
        {t :table id-col :id} (record-spec perms kind)]
    (boolean
     (and (seq vias) (not (blocked? perms action flags))
          (as-system perms
                     (fn [tx]
                       (some (fn [{:keys [via]}]
                               (let [[w wps] (rules/who-sql "r" (:who via))
                                     [c cps] (if (:when via) (rules/condition-sql "r" (:when via)) ["TRUE" []])]
                                 (seq (query tx (str "SELECT 1 FROM " (rules/quote-table t) " r WHERE " (id-is id-col id)
                                                     " AND " w " AND " c)
                                             (into [id] (concat (rules/bind wps person visitor) cps))))))
                             vias)))))))

(defn allowed?
  "Whether a person (or a visitor, for someone not signed in) may do an
   action on one record: [kind id]. A sentence must hold for the row, and
   no deny."
  [perms request]
  (nil? (why-not perms request)))

(defn scopes-of
  "The scope ids a record belongs to, through its own columns."
  [perms [kind id]]
  (let [id                             (as-id id)
        {t :table id-col :id :as spec} (record-spec perms kind)]
    (as-system perms
               (fn [tx]
                 (into #{}
                       (mapcat row-scopes)
                       (query tx (str "SELECT " (scope-select spec) " FROM " (rules/quote-table t) " r WHERE "
                                      (id-is id-col id))
                              [id]))))))

(defn check!
  "The record's scopes when the person may do the action on it, else throws
   not-found. Every other id the request carries (:also {kind [ids]}) must
   belong to one of those scopes too, or it's not-found as well."
  [perms {:keys [record also] :as request}]
  (when-not (allowed? perms request)
    (throw (not-found {:record record})))
  (let [scopes (scopes-of perms record)]
    (doseq [[kind ids] also
            id         ids]
      (when (empty? (set/intersection scopes (scopes-of perms [kind id])))
        (throw (not-found {:record [kind id]}))))
    scopes))

(defn list-filter
  "The condition for every record of a kind the person may do the action on:
   {:sql … :params […]} over alias r, for a list query's WHERE. FALSE when a
   session flag denies the action or no sentence gives it. A scope's state
   isn't in it: the endpoint's verified scopes leave those out."
  [perms {:keys [person visitor flags action kind]}]
  (let [{:keys [sql params]} (get-in perms [:compiled [action kind]])]
    (if (or (nil? sql) (blocked? perms action flags))
      {:sql "FALSE" :params []}
      {:sql sql :params (rules/bind params person visitor)})))

(defn- via-scope-position
  "Where a via offers a value for the row's scope column: the index into
   what it matches, or nil when it matches nothing that names the scope."
  [perms {:keys [kind via]}]
  (let [scope-cols (set (keys (rules/scope-columns (get-in perms [:description :records kind]))))]
    (first (keep-indexed (fn [i [mine _]] (when (scope-cols mine) i)) (:match via)))))

(defn- scope-clauses
  "Conditions on a scope id (the SQL `expr`) under which an action's
   sentences give it in that scope: [[sql params] …]. A member of the scope
   through their role, a member of a fixed scope that reaches every one,
   a related row that names the scope. A person's own rows and the open
   words give no scope: those show row by row."
  [perms action expr]
  (distinct
   (keep (fn [{:keys [who via] :as s}]
           (cond
             (and (= :member (:word who)) (:column who))
             [(str expr " IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))")
              [:person (rules/action-name (:holding who))]]

             (= :member (:word who))
             (rules/who-sql "gwx" who)

             via
             (when-let [i (via-scope-position perms s)]
               [(str expr " IN (SELECT via_" (inc i) " FROM " (rules/via-function via)
                     "(CAST(? AS uuid), CAST(? AS uuid), NULL))")
                [:person :visitor]])))
         (sentences-of perms action))))

(defn scopes-under
  "The scope and every scope below it where the sentences give the person
   the action: what a request about that scope may see. Empty when nothing
   gives it there, or a session flag denies it. A scope's state is the
   endpoint's to apply (see with-denies). :groundwork/team is held through
   roles alone."
  [perms {:keys [person visitor flags action scope]}]
  (let [clauses (if (= rules/team-action action)
                  [["scope IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))"
                    [:person (rules/action-name action)]]]
                  (scope-clauses perms action "scope"))]
    (if (or (nil? scope) (empty? clauses) (blocked? perms action flags))
      #{}
      (as-system perms
                 (fn [tx]
                   (into #{}
                         (map (comp val first))
                         (query tx (str "WITH RECURSIVE below(scope) AS ("
                                        " SELECT CAST(? AS uuid)"
                                        " UNION SELECT s.scope__id FROM groundwork.scopes s"
                                        " JOIN below b ON s.scope__parent_id = b.scope)"
                                        " SELECT scope FROM below WHERE "
                                        (str/join " OR " (map first clauses)))
                                (into [(str scope)] (rules/bind (vec (mapcat second clauses)) person visitor)))))))))

(defn open-scope
  "For an action a sentence opens to anyone (or the signed-in) asked about a
   scope: that scope alone, when it exists and is of the kind the endpoint
   names; else nothing. No membership is looked at, and nothing below the
   scope comes with it."
  [perms {:keys [person flags action scope kind]}]
  (let [level (open-level perms action)]
    (if (or (nil? level) (nil? scope) (blocked? perms action flags)
            (and (= :signed-in level) (nil? person)))
      #{}
      (as-system perms
                 (fn [tx]
                   (into #{}
                         (map (comp val first))
                         (pg/rows tx scope-of-kind {:scope/id (str scope) :scope/kind (name kind)})))))))

(defn scopes-listed
  "The scopes a list of every record of a kind the person may do the action
   on reads from: {:scopes #{…} :every? bool}. Where a member holds the
   action, the scopes a related row names, every scope for a fixed scope's
   member who reaches them all. A person's own rows show by the person,
   universal rows by having none. Empty when a session flag denies the
   action."
  [perms {:keys [person visitor flags action kind]}]
  (let [ss (sentences-of perms action kind)]
    (if (or (empty? ss) (blocked? perms action flags))
      {:scopes #{} :every? false}
      (let [{t :table :as spec} (record-spec perms kind)
            parts  (distinct
                    (keep (fn [{:keys [who via] :as s}]
                            (cond
                              (and (= :member (:word who)) (:column who))
                              ["SELECT groundwork.scopes_holding(CAST(? AS uuid), ?) AS scope"
                               [:person (rules/action-name (:holding who))]]

                              via
                              (if-let [i (via-scope-position perms s)]
                                [(str "SELECT via_" (inc i) " AS scope FROM " (rules/via-function via)
                                      "(CAST(? AS uuid), CAST(? AS uuid), NULL)")
                                 [:person :visitor]]
                                ;; a via that names records, not their scope: the
                                ;; scopes of the records it gives
                                (let [[v vps] (rules/sentence-sql (dissoc s :when) "r")]
                                  [(str "SELECT DISTINCT x.scope FROM " (rules/quote-table t) " r"
                                        " CROSS JOIN LATERAL (VALUES "
                                        (str/join ", " (map #(str "(r." (rules/quote-column %) ")")
                                                            (keys (rules/scope-columns spec))))
                                        ") AS x(scope) WHERE " v)
                                   vps]))))
                          ss))
            every  (distinct (keep (fn [{:keys [who]}]
                                     (when (and (= :member (:word who)) (not (:column who)))
                                       (rules/who-sql "gwx" who)))
                                   ss))]
        (as-system perms
                   (fn [tx]
                     {:scopes (if (seq parts)
                                (into #{}
                                      (comp (map (comp val first)) (remove nil?))
                                      (query tx (str/join " UNION " (map first parts))
                                             (rules/bind (vec (mapcat second parts)) person visitor)))
                                #{})
                      :every? (boolean (and (seq every)
                                            (:every (jdbc/execute-one!
                                                     (::pg/conn tx)
                                                     (into [(str "SELECT (" (str/join " OR " (map first every)) ") AS every")]
                                                           (rules/bind (vec (mapcat second every)) person visitor))
                                                     {:builder-fn rs/as-unqualified-maps}))))}))))))

(defn with-denies
  "A request's scopes after the scopes' states: `own` are the scopes the
   request is about (the record's, the one it names), `more` the others it
   may read (those below, a list's). A deny holding in an own scope makes
   the request not-found. For an action that writes, a scope a deny holds
   in stays verified for reads and is left out of the writes; for one that
   only reads, it is left out. `more` may be :every.
   Returns {:read scopes-or-:every :write scopes-or-:every}."
  [perms {:keys [action own more]}]
  (if (empty? (state-denies perms action))
    {:read (if (= :every more) :every (into (set own) more)) :write (if (= :every more) :every (into (set own) more))}
    (as-system perms
               (fn [tx]
                 (when (seq (denied-scopes perms tx action own))
                   (throw (not-found {:scopes own})))
                 (let [more   (if (= :every more)
                                (into #{} (map :scopes/scope__id) (query tx "SELECT scope__id FROM groundwork.scopes" []))
                                (set more))
                       denied (set (keys (denied-scopes perms tx action more)))
                       read   (into (set own) (if (writes? perms action) more (remove denied more)))]
                   {:read read :write (set/difference read denied)})))))

(defn listening
  "The scopes in which a person could be the one a related row names, for
   any via of the description: a change to such a row in them changes what
   they see, so their lines hear of it. {:scopes #{…} :every? bool}."
  [perms person]
  (let [whos   (distinct (keep #(get-in % [:via :who]) (:sentences perms)))
        parts  (distinct (keep (fn [who]
                                 (when (and (= :member (:word who)) (:column who))
                                   ["SELECT groundwork.scopes_holding(CAST(? AS uuid), ?)"
                                    [:person (rules/action-name (:holding who))]]))
                               whos))
        every  (distinct (keep (fn [who] (when (and (= :member (:word who)) (not (:column who)))
                                           (rules/who-sql "gwx" who)))
                               whos))]
    (if (or (nil? person) (and (empty? parts) (empty? every)))
      {:scopes #{} :every? false}
      (as-system perms
                 (fn [tx]
                   {:scopes (if (seq parts)
                              (into #{} (map (comp val first))
                                    (query tx (str/join " UNION " (map first parts))
                                           (rules/bind (vec (mapcat second parts)) person)))
                              #{})
                    :every? (boolean (and (seq every)
                                          (:every (jdbc/execute-one!
                                                   (::pg/conn tx)
                                                   (into [(str "SELECT (" (str/join " OR " (map first every)) ") AS every")]
                                                         (rules/bind (vec (mapcat second every)) person))
                                                   {:builder-fn rs/as-unqualified-maps}))))})))))

(defn grants
  "Every action the sentences give the person, and where: for the browser's
   \"can I?\". [{:grant/scope-id id-or-nil :grant/action action}]; a nil
   scope means every scope (an open word, a fixed scope's member who reaches
   them all). A session flag's deny takes an action out; a scope's state
   takes the action out of that scope. :groundwork/team comes from roles."
  [perms {:keys [person visitor flags]}]
  (let [parts (distinct
               (concat
                [["SELECT s AS scope, ? AS action FROM groundwork.scopes_holding(CAST(? AS uuid), ?) s"
                  [(rules/action-name rules/team-action) :person (rules/action-name rules/team-action)]]]
                (for [[action ss] (:by-action perms)
                      :when (not (blocked? perms action flags))
                      {:keys [who via] :as s} ss
                      :let [a (rules/action-name action)
                            part (cond
                                   (= :anyone (:word who))
                                   ["SELECT CAST(NULL AS uuid), ?" [a]]

                                   (= :signed-in (:word who))
                                   ["SELECT CAST(NULL AS uuid), ? WHERE CAST(? AS uuid) IS NOT NULL" [a :person]]

                                   (and (= :member (:word who)) (:column who))
                                   ["SELECT s, ? FROM groundwork.scopes_holding(CAST(? AS uuid), ?) s"
                                    [a :person (rules/action-name (:holding who))]]

                                   (= :member (:word who))
                                   (let [[w ps] (rules/who-sql "gwx" who)]
                                     [(str "SELECT CAST(NULL AS uuid), ? WHERE " w) (into [a] ps)])

                                   (= :anyone (get-in via [:who :word]))
                                   ["SELECT CAST(NULL AS uuid), ?" [a]]

                                   (= :signed-in (get-in via [:who :word]))
                                   ["SELECT CAST(NULL AS uuid), ? WHERE CAST(? AS uuid) IS NOT NULL" [a :person]]

                                   via
                                   (when-let [i (via-scope-position perms s)]
                                     [(str "SELECT via_" (inc i) ", ? FROM " (rules/via-function via)
                                           "(CAST(? AS uuid), CAST(? AS uuid), NULL)")
                                      [a :person :visitor]]))]
                      :when part]
                  part)))]
    (as-system perms
               (fn [tx]
                 (let [found (distinct
                              (map (fn [{:keys [scope action]}] {:grant/scope-id scope :grant/action (keyword action)})
                                   (jdbc/execute! (::pg/conn tx)
                                                  (into [(str/join " UNION " (map first parts))]
                                                        (rules/bind (vec (mapcat second parts)) person visitor))
                                                  {:builder-fn rs/as-unqualified-maps})))
                       ids   (set (keep :grant/scope-id found))
                       out   (into {}
                                   (for [action (distinct (map :grant/action found))
                                         :let [denied (denied-scopes perms tx action ids)]
                                         :when (seq denied)]
                                     [action (set (keys denied))]))]
                   (vec (remove (fn [{:grant/keys [scope-id action]}] (contains? (get out action) scope-id))
                                found)))))))

(defn move-visitor!
  "At sign-in: every row that is the visitor's becomes the person's, in
   every table with a visitor column. The person is set and the visitor
   emptied; a kind with no person column keeps its rows, which are nobody's
   once the visitor's session has ended. Returns how many rows moved."
  [perms visitor person]
  (let [moves (filter :person-column (rules/visitor-moves (:description perms)))]
    (if (or (nil? visitor) (nil? person) (empty? moves))
      0
      (as-system perms
                 (fn [tx]
                   (reduce + 0
                           (for [{:keys [table visitor-column person-column]} moves]
                             (:next.jdbc/update-count
                              (first (query tx (str "UPDATE \"" table "\" SET \"" person-column "\" = CAST(? AS uuid), \""
                                                    visitor-column "\" = NULL WHERE \"" visitor-column
                                                    "\" = CAST(? AS uuid)")
                                            [(str person) (str visitor)]))))))))))

(defn open-tables
  "The tables someone who belongs nowhere may read rows of."
  [perms]
  (:open-tables perms))

(defn holds?
  "Whether a person holds an action in a scope, or in one above it."
  [perms person action scope-id]
  (as-system perms
             (fn [tx]
               (:holding/yes (pg/row tx holds-sql {:scope/id scope-id :person (str person)
                                                   :action   (rules/action-name action)})))))

;; =============================================================================
;; Scopes, roles and members
;; =============================================================================

(defn- kind-of [tx scope-id]
  (some-> (first (query tx "SELECT scope__kind FROM groundwork.scopes WHERE scope__id = ?" [scope-id]))
          :scopes/scope__kind
          keyword))

(defn- set-role-actions-in! [tx role-id actions]
  (pg/execute! tx delete-role-actions {:role/id role-id})
  (doseq [action actions]
    (pg/execute! tx insert-role-action {:role-action/role-id role-id
                                        :role-action/action  (rules/action-name action)})))

(defn create-scope!
  "A new place: its kind, its parent (or nil), its owner. It gets the owner
   role and the kind's starting roles. A kind with exactly one scope
   (:single? true) refuses a second. Returns the scope's id."
  [perms {:keys [kind id parent owner]}]
  (let [spec (get-in perms [:description :scopes kind])]
    (when-not spec
      (throw (ex-info (str "No scope kind " kind) {:type ::unknown-kind :kind kind})))
    (as-system perms
               (fn [tx]
                 (when parent
                   (when-not (= (:parent spec) (kind-of tx parent))
                     (throw (ex-info (str "A " kind "'s parent must be a " (:parent spec))
                                     {:type ::bad-parent :parent parent}))))
                 (when (:single? spec)
                   ;; one at a time, so two starts can't both make the first
                   (query tx "SELECT pg_advisory_xact_lock(hashtext(?))" [(str "groundwork.single/" (name kind))])
                   (when (pos? (:scopes/count (pg/row tx count-of-kind {:scope/kind (name kind)})))
                     (throw (ex-info (str "A " (name kind) " has exactly one scope, and it exists")
                                     {:type ::single-scope :kind kind}))))
                 (pg/execute! tx insert-scope {:scope/id id :scope/kind (name kind) :scope/parent-id parent})
                 (let [owner-id (:role/id (pg/row tx insert-role {:role/scope-id id :role/name owner-role :role/owner true}))]
                   (doseq [[role actions] (:roles spec)]
                     (let [role-id (:role/id (pg/row tx insert-role {:role/scope-id id :role/name role :role/owner false}))]
                       (set-role-actions-in! tx role-id actions)))
                   (pg/execute! tx upsert-membership {:membership/person-id owner
                                                      :membership/scope-id  id
                                                      :membership/role-id   owner-id}))
                 id))))

(defn- role-in [tx scope-id role-name]
  (or (pg/row tx role-by-name {:role/scope-id scope-id :role/name role-name})
      (throw (ex-info (str "No role " role-name) {:type ::unknown-role :role role-name}))))

(defn- owner? [tx person scope-id]
  (boolean (:role/owner (pg/row tx membership {:membership/person-id person :membership/scope-id scope-id}))))

(defn- may-manage!
  "The acting person must hold :groundwork/team in the scope, and every
   action they hand out (or take back)."
  [perms by scope-id actions]
  (when-not (holds? perms by rules/team-action scope-id)
    (throw (refused "Only someone who manages the team can do this" {:scope scope-id})))
  (doseq [action actions
          :when (not (holds? perms by action scope-id))]
    (throw (refused (str "Only someone who holds " action " can give or take it") {:action action}))))

(defn- check-role-actions! [perms tx scope-id actions]
  (let [allowed (rules/allowed-in-role? (:description perms) (kind-of tx scope-id))]
    (doseq [action actions
            :when (not (allowed action))]
      (throw (ex-info (str action " isn't an action of this scope") {:type ::unknown-action :action action})))))

(defn create-role!
  "A new role in a scope, with its actions."
  [perms {:keys [by scope role actions]}]
  (may-manage! perms by scope actions)
  (as-system perms
             (fn [tx]
               (check-role-actions! perms tx scope actions)
               (let [role-id (:role/id (pg/row tx insert-role {:role/scope-id scope :role/name role :role/owner false}))]
                 (set-role-actions-in! tx role-id actions)
                 role-id))))

(defn set-role-actions!
  "Set a role's actions (a scope's roles grid). The owner role can't be
   edited; every action added or removed must be one the editor holds."
  [perms {:keys [by scope role actions]}]
  (let [{:role/keys [id owner] before :role/actions} (as-system perms #(role-in % scope role))
        before                                       (set (map keyword before))
        changed                                      (into (apply disj (set actions) before)
                                                           (apply disj before actions))]
    (when owner
      (throw (refused "The owner role always holds everything" {:role role})))
    (may-manage! perms by scope changed)
    (as-system perms
               (fn [tx]
                 (check-role-actions! perms tx scope actions)
                 (set-role-actions-in! tx id actions)))))

(defn invite!
  "Invite an email into a scope with a role, valid for `days`. Returns the
   token for the invitation link; only its SHA-256 is kept."
  [perms {:keys [by scope email role days]}]
  (let [{:role/keys [id owner actions]} (as-system perms #(role-in % scope role))]
    (may-manage! perms by scope (map keyword actions))
    (when (and owner (not (as-system perms #(owner? % by scope))))
      (throw (refused "Only an owner can invite an owner" {:role role})))
    (let [token (let [bytes (byte-array 32)]
                  (.nextBytes (SecureRandom.) bytes)
                  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))]
      (as-system perms
                 (fn [tx]
                   (pg/execute! tx insert-invitation
                                {:invitation/email      email
                                 :invitation/scope-id   scope
                                 :invitation/role-id    id
                                 :invitation/token-hash (.digest (MessageDigest/getInstance "SHA-256")
                                                                 (.getBytes ^String token "UTF-8"))
                                 :invitation/invited-by by
                                 :days                  days})))
      token)))

(defn accept-invitation!
  "The signed-in person (with this email) joins with the invitation's role.
   Returns the scope's id. A used, expired or someone else's invitation is
   not-found."
  [perms {:keys [token person email]}]
  (as-system perms
             (fn [tx]
               (let [invitation (pg/row tx take-invitation
                                        {:invitation/token-hash (.digest (MessageDigest/getInstance "SHA-256")
                                                                         (.getBytes ^String token "UTF-8"))})]
                 (when-not (and invitation (= (str/lower-case email) (:invitation/email invitation)))
                   (throw (not-found {:invitation :token})))
                 (pg/execute! tx upsert-membership {:membership/person-id person
                                                    :membership/scope-id  (:invitation/scope-id invitation)
                                                    :membership/role-id   (:invitation/role-id invitation)})
                 (:invitation/scope-id invitation)))))

(defn- keep-an-owner! [tx scope person]
  (when (and (owner? tx person scope)
             (<= (:owners/count (pg/row tx count-owners {:scope/id scope})) 1))
    (throw (refused "A scope always keeps an owner" {:scope scope}))))

(defn change-role!
  "Give a member another role. Only an owner makes or unmakes owners; the
   last owner stays one."
  [perms {:keys [by scope person role]}]
  (let [{:role/keys [id owner actions]} (as-system perms #(role-in % scope role))]
    (may-manage! perms by scope (map keyword actions))
    (as-system perms
               (fn [tx]
                 (when (and (or owner (owner? tx person scope)) (not (owner? tx by scope)))
                   (throw (refused "Only an owner can make or unmake an owner" {:role role})))
                 (when-not owner (keep-an-owner! tx scope person))
                 (when-not (pg/row tx membership {:membership/person-id person :membership/scope-id scope})
                   (throw (not-found {:member person})))
                 (pg/execute! tx upsert-membership {:membership/person-id person
                                                    :membership/scope-id  scope
                                                    :membership/role-id   id})))))

(defn remove-member!
  "Take a person out of a scope. Only an owner removes an owner; the last
   owner stays."
  [perms {:keys [by scope person]}]
  (may-manage! perms by scope [])
  (as-system perms
             (fn [tx]
               (when (and (owner? tx person scope) (not (owner? tx by scope)))
                 (throw (refused "Only an owner can remove an owner" {:member person})))
               (keep-an-owner! tx scope person)
               (pg/execute! tx delete-membership {:membership/person-id person :membership/scope-id scope}))))

(defn grant-extra!
  "One action for a person in a scope, on top of their role."
  [perms {:keys [by scope person action]}]
  (may-manage! perms by scope [action])
  (as-system perms
             (fn [tx]
               (check-role-actions! perms tx scope [action])
               (pg/execute! tx insert-extra-action {:extra-action/person-id person
                                                    :extra-action/scope-id  scope
                                                    :extra-action/action    (rules/action-name action)}))))

(defn revoke-extra!
  "Take an extra action back."
  [perms {:keys [by scope person action]}]
  (may-manage! perms by scope [action])
  (as-system perms
             (fn [tx]
               (pg/execute! tx delete-extra-action {:extra-action/person-id person
                                                    :extra-action/scope-id  scope
                                                    :extra-action/action    (rules/action-name action)}))))

;; =============================================================================
;; The part
;; =============================================================================

(defn- bad [message data sql]
  (throw (ex-info (str message (when sql (str "\nIn a migration:\n" sql)))
                  (assoc data :type ::bad-description :sql sql))))

(defn- link-sql
  "The SQL that adds a parent link for one carried column: the parent's
   unique pair, if it has none, and the child's foreign key."
  [{t :table :keys [parent-column parent-table parent-id]} {c :column p :parent-column}]
  (str "ALTER TABLE " parent-table " ADD UNIQUE (" parent-id ", " p ");  -- if it has none\n"
       "ALTER TABLE " t " ADD FOREIGN KEY (" parent-column ", " c ")\n"
       "  REFERENCES " parent-table " (" parent-id ", " p ") ON UPDATE CASCADE;"))

(defn- not-null-of [db t]
  (into {} (map (juxt :column/name :column/not-null)) (pg/rows db columns-of {:table t})))

(defn- check-scope-columns!
  "Every record table has its scope columns. A scope column a record
   carries from its parent, and the parent's column it is carried from, are
   NOT NULL: their matching link holds only then. A record on its own, and
   a column that is a record's own beside what it carries, may be empty:
   the row is in no scope through it yet, and only its people and the
   system see it, or it is universal when a sentence says so."
  [db description]
  (let [links      (rules/parent-links description)
        carried    (into {} (for [{:keys [table carried] :as link} links
                                  {:keys [column person?] :as c} carried
                                  :when (not person?)]
                              [[table column] [link c]]))
        handed     (into #{} (for [{:keys [parent-table carried]} links
                                   {:keys [parent-column person?]} carried
                                   :when (not person?)]
                               [parent-table parent-column]))]
    (doseq [{:keys [table column]} (rules/record-scope-columns description)]
      (let [have     (not-null-of db table)
            [link c] (carried [table column])]
        (cond
          (not (contains? have column))
          (bad (str "Table " table " needs its own scope column " column)
               {:table table :column column}
               (str "ALTER TABLE " table " ADD COLUMN " column " uuid;\n"
                    (if link
                      (str "UPDATE " table " c SET " column " = p." (:parent-column c)
                           " FROM " (:parent-table link) " p WHERE p." (:parent-id link)
                           " = c." (:parent-column link) ";\n"
                           "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;")
                      (str "-- fill " column " for the rows already there\n"
                           "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;"))))

          (and (or link (handed [table column])) (not (have column)))
          (bad (str "Scope column " table "." column " must be NOT NULL: records hang under " table
                    ", or it hangs under another")
               {:table table :column column}
               (str "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;")))))
    (doseq [{:keys [table column]} (rules/none-columns description)
            :when (get (not-null-of db table) column)]
      (bad (str "A sentence says [:none …] of " table "." column ", which is NOT NULL: no row of it can belong"
                " to nobody")
           {:table table :column column}
           (str "ALTER TABLE " table " ALTER COLUMN " column " DROP NOT NULL;")))))

(defn- check-parent-links!
  "Every record under another record is tied to it by a matching link, for
   each column it carries: a foreign key that holds its (parent column,
   column) to the parent's (id, column) and cascades. A key with more
   columns in it counts only while none of them can be empty, since one
   empty column switches a whole key off; a column that may be empty (a
   person) has a key of its own two."
  [db description]
  (doseq [{t :table :keys [parent-column parent-table parent-id carried] :as link} (rules/parent-links description)
          :let [not-null (not-null-of db t)
                keys     (pg/rows db links-between {:child t :parent parent-table})]
          {c :column p :parent-column :as one} carried]
    (let [wanted  #{[parent-column parent-id] [c p]}
          pairs   (fn [k] (set (map vector (:link/child-columns k) (:link/parent-columns k))))
          holding (filter (fn [k] (let [ps (pairs k)]
                                    (and (every? ps wanted)
                                         (or (= wanted ps) (every? not-null (:link/child-columns k))))))
                          keys)
          found   (or (first (filter :link/cascades holding)) (first holding))]
      (cond
        (nil? found)
        (bad (str "Table " t " needs a link to " parent-table " that keeps its " c " its parent's: "
                  "a foreign key (" parent-column ", " c ") → " parent-table
                  " (" parent-id ", " p ") ON UPDATE CASCADE")
             {:table t :parent parent-table :column c}
             (link-sql link one))

        (not (:link/cascades found))
        (bad (str "The link from " t " to " parent-table " must be ON UPDATE CASCADE, so "
                  t " follows its parent when the parent moves to another scope")
             {:table t :parent parent-table :column c}
             (str "-- drop the existing foreign key (" parent-column ", " c "), then:\n"
                  (link-sql link one)))))))

(defn- implied-by-match?
  "Whether a partial index's predicate (as Postgres prints it) holds for
   every row a lookup by these columns finds: it says only that some of
   them are not empty, as an equality match already does."
  [predicate columns]
  (let [parts (-> predicate
                  (str/replace #"[()]" "")
                  (str/split #"(?i)\s+AND\s+"))]
    (every? (fn [part]
              (when-let [[_ c] (re-matches #"(?i)\s*\"?([a-z_][a-z0-9_]*)\"?\s+IS\s+NOT\s+NULL\s*" part)]
                (contains? columns c)))
            parts)))

(defn- check-via-indexes!
  "A via looks rows up by what it matches, on both sides, and finds the
   other rows by their who column: each needs an index that starts with
   those columns, or the lookup reads the whole table on every check. A
   partial index counts when it leaves out only rows with those columns
   empty (WHERE col IS NOT NULL): no lookup by them finds such a row."
  [db description]
  (doseq [{t :table :keys [columns why]} (rules/via-indexes description)
          :let [wanted  (set columns)
                indexes (keep (fn [{:index/keys [columns predicate]}]
                                ;; a partial index counts when all it leaves out is a row
                                ;; whose matched column is empty, which a match never finds
                                (when (or (nil? predicate) (implied-by-match? predicate wanted)) columns))
                              (pg/rows db indexes-of {:table t}))]
          :when (not-any? #(and (<= (count wanted) (count %)) (= wanted (set (take (count wanted) %)))) indexes)]
    (bad (str "Table " t " needs an index on " (str/join ", " columns) ": " why)
         {:table t :columns columns}
         (str "CREATE INDEX ON " t " (" (str/join ", " columns) ");"))))

(defn role-problem
  "Why the role the app connects as can't carry the wall, or nil: a superuser
   or a role with BYPASSRLS passes by every row-level policy, so every query
   would see every scope's rows, with no error anywhere. A hosted database
   often makes its owner such a role."
  [{:role/keys [name bypasses-rls]}]
  (when bypasses-rls
    (str "The database role " name " passes by row-level security (it is a superuser or has BYPASSRLS),"
         " so the wall would let every query see every scope's rows. Connect as a role without it.")))

(defn- check-database!
  "The role the app connects as must not pass by row-level security; every
   table and column the description names must exist; every record table
   names its scope; every record under another is tied to it; every via
   can find its rows by an index."
  [db description]
  (when-let [problem (role-problem (pg/row db connecting-role {}))]
    (throw (ex-info problem {:type ::bad-database})))
  (doseq [t (keys (rules/columns-needed description))]
    (when (empty? (pg/rows db columns-of {:table t}))
      (throw (ex-info (str "Permissions name a table " t " that doesn't exist")
                      {:type ::bad-description :table t}))))
  (check-scope-columns! db description)
  (doseq [[t needed] (rules/columns-needed description)]
    (let [have (set (map :column/name (pg/rows db columns-of {:table t})))]
      (doseq [c needed
              :when (not (have c))]
        (throw (ex-info (str "Permissions name a column " t "." c " that doesn't exist")
                        {:type ::bad-description :table t :column c})))))
  (check-parent-links! db description)
  (check-via-indexes! db description))

(defn- exec! [tx sql]
  (jdbc/execute! (::pg/conn tx) [sql]))

(defn install-wall!
  "Row-level security on every record kind's table, a policy per command
   made from the sentences (rules/policies). Forced, so the tables' owner
   is held to it too. The vias' functions first, which the policies call;
   then the guards on the rows the vias look for. Run at start; safe to run
   again."
  [{:keys [db description]}]
  (pg/with-transaction [tx db]
    (doseq [via (rules/vias description)]
      (let [types (into {} (map (juxt :column/name :column/type)) (pg/rows tx columns-of {:table (:table via)}))]
        (exec! tx (rules/via-sql-statement via types))))
    (doseq [[kind {t :table}] (:records description)
            :let [table                                 (rules/quote-table t)
                  {:keys [select insert update delete]} (rules/policies description kind)]]
      (exec! tx (str "ALTER TABLE " table " ENABLE ROW LEVEL SECURITY"))
      (exec! tx (str "ALTER TABLE " table " FORCE ROW LEVEL SECURITY"))
      (doseq [{:policy/keys [name]} (pg/rows tx groundwork-policies {:table (clojure.core/name t)})]
        (exec! tx (str "DROP POLICY \"" name "\" ON " table)))
      (exec! tx (str "CREATE POLICY groundwork_read ON " table " FOR SELECT USING (" select ")"))
      (exec! tx (str "CREATE POLICY groundwork_insert ON " table " FOR INSERT WITH CHECK (" insert ")"))
      (exec! tx (str "CREATE POLICY groundwork_update ON " table " FOR UPDATE USING (" (first update)
                     ") WITH CHECK (" (second update) ")"))
      (exec! tx (str "CREATE POLICY groundwork_delete ON " table " FOR DELETE USING (" delete ")")))
    (let [guarded (set (map #(name (get-in description [:records % :table])) (rules/guarded-kinds description)))]
      ;; a guard from an earlier start on a table no via looks into any more goes
      (doseq [{:guard/keys [table name]} (pg/rows tx groundwork-guards {})
              :when (not (guarded table))]
        (exec! tx (str "DROP TRIGGER IF EXISTS \"" name "\" ON \"" table "\"")))
      (doseq [kind      (rules/guarded-kinds description)
              statement (rules/guard-sql description kind)]
        (exec! tx statement)))
    ;; the functions a query file narrows a list with, one per action and
    ;; kind, and the state denies they leave out
    (let [denies (filter :scope (rules/denies description))
          mays   (distinct (map (juxt :action :kind) (rules/sentences description)))]
      ;; made afresh each start: an id column's type may have changed
      (doseq [{:function/keys [name]} (pg/rows tx groundwork-functions {})]
        (exec! tx (str "DROP FUNCTION IF EXISTS groundwork.\"" name "\"()")))
      (doseq [d denies]
        (exec! tx (rules/deny-sql-statement description d)))
      (doseq [[action kind] mays
              :let [{t :table id :id} (get-in description [:records kind])
                    id-type (some #(when (= (names/key->column id) (:column/name %)) (:column/type %))
                                  (pg/rows tx columns-of {:table (clojure.core/name t)}))]]
        (exec! tx (rules/may-sql-statement description action kind id-type))))))

(defn install-fill!
  "On every record table under another record, a rule that fills a row's
   scope from its parent when it's inserted without one. Run at start; safe
   to run again."
  [{:keys [db description]}]
  (pg/with-transaction [tx db]
    (doseq [link      (rules/parent-links description)
            statement (rules/fill-sql link)]
      (exec! tx statement))))

(defn compile-description
  "What checking needs, read once from a checked description."
  [description]
  (let [ss (rules/sentences description)]
    {:description     description
     :sentences       ss
     :denies          (rules/denies description)
     :by-action       (group-by :action ss)
     :by-action-kind  (group-by (juxt :action :kind) ss)
     :compiled        (into {} (for [[k group] (group-by (juxt :action :kind) ss)]
                                 [k (rules/compile-sentences group "r")]))
     :open            (into {} (for [action (distinct (map :action ss))
                                     :let [level (rules/open-level description action)]
                                     :when level]
                                 [action level]))
     :visitor-actions (rules/visitor-actions description)
     :open-tables     (rules/open-read-tables description)}))

(defn start
  "The permissions part: check the description, against the database too,
   read every sentence, put up the wall, the vias' functions and guards,
   and the fill rules."
  [{:keys [settings db]}]
  (let [description (rules/check (settings/check-part! :permissions settings))]
    (check-database! db description)
    (let [perms (assoc (compile-description description) :db db)]
      (install-wall! perms)
      (install-fill! perms)
      perms)))
