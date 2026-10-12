(ns groundwork.permissions
  "Who may do what, on which record. The app describes its scopes, actions,
   roles and records (see groundwork.permissions.rules); Groundwork keeps
   scopes, roles, memberships, extra actions and invitations in its own
   tables, checks the description against the database at start, and turns
   each rule into one SQL condition.

     (allowed? perms {:person p :flags #{} :action :jobs/move :record [:job id]})
     (check! perms {… :also {:machine [machine-id]}})   ; every id in the request
     (list-filter perms {:person p :flags #{} :action :orders/see :kind :order})

   A record is checked through its own columns, never a scope the browser
   names; a record that isn't the person's answers not-found.

   The database wall: at start, every record kind's table gets row-level
   security. A transaction sees only rows of the scopes verified for it
   (in-wall), or every row when it runs as the system (as-system). Both last
   one transaction and can't be nested, so neither leaks into the next.

   Grants and roles live in Postgres and change at once; nothing is cached.
   The endpoints of parquery will declare their permission and run their work
   in-wall; this namespace is what they call."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings]
   [next.jdbc :as jdbc])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(pg/defsql set-bypass "groundwork/permissions/set_bypass.sql")
(pg/defsql set-wall "groundwork/permissions/set_wall.sql")
(pg/defsql set-member-scopes "groundwork/permissions/set_member_scopes.sql")
(pg/defsql columns-of "groundwork/permissions/columns_of.sql")
(pg/defsql links-between "groundwork/permissions/links_between.sql")
(pg/defsql holds-sql "groundwork/permissions/holds.sql")
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

(defn in-wall
  "Run (f tx) in its own transaction that sees only rows of `scopes` (the
   ones verified for this request) and the person's own records. With an
   :action and the :kind of record it's about, that kind shows only the rows
   the action's rule allows (without a kind, every kind the action has a
   rule for); every other kind only rows of the scopes the person is a
   member of. See rules/policy-sql. With :read-only? true, the transaction
   can't change anything."
  [perms {:keys [person scopes action kind read-only?]} f]
  (own-transaction perms
                   {:read-only? (boolean read-only?)}
                   (fn [tx]
                     (pg/execute! tx set-wall {:scopes  (str "{" (str/join "," scopes) "}")
                                               :person  (if person (str person) "")
                                               :action  (if action (rules/action-name action) "")
                                               :kind    (if kind (rules/action-name kind) "")
                                               :writing (if read-only? "" "on")})
                     (f tx))))

(defn in-scopes
  "Run (f tx) in its own transaction that sees every row of `scopes` and
   nothing else: work done for a scope rather than a person, like a job
   that names its scope (see groundwork.jobs)."
  [perms scopes f]
  (in-wall perms {:scopes scopes}
           (fn [tx]
             (pg/execute! tx set-member-scopes {:scopes (str "{" (str/join "," scopes) "}")})
             (f tx))))

(defn- query
  "Rows of compiled SQL on a transaction's connection."
  [tx sql params]
  (jdbc/execute! (::pg/conn tx) (into [sql] params)))

;; =============================================================================
;; Checking
;; =============================================================================

(defn- rule [{:keys [rules]} action kind]
  (get rules [action kind]))

(defn declared?
  "Whether the description has a rule for an action on a kind of record."
  [perms action kind]
  (some? (rule perms action kind)))

(defn- record-spec [{:keys [description]} kind]
  (or (get-in description [:records kind])
      (throw (ex-info (str "No record kind " kind) {:type ::unknown-kind :kind kind}))))

(defn allowed?
  "Whether a person may do an action on one record: [kind id]."
  [perms {:keys [person flags action] [kind id] :record}]
  (let [{t :table id-col :id} (record-spec perms kind)
        {:keys [sql params]}  (rule perms action kind)]
    (and (some? person)
         (some? sql)
         (not (rules/blocked? (:description perms) action flags))
         (as-system perms
                    (fn [tx]
                      (boolean (seq (query tx (str "SELECT 1 FROM " (rules/quote-table t) " r WHERE r."
                                                   (rules/quote-column id-col) " = ? AND " sql)
                                           (into [id] (rules/bind params person))))))))))

(defn scopes-of
  "The scope ids a record belongs to, through its own columns."
  [perms [kind id]]
  (let [{t :table id-col :id :as spec} (record-spec perms kind)
        arrows                         (let [in (:in spec)] (if (keyword? (first in)) [in] in))]
    (as-system perms
               (fn [tx]
                 (into #{}
                       (comp (mapcat vals) (remove nil?))
                       (query tx (str "SELECT "
                                      (str/join ", " (map-indexed (fn [i arrow]
                                                                    (str (rules/scope-sql "r" arrow)
                                                                         " AS s" i))
                                                                  arrows))
                                      " FROM " (rules/quote-table t) " r WHERE r." (rules/quote-column id-col) " = ?")
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
   {:sql … :params […]} over alias r, for a list query's WHERE. FALSE when
   the session blocks the action or nothing grants it."
  [perms {:keys [person flags action kind]}]
  (let [{:keys [sql params]} (rule perms action kind)]
    (if (or (nil? person) (nil? sql) (rules/blocked? (:description perms) action flags))
      {:sql "FALSE" :params []}
      {:sql sql :params (rules/bind params person)})))

(defn blocked?
  "Whether a session's flags block an action."
  [perms action flags]
  (rules/blocked? (:description perms) action flags))

(defn action-kind
  "The kind of scope an action belongs to, or nil for an unknown action."
  [perms action]
  (get (rules/action-kinds (:description perms)) action))

(defn scopes-under
  "The scope and every scope below it where the person holds the action,
   or a link gives it to them: what a request about that scope may see.
   Empty when nothing grants it there, or the session blocks it."
  [perms {:keys [person flags action scope]}]
  (if (or (nil? person) (nil? scope) (blocked? perms action flags))
    #{}
    (let [links (rules/linked-scope-clauses (:description perms) action "scope")]
      (as-system perms
                 (fn [tx]
                   (into #{}
                         (map (comp val first))
                         (query tx (str "WITH RECURSIVE below(scope) AS ("
                                        " SELECT CAST(? AS uuid)"
                                        " UNION SELECT s.scope__id FROM groundwork.scopes s"
                                        " JOIN below b ON s.scope__parent_id = b.scope)"
                                        " SELECT scope FROM below"
                                        " WHERE scope IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))"
                                        (apply str (map #(str " OR " (first %)) links)))
                                (into [(str scope) (str person) (rules/action-name action)]
                                      (mapcat #(rules/bind (second %) person) links)))))))))

(defn scopes-listed
  "The scopes of every record of a kind the person may do the action on
   through a scope (a role there, an extra action, a link): what a request
   listing them may see. Records that are the person's own (a person column)
   don't count, the wall shows those by the person. Empty when nothing is
   granted."
  [perms {:keys [person flags action kind]}]
  (let [description (update-in (:description perms) [:records kind] dissoc :people)
        rule        (rules/compile-rule description action kind)
        {t :table :as spec} (record-spec perms kind)
        arrows      (let [in (:in spec)] (if (keyword? (first in)) [in] in))]
    (if (or (nil? person) (nil? rule) (blocked? perms action flags))
      #{}
      (as-system perms
                 (fn [tx]
                   (into #{}
                         (comp (mapcat vals) (remove nil?))
                         (query tx (str "SELECT DISTINCT "
                                        (str/join ", " (map-indexed (fn [i arrow] (str (rules/scope-sql "r" arrow) " AS s" i))
                                                                    arrows))
                                        " FROM " (rules/quote-table t) " r WHERE " (:sql rule))
                                (rules/bind (:params rule) person))))))))

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
   role and the kind's starting roles. Returns the scope's id."
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
  "The SQL that adds a parent link: the parent's unique pair, if it has
   none, and the child's foreign key."
  [{t :table :keys [scope-column parent-column parent-table parent-id parent-scope]}]
  (str "ALTER TABLE " parent-table " ADD UNIQUE (" parent-id ", " parent-scope ");  -- if it has none\n"
       "ALTER TABLE " t " ADD FOREIGN KEY (" parent-column ", " scope-column ")\n"
       "  REFERENCES " parent-table " (" parent-id ", " parent-scope ") ON UPDATE CASCADE;"))

(defn- check-scope-columns!
  "Every record table has its scope columns. A record under another record,
   and a record others hang under, keep them NOT NULL: their matching link
   holds only then. A record on its own may leave one empty: the row is in
   no scope through it yet, and only its people and the system see it."
  [db description]
  (let [parents     (into {} (map (juxt :kind identity)) (rules/parent-links description))
        above       (set (keep (comp first :parent val) (:records description)))
        must-fill?  (fn [kind] (or (contains? parents kind) (contains? above kind)))]
    (doseq [{:keys [kind table column]} (rules/scope-columns description)]
      (let [have (into {} (map (juxt :column/name :column/not-null)) (pg/rows db columns-of {:table table}))
            link (parents kind)]
        (cond
          (not (contains? have column))
          (bad (str "Table " table " needs its own scope column " column)
               {:table table :column column}
               (str "ALTER TABLE " table " ADD COLUMN " column " uuid;\n"
                    (if link
                      (str "UPDATE " table " c SET " column " = p." (:parent-scope link)
                           " FROM " (:parent-table link) " p WHERE p." (:parent-id link)
                           " = c." (:parent-column link) ";\n")
                      (str "-- fill " column " for the rows already there\n"))
                    "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;"))

          (and (must-fill? kind) (not (have column)))
          (bad (str "Scope column " table "." column " must be NOT NULL: records hang under " table
                    ", or it hangs under another")
               {:table table :column column}
               (str "ALTER TABLE " table " ALTER COLUMN " column " SET NOT NULL;")))))))

(defn- check-parent-links!
  "Every record under another record is tied to it by its matching link."
  [db description]
  (doseq [{t :table :keys [scope-column parent-column parent-table parent-id parent-scope] :as link}
          (rules/parent-links description)]
    (let [wanted #{[parent-column parent-id] [scope-column parent-scope]}
          found  (first (filter #(= wanted (set (map vector (:link/child-columns %) (:link/parent-columns %))))
                                (pg/rows db links-between {:child t :parent parent-table})))]
      (cond
        (nil? found)
        (bad (str "Table " t " needs a link to " parent-table " that keeps its scope its parent's: "
                  "a foreign key (" parent-column ", " scope-column ") → " parent-table
                  " (" parent-id ", " parent-scope ") ON UPDATE CASCADE")
             {:table t :parent parent-table}
             (link-sql link))

        (not (:link/cascades found))
        (bad (str "The link from " t " to " parent-table " must be ON UPDATE CASCADE, so "
                  t " follows its parent when the parent moves to another scope")
             {:table t :parent parent-table}
             (str "-- drop the existing foreign key (" parent-column ", " scope-column "), then:\n"
                  (link-sql link)))))))

(defn- check-database!
  "Every table and column the description names must exist; every record
   table names its scope; every record under another is tied to it."
  [db description]
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
  (check-parent-links! db description))

(defn install-wall!
  "Row-level security on every record kind's table: its own rows are
   visible only through the wall, by the transaction's scopes and action
   (rules/policy-sql). Forced, so the tables' owner is held to it too. The
   links' functions first, which the rules call. Run at start; safe to run
   again."
  [{:keys [db description rules]}]
  (pg/with-transaction [tx db]
    (doseq [link (:links description)]
      (jdbc/execute! (::pg/conn tx) [(rules/link-sql link)]))
    (doseq [[kind {t :table}] (:records description)
            :let [table                 (rules/quote-table t)
                  {:keys [using check]} (rules/policy-sql description rules kind)]]
      (jdbc/execute! (::pg/conn tx) [(str "ALTER TABLE " table " ENABLE ROW LEVEL SECURITY")])
      (jdbc/execute! (::pg/conn tx) [(str "ALTER TABLE " table " FORCE ROW LEVEL SECURITY")])
      (jdbc/execute! (::pg/conn tx) [(str "DROP POLICY IF EXISTS groundwork_wall ON " table)])
      (jdbc/execute! (::pg/conn tx) [(str "CREATE POLICY groundwork_wall ON " table
                                          " USING (" using ") WITH CHECK (" check ")")]))))

(defn install-fill!
  "On every record table under another record, a rule that fills a row's
   scope from its parent when it's inserted without one. Run at start; safe
   to run again."
  [{:keys [db description]}]
  (pg/with-transaction [tx db]
    (doseq [link      (rules/parent-links description)
            statement (rules/fill-sql link)]
      (jdbc/execute! (::pg/conn tx) [statement]))))

(defn start
  "The permissions part: check the description, against the database too,
   compile every rule, put up the wall and the fill rules."
  [{:keys [settings db]}]
  (let [description (rules/check (settings/check-part! :permissions settings))]
    (check-database! db description)
    (let [perms {:db db :description description :rules (rules/rules-of description)}]
      (install-wall! perms)
      (install-fill! perms)
      perms)))
