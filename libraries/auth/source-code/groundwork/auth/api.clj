(ns groundwork.auth.api
  "Who is asking, and what may they do. The api of groundwork.auth, in two
   halves.

   SESSIONS: who is asking. A session is a row in Postgres, the browser's
   cookie holds only random bytes, the table only their fingerprint. The
   app's own login proves who someone is and calls sign-in!; signing out
   deletes rows, so access ends at once on every server.

   ACCESS: what they may do. The app's kinds of scope (a shop) with their
   actions and roles; scopes, roles and memberships as rows; the :access
   sentences on table maps turned into one SQL condition per action; the
   check put in front of every handler; the gates (Postgres's row rules)
   from the same conditions; and the leak test."
  (:require
   [groundwork.auth.server-code.access.access :as access]
   [groundwork.auth.server-code.sessions.sessions :as sessions]))

;; -----------------------------------------------------------------------------
;; Sessions

(def sessions-table
  "The sessions, in Groundwork's own folder."
  sessions/sessions-table)

(defn sign-in!
  "Makes a session for a person whose login the app has proven. Returns
   {:set-cookie text} for the answer's Set-Cookie header."
  [pool person-id settings]
  (sessions/sign-in! pool person-id settings))

(defn sign-out!
  "Ends the request's session at once. Returns {:set-cookie text} that
   deletes the cookie."
  [pool request settings]
  (sessions/sign-out! pool request settings))

(defn sign-out-everywhere!
  "Ends every session of a person at once (signing out everywhere, a
   removed member). Returns how many ended."
  [pool person-id]
  (sessions/sign-out-everywhere! pool person-id))

(defn delete-ended-sessions!
  "Forgets sessions that have ended; for a nightly chore. Returns how many."
  [pool]
  (sessions/delete-ended-sessions! pool))

(defn make-find-session-step
  "A step for the start of a handler: a request that changes something must
   come from the app's own pages (its Origin), or it is answered with an
   error; then :groundwork.auth/person-id is added to the context, nil
   when nobody is signed in. Reads the request at
   :groundwork.transport/request."
  [pool settings]
  (sessions/make-find-session-step pool settings))

;; -----------------------------------------------------------------------------
;; Access

(def access-tables
  "The scopes, their roles and actions, and the memberships, in Groundwork's folder."
  access/access-tables)

(defn check-scope-kinds
  "Returns the app's scope kinds; throws with every problem at once (a role
   naming an action its kind doesn't have, an action not saying what it does
   to rows, a role named \"Owner\")."
  [scope-kinds]
  (access/check-scope-kinds scope-kinds))

(defn add-role!
  "Gives a scope a role allowing these actions, each one of the kind's."
  [transaction scope-kinds scope role-name actions]
  (access/add-role! transaction scope-kinds scope role-name actions))

(defn add-membership!
  "Puts a person in a scope with one of the scope's roles."
  [transaction scope person-id role-name]
  (access/add-membership! transaction scope person-id role-name))

(defn add-scope!
  "Makes a scope of the app's own row: its starting roles, Owner, and the
   founder as its Owner. Call it in the transaction that makes the row."
  [transaction scope-kinds kind scope-id founder-id]
  (access/add-scope! transaction scope-kinds kind scope-id founder-id))

(defn remove-membership!
  "Takes a person out of a scope. Whether they were in it."
  [transaction scope person-id]
  (access/remove-membership! transaction scope person-id))

(defn list-actions-of-person
  "Every scope a person is in, with the actions their role there allows:
   {[:shop id] #{:orders/see …}}."
  [transaction scope-kinds person-id]
  (access/list-actions-of-person transaction scope-kinds person-id))

(defn make-sql-conditions
  "The :access sentences of every table map, checked (unknown actions or
   columns, an open write without a :when: every problem at once) and
   turned into SQL conditions:

     {:orders {:actions    {:orders/see \"…\" :orders/edit \"…\"}
               :operations {:read \"…\" :insert \"…\" :update \"…\" :delete \"…\"}}}

   The check uses an action's condition; the gates use an operation's."
  [tables scope-kinds]
  (access/make-sql-conditions tables scope-kinds))

(defn install-access-functions!
  "Installs, or replaces, the two SQL functions the check and the gates read
   who is asking from: groundwork.find_asking_person() and
   groundwork.find_scopes_holding(kind, action). Run it before migrate:
   Postgres refuses a gate whose function doesn't exist yet."
  [pool]
  (access/install-access-functions! pool))

(defn put-access-into-handlers
  "Every handler (transport's table) checked for :action, :params-shape and
   :answer-shape, and for a sentence allowing its action on its :record;
   then each gets the check in front of its steps and runs them in one
   transaction that tells Postgres who is asking. A message found on no
   session, or naming a record its person may not reach, is answered
   \"Not found.\" before the handler's own steps.

     (access/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                :find-session-step (sessions/make-find-session-step pool settings)})

   The handler's steps reach the database through
   :groundwork.auth/transaction in their context."
  [handlers setup]
  (access/put-access-into-handlers handlers setup))

(defn put-gates-on-tables
  "The table maps, each with sentences given :gates (its :operations
   conditions) for groundwork.database to make Postgres's row rules from. A
   table that names a scope (references a scope kind's table) must have
   :access, or :access :public on purpose; otherwise this throws.

     (database/migrate! pool (access/put-gates-on-tables tables scope-kinds) changes)"
  [tables scope-kinds]
  (access/put-gates-on-tables tables scope-kinds))

(defn find-leaks
  "The leak test, for an app's own test suite: every handler (made ready by
   put-access-into-handlers) asked by the person of request (signed in to
   one scope) with foreign-ids, the ids of another scope's rows by table,
   {:orders [id …]}. A handler with a :record must answer \"Not found.\" for
   each; any other must give none of them back. Returns every leak; empty
   means none.

     (is (= [] (access/find-leaks ready-handlers anna-request {:orders [order-in-shop-9]})))"
  [handlers request foreign-ids]
  (access/find-leaks handlers request foreign-ids))
