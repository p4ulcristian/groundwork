(ns groundwork.permissions.check
  "Checking a request against the sentences: the transactions (the system,
   the wall, a job's scopes), whether a person may do an action on a
   record, the scopes a request is about, a list's filter, the browser's
   \"can I?\" (grants), the scopes a websocket line listens in, and why a
   person may or may not (for the REPL).

   A record is checked through its own columns, never a scope the browser
   names; a record that isn't the person's answers not-found.

   The database wall: a transaction sees only rows of the scopes verified
   for it (in-wall), as the sentences allow, or every row when it runs as
   the system (as-system). Both last one transaction and can't be nested,
   so neither leaks into the next. Grants and roles live in Postgres and
   change at once; nothing is cached."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [groundwork.permissions :as-alias permissions]
   [groundwork.permissions.description :as description]
   [groundwork.permissions.sql :as sql]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(pg/defsql set-bypass "groundwork/permissions/check/set_bypass.sql")
(pg/defsql set-wall "groundwork/permissions/check/set_wall.sql")
(pg/defsql set-member-scopes "groundwork/permissions/check/set_member_scopes.sql")
(pg/defsql holds "groundwork/permissions/check/holds.sql")
(pg/defsql scope-of-kind "groundwork/permissions/check/scope_of_kind.sql")
(pg/defsql memberships-above "groundwork/permissions/check/memberships_above.sql")

(defn- not-found [what]
  (ex-info "Not found" (assoc what :type ::permissions/not-found)))

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
                    {:type ::permissions/nested-transaction})))
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
      (throw (ex-info (str "No record kind " kind) {:type ::permissions/unknown-kind :kind kind}))))

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
  (str "r." (sql/quote-column id-col) (if (string? id) "::text = ?" " = ?")))

(defn open-level
  "Who an action is open to through its sentences: :anyone, :signed-in, or
   nil."
  [perms action]
  (get-in perms [:open action]))

(defn action-kind
  "The kind of scope an action belongs to, or nil for an unknown action."
  [perms action]
  (get (description/action-kinds (:description perms)) action))

(defn writes?
  "Whether an action inserts, updates or deletes."
  [perms action]
  (description/writes? (:description perms) action))

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
                          (description/deny-applies? (:description perms) d action))]
           d)))

(defn blocked?
  "Whether a session's flags deny an action."
  [perms action flags]
  (some? (flag-deny perms action flags)))

(defn- state-denies [perms action]
  (filter #(and (:scope %) (description/deny-applies? (:description perms) % action)) (:denies perms)))

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
                        [c ps]            (sql/condition-sql "s" condition)]
                  row (query tx (str "SELECT s." (sql/quote-column id) "::text AS denied FROM "
                                     (sql/quote-table t) " s WHERE s." (sql/quote-column id)
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
  (let [cols (keys (description/scope-columns record))]
    (if (seq cols)
      (str/join ", " (map-indexed (fn [i c] (str "r." (sql/quote-column c) " AS s" i)) cols))
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
        {where :sql params :params} (get-in perms [:compiled [action kind]])
        ;; a visitor is someone not signed in: signed in, there is none
        visitor               (when (nil? person) visitor)]
    (cond
      (nil? where)                      {:reason :none}
      (flag-deny perms action flags)    {:reason :denied :deny (flag-deny perms action flags)}
      :else
      (as-system perms
                 (fn [tx]
                   (let [row (first (query tx (str "SELECT " (scope-select spec) " FROM " (sql/quote-table t) " r WHERE "
                                                   (id-is id-col id) " AND " where)
                                           (into [id] (sql/bind params person visitor))))]
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
                               (let [[w wps] (sql/who-sql "r" (:who via))
                                     [c cps] (if (:when via) (sql/condition-sql "r" (:when via)) ["TRUE" []])]
                                 (seq (query tx (str "SELECT 1 FROM " (sql/quote-table t) " r WHERE " (id-is id-col id)
                                                     " AND " w " AND " c)
                                             (into [id] (concat (sql/bind wps person visitor) cps))))))
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
                       (query tx (str "SELECT " (scope-select spec) " FROM " (sql/quote-table t) " r WHERE "
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
  (let [{where :sql params :params} (get-in perms [:compiled [action kind]])]
    (if (or (nil? where) (blocked? perms action flags))
      {:sql "FALSE" :params []}
      {:sql where :params (sql/bind params person visitor)})))

(defn- via-scope-position
  "Where a via offers a value for the row's scope column: the index into
   what it matches, or nil when it matches nothing that names the scope."
  [perms {:keys [kind via]}]
  (let [scope-cols (set (keys (description/scope-columns (get-in perms [:description :records kind]))))]
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
              [:person (description/action-name (:holding who))]]

             (= :member (:word who))
             (sql/who-sql "gwx" who)

             via
             (when-let [i (via-scope-position perms s)]
               [(str expr " IN (SELECT via_" (inc i) " FROM " (sql/via-function via)
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
  (let [clauses (if (= description/team-action action)
                  [["scope IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))"
                    [:person (description/action-name action)]]]
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
                                (into [(str scope)] (sql/bind (vec (mapcat second clauses)) person visitor)))))))))

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
                               [:person (description/action-name (:holding who))]]

                              via
                              (if-let [i (via-scope-position perms s)]
                                [(str "SELECT via_" (inc i) " AS scope FROM " (sql/via-function via)
                                      "(CAST(? AS uuid), CAST(? AS uuid), NULL)")
                                 [:person :visitor]]
                                ;; a via that names records, not their scope: the
                                ;; scopes of the records it gives
                                (let [[v vps] (sql/sentence-sql (dissoc s :when) "r")]
                                  [(str "SELECT DISTINCT x.scope FROM " (sql/quote-table t) " r"
                                        " CROSS JOIN LATERAL (VALUES "
                                        (str/join ", " (map #(str "(r." (sql/quote-column %) ")")
                                                            (keys (description/scope-columns spec))))
                                        ") AS x(scope) WHERE " v)
                                   vps]))))
                          ss))
            every  (distinct (keep (fn [{:keys [who]}]
                                     (when (and (= :member (:word who)) (not (:column who)))
                                       (sql/who-sql "gwx" who)))
                                   ss))]
        (as-system perms
                   (fn [tx]
                     {:scopes (if (seq parts)
                                (into #{}
                                      (comp (map (comp val first)) (remove nil?))
                                      (query tx (str/join " UNION " (map first parts))
                                             (sql/bind (vec (mapcat second parts)) person visitor)))
                                #{})
                      :every? (boolean (and (seq every)
                                            (:every (jdbc/execute-one!
                                                     (::pg/conn tx)
                                                     (into [(str "SELECT (" (str/join " OR " (map first every)) ") AS every")]
                                                           (sql/bind (vec (mapcat second every)) person visitor))
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
                                    [:person (description/action-name (:holding who))]]))
                               whos))
        every  (distinct (keep (fn [who] (when (and (= :member (:word who)) (not (:column who)))
                                           (sql/who-sql "gwx" who)))
                               whos))]
    (if (or (nil? person) (and (empty? parts) (empty? every)))
      {:scopes #{} :every? false}
      (as-system perms
                 (fn [tx]
                   {:scopes (if (seq parts)
                              (into #{} (map (comp val first))
                                    (query tx (str/join " UNION " (map first parts))
                                           (sql/bind (vec (mapcat second parts)) person)))
                              #{})
                    :every? (boolean (and (seq every)
                                          (:every (jdbc/execute-one!
                                                   (::pg/conn tx)
                                                   (into [(str "SELECT (" (str/join " OR " (map first every)) ") AS every")]
                                                         (sql/bind (vec (mapcat second every)) person))
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
                  [(description/action-name description/team-action) :person (description/action-name description/team-action)]]]
                (for [[action ss] (:by-action perms)
                      :when (not (blocked? perms action flags))
                      {:keys [who via] :as s} ss
                      :let [a (description/action-name action)
                            part (cond
                                   (= :anyone (:word who))
                                   ["SELECT CAST(NULL AS uuid), ?" [a]]

                                   (= :signed-in (:word who))
                                   ["SELECT CAST(NULL AS uuid), ? WHERE CAST(? AS uuid) IS NOT NULL" [a :person]]

                                   (and (= :member (:word who)) (:column who))
                                   ["SELECT s, ? FROM groundwork.scopes_holding(CAST(? AS uuid), ?) s"
                                    [a :person (description/action-name (:holding who))]]

                                   (= :member (:word who))
                                   (let [[w ps] (sql/who-sql "gwx" who)]
                                     [(str "SELECT CAST(NULL AS uuid), ? WHERE " w) (into [a] ps)])

                                   (= :anyone (get-in via [:who :word]))
                                   ["SELECT CAST(NULL AS uuid), ?" [a]]

                                   (= :signed-in (get-in via [:who :word]))
                                   ["SELECT CAST(NULL AS uuid), ? WHERE CAST(? AS uuid) IS NOT NULL" [a :person]]

                                   via
                                   (when-let [i (via-scope-position perms s)]
                                     [(str "SELECT via_" (inc i) ", ? FROM " (sql/via-function via)
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
                                                        (sql/bind (vec (mapcat second parts)) person visitor))
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
  (let [moves (filter :person-column (description/visitor-moves (:description perms)))]
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
               (:holding/yes (pg/row tx holds {:scope/id scope-id :person (str person)
                                               :action   (description/action-name action)})))))

;; =============================================================================
;; What checking needs, read once
;; =============================================================================

(defn compile-description
  "What checking needs, read once from a checked description."
  [description]
  (let [ss (description/sentences description)]
    {:description     description
     :sentences       ss
     :denies          (description/denies description)
     :by-action       (group-by :action ss)
     :by-action-kind  (group-by (juxt :action :kind) ss)
     :compiled        (into {} (for [[k group] (group-by (juxt :action :kind) ss)]
                                 [k (sql/compile-sentences group "r")]))
     :open            (into {} (for [action (distinct (map :action ss))
                                     :let [level (description/open-level description action)]
                                     :when level]
                                 [action level]))
     :visitor-actions (description/visitor-actions description)
     :open-tables     (description/open-read-tables description)}))

;; =============================================================================
;; Why, in plain words
;; =============================================================================

(defn- query-one [perms sql params]
  (as-system perms (fn [tx] (jdbc/execute-one! (::pg/conn tx) (into [sql] params)
                                                    {:builder-fn rs/as-unqualified-maps}))))

(defn- place [kind id] (str (name kind) " " id))

(defn- membership-lines-of
  [perms person action scope]
  (let [rows (pg/rows (:db perms) memberships-above {:scope/id scope :person (str person)
                                                     :action   (description/action-name action)})
        here (first rows)
        said (for [{:why/keys [scope-id kind depth role owner role-holds extra]} rows
                   :when (or role extra)
                   :let [where (str (place kind scope-id) (when (pos? depth) " (above it)"))]]
               (str/join " "
                         (remove nil?
                                 [(cond
                                    owner      (str person " is the owner of " where ": an owner holds everything.")
                                    role-holds (str person " is " role " in " where ", and " role " holds " action ".")
                                    role       (str person " is " role " in " where ", but " role " doesn't hold " action "."))
                                  (when extra (str person " has " action " as an extra action in " where "."))])))]
    (if (seq said)
      said
      [(str person " has no membership in " (place (:why/kind here) scope) " or above.")])))

(defn- membership-lines
  "What the person holds in a scope and above it."
  [perms person action scope]
  (if (nil? person)
    ["Nobody is signed in: no membership counts."]
    (membership-lines-of perms person action scope)))

(defn- holds-sql?
  "Whether [sql params] holds for one row of a kind, bound for a person or
   a visitor; read as the system."
  [perms {t :table id-col :id} id [condition params] person visitor]
  (boolean (:h (query-one perms (str "SELECT (" condition ") AS h FROM " (sql/quote-table t) " r WHERE r."
                                     (sql/quote-column id-col) " = ?")
                          (conj (sql/bind params person visitor) id)))))

(defn- column-of-row [perms {t :table id-col :id} id col]
  (:v (query-one perms (str "SELECT " (sql/quote-column col) " AS v FROM " (sql/quote-table t)
                            " WHERE " (sql/quote-column id-col) " = ?")
                 [id])))

(defn- rule-text [{:keys [n rule]}]
  (str "Rule " n " " (pr-str rule)))

(defn- who-lines
  "Why a who word holds, or doesn't, for one row."
  [perms person visitor {:keys [word column holding visitor?] :as who} spec id kind]
  (case word
    :anyone    ["It is for anyone."]
    :signed-in [(if person "It is for anyone signed in." "It is for anyone signed in, and nobody is.")]
    :me        (let [v  (column-of-row perms spec id column)
                     me (if visitor? visitor person)]
                 [(if (and me (= (str v) (str me)))
                    (str "The " (name kind) "'s " (name column) " is " me ": it's theirs.")
                    (str "The " (name kind) "'s " (name column) " isn't " (or me "anyone signed in") "."))])
    :member    (cond
                 column (let [scope (column-of-row perms spec id column)]
                          (if (nil? scope)
                            [(str "The " (name kind) "'s " (name column) " names no scope.")]
                            (cons (str "The " (name kind) " " id " is in " (place (:scope-kind who) scope) ".")
                                  (membership-lines perms person holding scope))))
                 (:kind who) (if-let [scope (:s (query-one perms "SELECT scope__id AS s FROM groundwork.scopes WHERE scope__kind = ?"
                                                           [(name (:kind who))]))]
                               (membership-lines perms person holding scope)
                               [(str "There's no " (name (:kind who)) " yet.")])
                 :else  (membership-lines perms person holding (:scope who)))))

(defn- deny-lines
  "The denies that say no to an action, for these scopes and flags."
  [perms action scopes flags]
  (concat
   (when-let [d (flag-deny perms action flags)]
     [(str "Denied: the session is " (name (:session d)) ", and " (rule-text d) " says no to " action ".")])
   (when (seq scopes)
     (for [[scope d] (as-system perms #(denied-scopes perms % action scopes))]
       (str "Denied: " (name (:scope d)) " " scope " is in the state " (pr-str (:when d)) ", and " (rule-text d)
            " says no to " action ".")))))

(defn- record-lines
  "Why a person may or may not do an action on one record, sentence by
   sentence, and the denies."
  [perms person visitor action [kind id] flags]
  (let [spec   (get-in perms [:description :records kind])
        ss     (get-in perms [:by-action-kind [action kind]])
        scopes (when spec (scopes-of perms [kind id]))
        there? (when spec (some? (query-one perms (str "SELECT 1 AS one FROM " (sql/quote-table (:table spec))
                                                       " WHERE " (sql/quote-column (:id spec)) " = ?")
                                            [id])))]
    (cond
      (nil? spec)   [(str "There's no record kind " kind " in the permission description.")]
      (empty? ss)   [(str "No sentence gives " action " on a " (name kind) ": no endpoint can declare it.")]
      (not there?)  [(str "There's no " (name kind) " " id ".")]
      :else
      (concat
       (for [{:keys [who via] condition :when :as s} ss
             line (let [holds? (holds-sql? perms spec id (sql/sentence-sql s "r") person visitor)]
                    (concat
                     [(str (rule-text s) (if holds? ": it holds." ": it doesn't hold."))]
                     (when who (who-lines perms person visitor who spec id kind))
                     (when via
                       [(if (holds-sql? perms spec id (sql/sentence-sql (dissoc s :when :who) "r") person visitor)
                          (str "A related row in " (:table via) " is there for them now.")
                          (str "No related row in " (:table via) " is there for them now (it needs " (pr-str (:rule s)) ")."))])
                     (when condition
                       [(if (holds-sql? perms spec id (sql/condition-sql "r" condition) nil nil)
                          (str "It needs " (pr-str condition) ": it holds now.")
                          (str "It needs " (pr-str condition) ": it doesn't hold now, so nobody may."))])))]
         line)
       (deny-lines perms action scopes flags)))))

(defn- scope-lines
  "Why a person may or may not do an action in a scope."
  [perms person action scope flags]
  (let [kind (some-> (query-one perms "SELECT scope__kind AS k FROM groundwork.scopes WHERE scope__id = ?" [scope])
                     :k keyword)
        ss   (get-in perms [:by-action action])]
    (cond
      (nil? kind) [(str "There's no scope " scope ".")]
      (empty? ss) [(str "No sentence gives " action ".")]
      :else
      (concat
       (distinct
        (for [{:keys [who via] :as s} ss
              line (cond
                     (and (= :member (:word who)) (:column who))
                     (cons (str (rule-text s) ": a member of the " (name (:scope-kind who)) " holding "
                                (:holding who) ".")
                           (membership-lines perms person (:holding who) scope))
                     (= :member (:word who))
                     [(str (rule-text s) ": a member of a fixed scope holding " (:holding who) ", in every scope.")]
                     (#{:anyone :signed-in} (:word who))
                     [(str (rule-text s) ": open to " (name (:word who)) ", in the scope a request names.")]
                     via
                     [(str (rule-text s) ": through related rows in " (:table via) ".")]
                     :else
                     [(str (rule-text s) ": a person's own rows, not a whole scope.")])]
          line))
       (deny-lines perms action #{scope} flags)))))

(defn why
  "Why a person may or may not do an action, on one record ([kind id]) or in
   a scope (its id), in plain words: each sentence that gives the action and
   whether it holds (the record's scope, the person's memberships, roles and
   extra actions there and above, a person column, a related row, a
   condition), and the deny that said no.

     (why perms peter :orders/edit [:order order-id])
     ;=> {:allowed? false
     ;    :because [\"Rule 2 {:allow :orders/edit …}: it doesn't hold.\" \"The order … is in shop ….\" …]}
     (why perms pia :orders/see acme :flags #{})
     (why perms nil :designs/mine [:design id] :visitor visitor-id)

   The REPL asks it for a running app (groundwork.repl/why)."
  [perms person action target & {:keys [flags visitor] :or {flags #{}}}]
  (let [record?  (vector? target)
        visitor  (when (nil? person) visitor)
        answer   (if record?
                   (and (some? (get-in perms [:description :records (first target)]))
                        (declared? perms action (first target))
                        (allowed? perms {:person person :visitor visitor :flags flags :action action :record target}))
                   (and (contains? (scopes-under perms {:person person :visitor visitor :flags flags :action action
                                                             :scope target})
                                   target)
                        (empty? (as-system perms #(denied-scopes perms % action #{target})))))]
    {:allowed? (boolean answer)
     :because  (vec (if record?
                      (record-lines perms person visitor action target flags)
                      (scope-lines perms person action target flags)))}))
