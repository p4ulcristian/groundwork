(ns groundwork.access.api
  "Who may see and do what. The api of groundwork.access; piece 1: who
   belongs where.

   The app says, in its code, its kinds of scope (a shop), their actions and
   the roles a new scope starts with (check-scope-kinds). Groundwork keeps,
   as rows: the scopes (the app's own row ids), each scope's roles, and the
   memberships (a person in a scope, with one role there). A scope's \"Owner\"
   holds every action of its kind.

     (access/add-scope! transaction scope-kinds :shop shop-id founder-id)
     (access/add-membership! transaction [:shop shop-id] anna-id \"Staff\")
     (access/list-actions-of-person transaction scope-kinds anna-id)
     ;=> {[:shop #uuid \"…\"] #{:orders/see :orders/edit}}

   Its tables are maps (access-tables) the app passes to groundwork.schema."
  (:require
   [clojure.string]
   [groundwork.access.server-code.scope-kinds :as scope-kinds]
   [groundwork.sql.api :as sql]))

(def access-tables
  "The scopes, their roles and actions, and the memberships, in Groundwork's folder."
  [{:table   :groundwork/scopes
    :columns [[:scope/kind      :string {:primary-key? true}]
              [:scope/id        :uuid   {:primary-key? true}]
              [:scope/parent-kind :string {:optional? true}]   ; for parent scopes, later
              [:scope/parent-id   :uuid   {:optional? true}]]}
   {:table   :groundwork/roles
    :columns [[:role/scope-kind :string {:primary-key? true}]
              [:role/scope-id   :uuid   {:primary-key? true}]
              [:role/name       :string {:primary-key? true}]]}
   {:table   :groundwork/role-actions
    :columns [[:role-action/scope-kind :string {:primary-key? true}]
              [:role-action/scope-id   :uuid   {:primary-key? true}]
              [:role-action/role       :string {:primary-key? true}]
              [:role-action/action     :string {:primary-key? true}]]}
   {:table   :groundwork/memberships
    :columns [[:membership/person-id  :uuid   {:primary-key? true}]
              [:membership/scope-kind :string {:primary-key? true}]
              [:membership/scope-id   :uuid   {:primary-key? true}]
              [:membership/role       :string]]
    :indexes [[:membership/scope-kind :membership/scope-id]]}])

(defn- make-query [file-name]
  (sql/make-query-from-sql-file (str "groundwork/access/sql_files/" file-name)))

(def ^:private insert-scope           (make-query "insert_scope.sql"))
(def ^:private insert-role            (make-query "insert_role.sql"))
(def ^:private insert-role-action     (make-query "insert_role_action.sql"))
(def ^:private find-role              (make-query "find_role.sql"))
(def ^:private insert-membership      (make-query "insert_membership.sql"))
(def ^:private delete-membership      (make-query "delete_membership.sql"))
(def ^:private find-actions-of-person (make-query "find_actions_of_person.sql"))

(defn- turn-keyword-into-text [k] (subs (str k) 1))

(defn- turn-text-into-keyword
  "\"orders/see\" -> :orders/see (with its namespace, unlike (keyword s))."
  [text]
  (apply keyword (clojure.string/split text #"/" 2)))

(defn check-scope-kinds
  "Returns the app's scope kinds; throws with every problem at once (a role
   naming an action its kind doesn't have, an action not saying what it does
   to rows, a role named \"Owner\")."
  [scope-kinds]
  (scope-kinds/check-scope-kinds scope-kinds))

(defn add-role!
  "Gives a scope a role allowing these actions, each one of the kind's."
  [transaction scope-kinds [kind scope-id] role-name actions]
  (doseq [action actions
          :when (not (contains? (get-in scope-kinds [kind :actions]) action))]
    (throw (ex-info (str kind " has no action " action) {:type :groundwork.access/unknown-action :action action})))
  (sql/run-statement! transaction insert-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
  (doseq [action actions]
    (sql/run-statement! transaction insert-role-action {:role-action/scope-kind (name kind) :role-action/scope-id scope-id
                                                        :role-action/role role-name :role-action/action (turn-keyword-into-text action)})))

(defn add-membership!
  "Puts a person in a scope with one of the scope's roles."
  [transaction [kind scope-id] person-id role-name]
  (when-not (sql/find-one-row transaction find-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
    (throw (ex-info (str "The scope has no role " role-name) {:type :groundwork.access/unknown-role :role role-name})))
  (sql/run-statement! transaction insert-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                     :membership/scope-id scope-id :membership/role role-name}))

(defn add-scope!
  "Makes a scope of the app's own row: its starting roles, Owner, and the
   founder as its Owner. Call it in the transaction that makes the row."
  [transaction scope-kinds kind scope-id founder-id]
  (sql/run-statement! transaction insert-scope {:scope/kind (name kind) :scope/id scope-id})
  (add-role! transaction scope-kinds [kind scope-id] scope-kinds/owner-role #{})
  (doseq [[role-name actions] (get-in scope-kinds [kind :roles])]
    (add-role! transaction scope-kinds [kind scope-id] role-name actions))
  (add-membership! transaction [kind scope-id] founder-id scope-kinds/owner-role))

(defn remove-membership!
  "Takes a person out of a scope. Whether they were in it."
  [transaction [kind scope-id] person-id]
  (pos? (sql/run-statement! transaction delete-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                           :membership/scope-id scope-id})))

(defn- add-row-to-actions [scope-kinds actions {:membership/keys [scope-kind scope-id role] :role-action/keys [action]}]
  (let [kind  (keyword scope-kind)
        scope [kind scope-id]]
    (if (= scope-kinds/owner-role role)
      (assoc actions scope (scope-kinds/find-actions-of-role scope-kinds kind role #{}))
      (update actions scope (fnil conj #{}) (some-> action turn-text-into-keyword)))))

(defn list-actions-of-person
  "Every scope a person is in, with the actions their role there allows:
   {[:shop id] #{:orders/see …}}."
  [transaction scope-kinds person-id]
  (-> (reduce #(add-row-to-actions scope-kinds %1 %2) {}
              (sql/find-rows transaction find-actions-of-person {:membership/person-id person-id}))
      (update-vals #(disj % nil))))
