(ns groundwork.permissions
  "Who may do what, on which record: the door an app uses. The app
   describes its scopes, actions, records and its rules, one list of
   sentences (see groundwork.permissions.description); Groundwork keeps
   scopes, roles, memberships, extra actions and invitations in its own
   tables, checks the description against the database at start, and turns
   each sentence into one SQL condition (groundwork.permissions.sql).

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

   Grants and roles live in Postgres and change at once; nothing is cached.

   The machinery: groundwork.permissions.check (checking a request),
   .members (scopes, roles and members, written), .install (the database
   checked and the wall put up at start), .leaks (the leak test). Every
   error they throw has a :type in this namespace: ::not-found, ::refused,
   ::bad-description, …"
  (:require
   [groundwork.permissions.check :as check]
   [groundwork.permissions.description :as description]
   [groundwork.permissions.install :as install]
   [groundwork.permissions.members :as members]
   [groundwork.settings :as settings]))

;; =============================================================================
;; Transactions
;; =============================================================================

(defn as-system
  "Run (f tx) in its own transaction where the wall lets every row through
   (see groundwork.permissions.check/as-system)."
  [perms f]
  (check/as-system perms f))

(defn in-wall
  "Run (f tx) in its own transaction behind the wall: {:person :visitor
   :scopes :write-scopes :flags :read-only?} (see
   groundwork.permissions.check/in-wall)."
  [perms opts f]
  (check/in-wall perms opts f))

(defn in-scopes
  "Run (f tx) in its own transaction that sees and writes every row of
   `scopes` and nothing else (see groundwork.permissions.check/in-scopes)."
  [perms scopes f]
  (check/in-scopes perms scopes f))

;; =============================================================================
;; Checking
;; =============================================================================

(defn allowed?
  "Whether a person (or a visitor) may do an action on one record:
   {:person :visitor :flags :action :record [kind id]}."
  [perms request]
  (check/allowed? perms request))

(defn check!
  "The record's scopes when the person may do the action on it, else throws
   ::not-found; every other id the request carries (:also {kind [ids]})
   must belong to one of those scopes too."
  [perms request]
  (check/check! perms request))

(defn list-filter
  "The condition for every record of a kind the person may do the action
   on: {:sql … :params […]} over alias r, for a list query's WHERE."
  [perms request]
  (check/list-filter perms request))

(defn scopes-of
  "The scope ids a record belongs to, through its own columns."
  [perms record]
  (check/scopes-of perms record))

(defn holds?
  "Whether a person holds an action in a scope, or in one above it."
  [perms person action scope-id]
  (check/holds? perms person action scope-id))

;; =============================================================================
;; Scopes, roles and members
;; =============================================================================

(def owner-role
  "The role every scope's owner has; it holds everything."
  members/owner-role)

(defn create-scope!
  "A new place: {:kind :id :parent :owner}; it gets the owner role and the
   kind's starting roles. Returns the scope's id."
  [perms scope]
  (members/create-scope! perms scope))

(defn create-role!
  "A new role in a scope, with its actions: {:by :scope :role :actions}."
  [perms role]
  (members/create-role! perms role))

(defn set-role-actions!
  "Set a role's actions: {:by :scope :role :actions}."
  [perms role]
  (members/set-role-actions! perms role))

(defn invite!
  "Invite an email into a scope with a role: {:by :scope :email :role
   :days}. Returns the token for the invitation link."
  [perms invitation]
  (members/invite! perms invitation))

(defn accept-invitation!
  "The signed-in person joins with the invitation's role: {:token :person
   :email}. Returns the scope's id."
  [perms acceptance]
  (members/accept-invitation! perms acceptance))

(defn change-role!
  "Give a member another role: {:by :scope :person :role}."
  [perms change]
  (members/change-role! perms change))

(defn remove-member!
  "Take a person out of a scope: {:by :scope :person}."
  [perms removal]
  (members/remove-member! perms removal))

(defn grant-extra!
  "One action for a person in a scope, on top of their role: {:by :scope
   :person :action}."
  [perms grant]
  (members/grant-extra! perms grant))

(defn revoke-extra!
  "Take an extra action back: {:by :scope :person :action}."
  [perms grant]
  (members/revoke-extra! perms grant))

;; =============================================================================
;; The part
;; =============================================================================

(defn start
  "The permissions part: check the description, against the database too,
   read every sentence, put up the wall, the vias' functions and guards,
   and the fill rules."
  [{:keys [settings db]}]
  (let [description (description/check (settings/check-part! :permissions settings))]
    (install/check-database! db description)
    (let [perms (assoc (check/compile-description description) :db db)]
      (install/install-wall! perms)
      (install/install-fill! perms)
      perms)))
