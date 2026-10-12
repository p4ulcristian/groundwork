(ns groundwork.permissions.rules
  "An app's permission description, checked and turned into SQL.

     {:scopes   {:company {:actions #{:company/report}
                           :roles   {\"Director\" #{:company/report :orders/see}}
                           :parent  nil}
                 :shop    {:actions #{:orders/see :orders/edit :jobs/move}
                           :roles   {\"Manager\" #{:orders/see :orders/edit :jobs/move :groundwork/team}
                                     \"Production\" #{:orders/see :jobs/move}}
                           :parent  :company}}
      :records  {:order   {:table :orders :id :order/id
                           :in [:shop :order/shop-id]                    ; the arrow to its scope
                           :people {:orders/see :order/customer-id}     ; optional: a person on the record
                           :only-when {:orders/edit [:= :order/status \"open\"]}} ; optional
                 :job     {:table :jobs :id :job/id :in [:order :job/order-id]}  ; job → order → shop
                 :agreement {:table :agreements :id :agreement/id
                             :in [[:shop :agreement/shop-id] [:partner :agreement/partner-id]]}}
      :links    [{:table :agreements
                  :from  [:partner :agreement/partner-id]
                  :to    [:shop :agreement/shop-id]
                  :when  [:= :agreement/status \"active\"]
                  :grants {:orders/see :agreements/see}}]  ; agreements/see in the partner gives orders/see in the shop
      :session-blocks {:impersonated #{:money/move}}}

   Every action belongs to one kind of scope. A role may hold its kind's
   actions and those of the kinds below it, plus :groundwork/team (managing
   the scope's people and roles). Conditions: [:= col v] [:not= col v]
   [:in col [v …]] [:is-null col] [:not-null col] [:and …] [:or …], on the
   record's own columns.

   One rule becomes one SQL condition over the record's table (alias r): the
   same text checks one record and filters a list."
  (:require
   [clojure.string :as str]
   [groundwork.names :as names]))

(def team-action :groundwork/team)

(defn action-name
  "An action as stored: :orders/edit → \"orders/edit\"."
  [action]
  (subs (str action) 1))

(defn- problem [message data]
  (throw (ex-info message (assoc data :type ::bad-description))))

;; =============================================================================
;; Reading the description
;; =============================================================================

(defn- arrows
  "A record kind's arrows, always as a list of [kind column]."
  [{:keys [in]}]
  (if (keyword? (first in)) [in] in))

(defn action-kinds
  "Which kind of scope each action belongs to."
  [{:keys [scopes]}]
  (reduce-kv (fn [m kind {:keys [actions]}]
               (reduce (fn [m action]
                         (when-let [other (m action)]
                           (problem (str "Action " action " belongs to both " other " and " kind)
                                    {:action action}))
                         (assoc m action kind))
                       m actions))
             {} scopes))

(defn- ancestors-of
  "The kinds above a scope kind, nearest first."
  [{:keys [scopes]} kind]
  (loop [kind (get-in scopes [kind :parent]), seen []]
    (cond
      (nil? kind)              seen
      (some #{kind} seen)      (problem (str "Scope kinds form a loop through " kind) {:kind kind})
      :else                    (recur (get-in scopes [kind :parent]) (conj seen kind)))))

(defn- under?
  "Whether scope kind `kind` is `above` itself or below it."
  [description kind above]
  (or (= kind above) (some #{above} (ancestors-of description kind))))

(defn- end-kind
  "The scope kind an arrow ends at, following record kinds."
  [{:keys [scopes records] :as description} [target _] seen]
  (cond
    (contains? scopes target)  target
    (contains? records target) (if (some #{target} seen)
                                 (problem (str "Record kinds form a loop through " target) {:kind target})
                                 (let [as (arrows (records target))]
                                   (when (< 1 (count as))
                                     (problem (str "An arrow can't go through " target ", which belongs to two scopes")
                                              {:kind target}))
                                   (end-kind description (first as) (conj seen target))))
    :else                      (problem (str "No scope or record kind " target) {:kind target})))

(defn- column [col]
  (names/key->column col))

(defn- table [t]
  (let [s (name t)]
    (when-not (re-matches #"[a-z_][a-z0-9_]*" s)
      (problem (str "Table " t " isn't a plain lowercase name") {:table t}))
    s))

(defn- quote-id [s] (str "\"" s "\""))

;; =============================================================================
;; Checking the description
;; =============================================================================

(defn- condition-columns [condition]
  (let [[op & args] condition]
    (case op
      (:and :or)                       (mapcat condition-columns args)
      (:= :not= :in :is-null :not-null) [(first args)]
      (problem (str "Unknown condition " op) {:condition condition}))))

(defn columns-needed
  "Every table the description names, with the columns it needs."
  [{:keys [records links]}]
  (merge-with into
              (into {}
                    (map (fn [[_ {:keys [id people only-when] t :table :as record}]]
                           [(table t) (set (map column (concat [id]
                                                               (map second (arrows record))
                                                               (vals people)
                                                               (mapcat condition-columns (vals only-when)))))]))
                    records)
              (into {}
                    (map (fn [{t :table condition :when :keys [from to]}]
                           [(table t) (set (map column (concat [(second from) (second to)]
                                                               (condition-columns condition))))]))
                    links)))

(defn check
  "Throws on the first thing wrong in a description, apart from the
   database: see columns-needed for that. Returns the description."
  [{:keys [scopes records links session-blocks] :as description}]
  (when-let [missing (seq (remove #(contains? description %) [:scopes :records :links :session-blocks]))]
    (problem (str ":permissions needs " (str/join " " missing)) {:missing (vec missing)}))
  (doseq [[kind spec] scopes]
    (when-let [missing (seq (remove #(contains? spec %) [:actions :roles :parent]))]
      (problem (str "Scope kind " kind " needs " (str/join " " missing)) {:kind kind}))
    (when-let [parent (:parent spec)]
      (when-not (contains? scopes parent)
        (problem (str "Scope kind " kind " has an unknown parent " parent) {:kind kind})))
    (doseq [action (:actions spec)]
      (when-not (qualified-keyword? action)
        (problem (str "Action " action " must be namespaced, like :orders/edit") {:action action}))))
  (let [kinds (action-kinds description)]
    (doseq [kind (keys scopes)] (ancestors-of description kind))
    (doseq [[kind {:keys [roles]}] scopes
            [role actions] roles
            action actions
            :when (not= team-action action)]
      (let [action-kind (kinds action)]
        (when-not (and action-kind (under? description action-kind kind))
          (problem (str "Role " role " of " kind " holds " action ", which isn't an action of " kind " or below")
                   {:kind kind :role role :action action}))))
    (doseq [[kind {:keys [id people only-when] :as record}] records]
      (when (contains? scopes kind)
        (problem (str kind " is both a scope kind and a record kind") {:kind kind}))
      (when-let [missing (seq (remove #(contains? record %) [:table :id :in]))]
        (problem (str "Record kind " kind " needs " (str/join " " missing)) {:kind kind}))
      (when-not (qualified-keyword? id)
        (problem (str "Record kind " kind ": :id must be a column like :order/id") {:kind kind}))
      (doseq [arrow (arrows record)]
        (end-kind description arrow [kind]))
      (doseq [action (concat (keys people) (keys only-when))]
        (when-not (kinds action)
          (problem (str "Record kind " kind " names an unknown action " action) {:kind kind :action action}))))
    (doseq [{:keys [from to grants] :as link} links]
      (doseq [k [:table :from :to :when :grants]]
        (when-not (contains? link k)
          (problem (str "A link needs " k) {:link link})))
      (doseq [[side [kind]] [[:from from] [:to to]]]
        (when-not (contains? scopes kind)
          (problem (str "A link's " side " must be a scope kind, not " kind) {:link link})))
      (doseq [[given needed] grants]
        (when-not (and (kinds given) (under? description (first to) (kinds given)))
          (problem (str "A link grants " given ", which isn't an action of " (first to) " or above") {:link link}))
        (when-not (and (kinds needed) (under? description (first from) (kinds needed)))
          (problem (str "A link needs " needed ", which isn't an action of " (first from) " or above") {:link link}))))
    (doseq [[flag actions] session-blocks
            action actions]
      (when-not (kinds action)
        (problem (str "Session flag " flag " blocks an unknown action " action) {:action action}))))
  description)

;; =============================================================================
;; Compiling
;; =============================================================================

(defn- condition-sql
  "A condition on a table's own columns: [sql params]."
  [alias condition]
  (let [[op & args] condition
        col         #(str alias "." (quote-id (column %)))]
    (case op
      :and      (let [parts (map #(condition-sql alias %) args)]
                  [(str "(" (str/join " AND " (map first parts)) ")") (vec (mapcat second parts))])
      :or       (let [parts (map #(condition-sql alias %) args)]
                  [(str "(" (str/join " OR " (map first parts)) ")") (vec (mapcat second parts))])
      :=        [(str (col (first args)) " = ?") [(second args)]]
      :not=     [(str (col (first args)) " IS DISTINCT FROM ?") [(second args)]]
      :in       (let [vs (vec (second args))]
                  [(str (col (first args)) " IN (" (str/join ", " (repeat (count vs) "?")) ")") vs])
      :is-null  [(str (col (first args)) " IS NULL") []]
      :not-null [(str (col (first args)) " IS NOT NULL") []])))

(defn scope-sql
  "The SQL for the scope id an arrow leads to, from `base` (an alias or a
   quoted table name), following record kinds through subqueries."
  [{:keys [scopes records] :as description} base [target col] depth]
  (let [here (str base "." (quote-id (column col)))]
    (if (contains? scopes target)
      here
      (let [{t :table id :id :as next-record} (records target)
            alias                             (str "gw" depth)]
        (str "(SELECT " (scope-sql description alias (first (arrows next-record)) (inc depth))
             " FROM " (quote-id (table t)) " " alias
             " WHERE " alias "." (quote-id (column id)) " = " here ")")))))

(defn compile-rule
  "The rule for an action on a kind of record, as {:sql … :params […]}: a
   condition over the record's table under alias r. Params name the person
   as :person, everything else is a value. nil when nothing can grant it."
  [{:keys [records links] :as description} action kind]
  (let [{:keys [people only-when] :as record} (records kind)
        kinds                                 (action-kinds description)
        action-kind                           (kinds action)
        holding                               "IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))"
        clauses
        (concat
         ;; the record's own scope, or one below the action's kind
         (for [arrow (arrows record)
               :let  [end (end-kind description arrow [kind])]
               :when (under? description end action-kind)]
           [(str (scope-sql description "r" arrow 0) " " holding) [:person (action-name action)]])
         ;; a scope linked to the record's scope
         (for [arrow                                      (arrows record)
               :let                                       [end (end-kind description arrow [kind])]
               {:keys [from to grants] t :table condition :when} links
               :let                                       [needed (grants action)]
               :when                                      (and needed (= end (first to)))]
           (let [[when-sql when-params] (condition-sql "gwl" condition)]
             [(str (scope-sql description "r" arrow 0)
                   " IN (SELECT gwl." (quote-id (column (second to)))
                   " FROM " (quote-id (table t)) " gwl WHERE " when-sql
                   " AND gwl." (quote-id (column (second from))) " " holding ")")
              (into when-params [:person (action-name needed)])]))
         ;; a person on the record
         (when-let [col (get people action)]
           [[(str "r." (quote-id (column col)) " = CAST(? AS uuid)") [:person]]]))]
    (when (seq clauses)
      (let [granted        [(str "(" (str/join " OR " (map first clauses)) ")") (vec (mapcat second clauses))]
            [sql params]   (if-let [condition (get only-when action)]
                             (let [[c ps] (condition-sql "r" condition)]
                               [(str (first granted) " AND " c) (into (second granted) ps)])
                             granted)]
        {:sql sql :params params}))))

(defn rules-of
  "Every rule a description can grant: {[action kind] {:sql :params}}. An
   action named on a record kind that nothing can grant throws."
  [{:keys [records] :as description}]
  (let [kinds (action-kinds description)]
    (into {}
          (for [[kind {:keys [people only-when]}] records
                action                            (keys kinds)
                :let                              [rule (compile-rule description action kind)]
                :when                             (or rule (contains? people action) (contains? only-when action))]
            (do (when-not rule
                  (problem (str "Nothing can grant " action " on " kind) {:action action :kind kind}))
                [[action kind] rule])))))

(defn wall-sql
  "The database wall's condition for a record kind's table: a row is visible
   when one of its scopes was verified, or it belongs to the person."
  [{:keys [records] :as description} kind]
  (let [{t :table :keys [people] :as record} (records kind)
        base                                 (quote-id (table t))]
    (str/join " OR "
              (concat (for [arrow (arrows record)]
                        (str "groundwork.wall_allows(" (scope-sql description base arrow 0) ")"))
                      (for [col (distinct (vals people))]
                        (str base "." (quote-id (column col)) " = groundwork.wall_person()"))))))

(defn blocked?
  "Whether a session's flags block an action."
  [{:keys [session-blocks]} action flags]
  (boolean (some #(contains? (get session-blocks %) action) flags)))

(defn allowed-in-role?
  "The actions a role of this kind may hold."
  [description kind]
  (let [kinds (action-kinds description)]
    (conj (set (filter #(under? description (kinds %) kind) (keys kinds))) team-action)))

(defn bind
  "A rule's params with the person filled in."
  [params person]
  (mapv #(if (= :person %) (str person) %) params))

(defn quote-table [t] (quote-id (table t)))
(defn quote-column [c] (quote-id (column c)))
