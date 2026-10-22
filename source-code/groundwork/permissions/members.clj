(ns groundwork.permissions.members
  "Scopes, roles and members, written: a new scope with its owner and
   starting roles, a role's actions, invitations, a member's role, extra
   actions. Every change is made by someone who manages the team
   (:groundwork/team) and holds what they hand out; a scope always keeps
   an owner, and only an owner makes or unmakes one."
  (:require
   [clojure.string :as str]
   [groundwork.permissions :as-alias permissions]
   [groundwork.permissions.check :as check]
   [groundwork.permissions.description :as description]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(pg/defsql count-of-kind "groundwork/permissions/members/count_of_kind.sql")
(pg/defsql insert-scope "groundwork/permissions/members/insert_scope.sql")
(pg/defsql insert-role "groundwork/permissions/members/insert_role.sql")
(pg/defsql insert-role-action "groundwork/permissions/members/insert_role_action.sql")
(pg/defsql delete-role-actions "groundwork/permissions/members/delete_role_actions.sql")
(pg/defsql role-by-name "groundwork/permissions/members/role_by_name.sql")
(pg/defsql membership "groundwork/permissions/members/membership.sql")
(pg/defsql upsert-membership "groundwork/permissions/members/upsert_membership.sql")
(pg/defsql delete-membership "groundwork/permissions/members/delete_membership.sql")
(pg/defsql count-owners "groundwork/permissions/members/count_owners.sql")
(pg/defsql insert-extra-action "groundwork/permissions/members/insert_extra_action.sql")
(pg/defsql delete-extra-action "groundwork/permissions/members/delete_extra_action.sql")
(pg/defsql insert-invitation "groundwork/permissions/members/insert_invitation.sql")
(pg/defsql take-invitation "groundwork/permissions/members/take_invitation.sql")

(def owner-role "Owner")

(defn- not-found [what]
  (ex-info "Not found" (assoc what :type ::permissions/not-found)))

(defn- refused [message what]
  (ex-info message (assoc what :type ::permissions/refused)))

(defn- query
  "Rows of compiled SQL on a transaction's connection."
  [tx sql params]
  (jdbc/execute! (::pg/conn tx) (into [sql] params)))

(defn- kind-of [tx scope-id]
  (some-> (first (query tx "SELECT scope__kind FROM groundwork.scopes WHERE scope__id = ?" [scope-id]))
          :scopes/scope__kind
          keyword))

(defn- set-role-actions-in! [tx role-id actions]
  (pg/execute! tx delete-role-actions {:role/id role-id})
  (doseq [action actions]
    (pg/execute! tx insert-role-action {:role-action/role-id role-id
                                        :role-action/action  (description/action-name action)})))

(defn create-scope!
  "A new place: its kind, its parent (or nil), its owner. It gets the owner
   role and the kind's starting roles. A kind with exactly one scope
   (:single? true) refuses a second. Returns the scope's id."
  [perms {:keys [kind id parent owner]}]
  (let [spec (get-in perms [:description :scopes kind])]
    (when-not spec
      (throw (ex-info (str "No scope kind " kind) {:type ::permissions/unknown-kind :kind kind})))
    (check/as-system perms
               (fn [tx]
                 (when parent
                   (when-not (= (:parent spec) (kind-of tx parent))
                     (throw (ex-info (str "A " kind "'s parent must be a " (:parent spec))
                                     {:type ::permissions/bad-parent :parent parent}))))
                 (when (:single? spec)
                   ;; one at a time, so two starts can't both make the first
                   (query tx "SELECT pg_advisory_xact_lock(hashtext(?))" [(str "groundwork.single/" (name kind))])
                   (when (pos? (:scopes/count (pg/row tx count-of-kind {:scope/kind (name kind)})))
                     (throw (ex-info (str "A " (name kind) " has exactly one scope, and it exists")
                                     {:type ::permissions/single-scope :kind kind}))))
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
      (throw (ex-info (str "No role " role-name) {:type ::permissions/unknown-role :role role-name}))))

(defn- owner? [tx person scope-id]
  (boolean (:role/owner (pg/row tx membership {:membership/person-id person :membership/scope-id scope-id}))))

(defn- may-manage!
  "The acting person must hold :groundwork/team in the scope, and every
   action they hand out (or take back)."
  [perms by scope-id actions]
  (when-not (check/holds? perms by description/team-action scope-id)
    (throw (refused "Only someone who manages the team can do this" {:scope scope-id})))
  (doseq [action actions
          :when (not (check/holds? perms by action scope-id))]
    (throw (refused (str "Only someone who holds " action " can give or take it") {:action action}))))

(defn- check-role-actions! [perms tx scope-id actions]
  (let [allowed (description/allowed-in-role? (:description perms) (kind-of tx scope-id))]
    (doseq [action actions
            :when (not (allowed action))]
      (throw (ex-info (str action " isn't an action of this scope") {:type ::permissions/unknown-action :action action})))))

(defn create-role!
  "A new role in a scope, with its actions."
  [perms {:keys [by scope role actions]}]
  (may-manage! perms by scope actions)
  (check/as-system perms
             (fn [tx]
               (check-role-actions! perms tx scope actions)
               (let [role-id (:role/id (pg/row tx insert-role {:role/scope-id scope :role/name role :role/owner false}))]
                 (set-role-actions-in! tx role-id actions)
                 role-id))))

(defn set-role-actions!
  "Set a role's actions (a scope's roles grid). The owner role can't be
   edited; every action added or removed must be one the editor holds."
  [perms {:keys [by scope role actions]}]
  (let [{:role/keys [id owner] before :role/actions} (check/as-system perms #(role-in % scope role))
        before                                       (set (map keyword before))
        changed                                      (into (apply disj (set actions) before)
                                                           (apply disj before actions))]
    (when owner
      (throw (refused "The owner role always holds everything" {:role role})))
    (may-manage! perms by scope changed)
    (check/as-system perms
               (fn [tx]
                 (check-role-actions! perms tx scope actions)
                 (set-role-actions-in! tx id actions)))))

(defn invite!
  "Invite an email into a scope with a role, valid for `days`. Returns the
   token for the invitation link; only its SHA-256 is kept."
  [perms {:keys [by scope email role days]}]
  (let [{:role/keys [id owner actions]} (check/as-system perms #(role-in % scope role))]
    (may-manage! perms by scope (map keyword actions))
    (when (and owner (not (check/as-system perms #(owner? % by scope))))
      (throw (refused "Only an owner can invite an owner" {:role role})))
    (let [token (let [bytes (byte-array 32)]
                  (.nextBytes (SecureRandom.) bytes)
                  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))]
      (check/as-system perms
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
  (check/as-system perms
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
  (let [{:role/keys [id owner actions]} (check/as-system perms #(role-in % scope role))]
    (may-manage! perms by scope (map keyword actions))
    (check/as-system perms
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
  (check/as-system perms
             (fn [tx]
               (when (and (owner? tx person scope) (not (owner? tx by scope)))
                 (throw (refused "Only an owner can remove an owner" {:member person})))
               (keep-an-owner! tx scope person)
               (pg/execute! tx delete-membership {:membership/person-id person :membership/scope-id scope}))))

(defn grant-extra!
  "One action for a person in a scope, on top of their role."
  [perms {:keys [by scope person action]}]
  (may-manage! perms by scope [action])
  (check/as-system perms
             (fn [tx]
               (check-role-actions! perms tx scope [action])
               (pg/execute! tx insert-extra-action {:extra-action/person-id person
                                                    :extra-action/scope-id  scope
                                                    :extra-action/action    (description/action-name action)}))))

(defn revoke-extra!
  "Take an extra action back."
  [perms {:keys [by scope person action]}]
  (may-manage! perms by scope [action])
  (check/as-system perms
             (fn [tx]
               (pg/execute! tx delete-extra-action {:extra-action/person-id person
                                                    :extra-action/scope-id  scope
                                                    :extra-action/action    (description/action-name action)}))))
