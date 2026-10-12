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
                 :job     {:table :jobs :id :job/id
                           :in [:shop :job/shop-id]                      ; its own scope column
                           :parent [:order :job/order-id]}               ; and the record it hangs under
                 :agreement {:table :agreements :id :agreement/id
                             :in [[:shop :agreement/shop-id] [:partner :agreement/partner-id]]}}
      :links    [{:table :agreements
                  :from  [:partner :agreement/partner-id]
                  :to    [:shop :agreement/shop-id]
                  :when  [:= :agreement/status \"active\"]
                  :grants {:orders/see :agreements/see}}]  ; agreements/see in the partner gives orders/see in the shop
      :session-blocks {:impersonated #{:money/move}}}

   Every record names its scope through its own column, however deep it
   sits: a job keeps its shop, not only its order. A record under another
   record names that parent too; the database must tie the two with a
   matching link (see parent-links).

   Every action belongs to one kind of scope. A role may hold its kind's
   actions and those of the kinds below it, plus :groundwork/team (managing
   the scope's people and roles). Conditions: [:= col v] [:not= col v]
   [:in col [v …]] [:is-null col] [:not-null col] [:and …] [:or …], on the
   record's own columns.

   One rule becomes one SQL condition over the record's table (alias r): the
   same text checks one record and filters a list. The database wall uses the
   same rules (see policy-sql): a transaction that runs for an action sees a
   table's rows only as that action's rule allows."
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
  "The scope kind an arrow leads to: an arrow names a scope kind directly."
  [{:keys [scopes records]} kind [target _]]
  (cond
    (contains? scopes target)  target
    (contains? records target) (problem (str "Record kind " kind "'s :in names the record kind " target
                                             ". A record under another record keeps its own scope column and"
                                             " names the parent: :in [<scope kind> <its scope column>] :parent ["
                                             target " <its column to the parent>]")
                                        {:kind kind})
    :else                      (problem (str "No scope kind " target) {:kind kind})))

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
                    (map (fn [[_ {:keys [id people only-when parent] t :table :as record}]]
                           [(table t) (set (map column (concat [id]
                                                               (map second (arrows record))
                                                               (when parent [(second parent)])
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
    (doseq [[kind {:keys [id people only-when parent] :as record}] records]
      (when (contains? scopes kind)
        (problem (str kind " is both a scope kind and a record kind") {:kind kind}))
      (when-let [missing (seq (remove #(contains? record %) [:table :id :in]))]
        (problem (str "Record kind " kind " needs " (str/join " " missing)) {:kind kind}))
      (when-not (qualified-keyword? id)
        (problem (str "Record kind " kind ": :id must be a column like :order/id") {:kind kind}))
      (doseq [arrow (arrows record)]
        (end-kind description kind arrow))
      (when-let [[parent-kind col] parent]
        (let [above (records parent-kind)]
          (when-not above
            (problem (str "Record kind " kind " has an unknown parent " parent-kind) {:kind kind}))
          (when-not (qualified-keyword? col)
            (problem (str "Record kind " kind ": :parent's column must be like :job/order-id") {:kind kind}))
          (when (or (< 1 (count (arrows record))) (< 1 (count (arrows above))))
            (problem (str "Record kind " kind " and its parent " parent-kind " must each belong to one scope")
                     {:kind kind}))
          (when-not (= (ffirst (arrows record)) (ffirst (arrows above)))
            (problem (str "Record kind " kind " is in " (ffirst (arrows record)) ", but its parent "
                          parent-kind " is in " (ffirst (arrows above)))
                     {:kind kind}))))
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

(defn condition-sql
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
   quoted table name): the row's own scope column, no lookup."
  [base [_ col]]
  (str base "." (quote-id (column col))))

(defn sql-literal
  "A value from the description as an SQL literal, for SQL Groundwork
   installs (functions, policies) where nothing can be bound."
  [v]
  (cond
    (string? v)                     (str "'" (str/replace v "'" "''") "'")
    (boolean? v)                    (if v "TRUE" "FALSE")
    (or (int? v) (decimal? v))      (str v)
    (instance? java.util.UUID v)    (str "'" v "'::uuid")
    :else (problem (str "A condition's value " (pr-str v) " must be a string, number, boolean or UUID")
                   {:value v})))

(defn- fill
  "SQL with each ? replaced, in order, by the text for its param. Split
   first, so a ? inside a value written in isn't taken for the next one."
  [sql params text-of]
  (let [parts (str/split sql #"\?" -1)]
    (when-not (= (count parts) (inc (count params)))
      (throw (ex-info "Params don't match the SQL's placeholders" {:sql sql :params params})))
    (apply str (first parts) (mapcat (fn [p part] [(text-of p) part]) params (rest parts)))))

(defn link-function
  "The name of the database function a link is read through:
   (person, action) → the scopes it reaches. Read inside it, the link's own
   table isn't walled, and a policy that calls it stays free of the tables
   behind it."
  [{t :table :keys [from to] condition :when}]
  (str "groundwork." (quote-id (str "link_scopes__" (table t) "__"
                                    (format "%08x" (hash [(table t) from to condition]))))))

(defn link-sql
  "The statement that installs a link's function. Safe to run again."
  [{:keys [from to] t :table condition :when :as link}]
  (let [[when-sql when-params] (condition-sql "gwl" condition)
        when-sql               (fill when-sql when-params sql-literal)]
    ;; The bypass is on only while the link's own rows are read, then back
    ;; to what it was; an error ends the transaction, and the setting with it.
    (str "CREATE OR REPLACE FUNCTION " (link-function link) "(person uuid, action text)\n"
         "RETURNS SETOF uuid LANGUAGE plpgsql AS $$\n"
         "DECLARE before text := coalesce(current_setting('groundwork.bypass', true), '');\n"
         "BEGIN\n"
         "  PERFORM set_config('groundwork.bypass', 'on', true);\n"
         "  RETURN QUERY SELECT gwl." (quote-id (column (second to))) " FROM " (quote-id (table t)) " gwl\n"
         "    WHERE " when-sql "\n"
         "      AND gwl." (quote-id (column (second from))) " IN (SELECT groundwork.scopes_holding(person, action));\n"
         "  PERFORM set_config('groundwork.bypass', before, true);\n"
         "END $$")))

(defn compile-rule
  "The rule for an action on a kind of record, as {:sql … :params […]}: a
   condition over the record's table under `alias` (r unless given). Params
   name the person as :person, everything else is a value. nil when nothing
   can grant it."
  ([description action kind] (compile-rule description action kind "r"))
  ([{:keys [records links] :as description} action kind alias]
   (let [{:keys [people only-when] :as record} (records kind)
         kinds                                 (action-kinds description)
         action-kind                           (kinds action)
         holding                               "IN (SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))"
         clauses
         (concat
          ;; the record's own scope, or one below the action's kind
          (for [arrow (arrows record)
                :let  [end (end-kind description kind arrow)]
                :when (under? description end action-kind)]
            [(str (scope-sql alias arrow) " " holding) [:person (action-name action)]])
          ;; a scope linked to the record's scope
          (for [arrow                     (arrows record)
                :let                      [end (end-kind description kind arrow)]
                {:keys [to grants] :as link} links
                :let                      [needed (grants action)]
                :when                     (and needed (= end (first to)))]
            [(str (scope-sql alias arrow) " IN (SELECT " (link-function link) "(CAST(? AS uuid), ?))")
             [:person (action-name needed)]])
          ;; a person on the record
          (when-let [col (get people action)]
            [[(str alias "." (quote-id (column col)) " = CAST(? AS uuid)") [:person]]]))]
     (when (seq clauses)
       (let [granted        [(str "(" (str/join " OR " (map first clauses)) ")") (vec (mapcat second clauses))]
             [sql params]   (if-let [condition (get only-when action)]
                              (let [[c ps] (condition-sql alias condition)]
                                [(str (first granted) " AND " c) (into (second granted) ps)])
                              granted)]
         {:sql sql :params params})))))

(defn linked-scope-clauses
  "Conditions on a scope id (the SQL `scope`) that hold when a link gives the
   action there: [[sql params] …], params naming the person as :person."
  [{:keys [links]} action scope]
  (for [{:keys [grants] :as link} links
        :let [needed (grants action)]
        :when needed]
    [(str scope " IN (SELECT " (link-function link) "(CAST(? AS uuid), ?))")
     [:person (action-name needed)]]))

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
  [{:keys [records]} kind]
  (let [{t :table :keys [people] :as record} (records kind)
        base                                 (quote-id (table t))]
    (str/join " OR "
              (concat (for [arrow (arrows record)]
                        (str "groundwork.wall_allows(" (scope-sql base arrow) ")"))
                      (for [col (distinct (vals people))]
                        (str base "." (quote-id (column col)) " = groundwork.wall_person()"))))))

(defn- member-wall-sql
  "A row of a kind's table is visible to a member: its scope was verified
   and the person belongs there (or above), or the row is the person's."
  [{:keys [records]} kind]
  (let [{t :table :keys [people] :as record} (records kind)
        base                                 (quote-id (table t))]
    (str/join " OR "
              (concat (for [arrow (arrows record)]
                        (str "groundwork.wall_member(" (scope-sql base arrow) ")"))
                      (for [col (distinct (vals people))]
                        (str base "." (quote-id (column col)) " = groundwork.wall_person()"))))))

(defn- inline
  "A compiled rule's SQL with its params written in: the person as the
   transaction's own, values as literals."
  [{:keys [sql params]}]
  (fill sql params #(if (= :person %) "groundwork.wall_person()" (sql-literal %))))

(defn policy-sql
  "The database wall's policy for a record kind's table, {:using … :check …}.

   A row is visible when one of its scopes was verified for the transaction
   (or it's the person's own), and then: when the transaction runs for an
   action with a rule on this kind, and is about this kind (or names no
   kind, as a scope's endpoint), only as that rule allows: a link and its
   condition, an only-when, a person column. Otherwise only to a member of
   its scope (there or above), so what a link gives is the linked action on
   the endpoint's own kind and nothing else there.

   A writing transaction leaves only-when out: its own record was checked
   with it before the write, and the changed row (which Postgres checks
   against this too when the write returns it) needn't fit it any more. A
   new or changed row must be in a verified scope, and of the kind the
   action is about or one the person is a member for. The system sees and
   writes everything."
  [{:keys [records] :as description} rules kind]
  (let [{t :table} (records kind)
        base       (quote-id (table t))
        actions    (sort (for [[[action k]] rules :when (= k kind)] action))
        bypass     "coalesce(current_setting('groundwork.bypass', true), '') = 'on'"
        writing    "coalesce(current_setting('groundwork.writing', true), '') = 'on'"
        action-now "coalesce(current_setting('groundwork.action', true), '')"
        about-this (str "coalesce(current_setting('groundwork.kind', true), '') IN ('', "
                        (sql-literal (action-name kind)) ")")
        member     (str "(" (member-wall-sql description kind) ")")
        rule-sql   (fn [d a] (str "(" (inline (compile-rule d a kind base)) ")"))
        without-ow (update-in description [:records kind] dissoc :only-when)
        by-action  (fn [branch]
                     (if (empty? actions)
                       member
                       (str "(CASE " action-now
                            (apply str (for [a actions]
                                         (str " WHEN " (sql-literal (action-name a))
                                              " THEN (CASE WHEN " about-this " THEN " (branch a)
                                              " ELSE " member " END)")))
                            " ELSE " member " END)")))]
    {:using (str "CASE WHEN " bypass " THEN TRUE ELSE (" (wall-sql description kind) ") AND "
                 (by-action (fn [a]
                              (if (get-in records [kind :only-when a])
                                (str "(CASE WHEN " writing " THEN " (rule-sql without-ow a)
                                     " ELSE " (rule-sql description a) " END)")
                                (rule-sql description a))))
                 " END")
     :check (str "(" (wall-sql description kind) ") AND " (by-action (constantly "TRUE")))}))

(defn scope-columns
  "Every record table's scope columns, as plain names."
  [{:keys [records]}]
  (for [[kind record] records
        arrow         (arrows record)]
    {:kind kind :table (table (:table record)) :column (column (second arrow))}))

(defn parent-links
  "For every record kind under another record: the matching link the
   database must have, as plain names. The child's (parent column, scope
   column) must reference the parent's (id, scope column), ON UPDATE CASCADE,
   so a child's scope can't differ from its parent's and follows it when the
   parent moves."
  [{:keys [records]}]
  (for [[kind {t :table [parent-kind parent-col] :parent :as record}] records
        :when parent-kind
        :let  [{pt :table pid :id :as above} (records parent-kind)]]
    {:kind          kind
     :table         (table t)
     :scope-column  (column (second (first (arrows record))))
     :parent-column (column parent-col)
     :parent-table  (table pt)
     :parent-id     (column pid)
     :parent-scope  (column (second (first (arrows above))))}))

(defn fill-sql
  "The statements that install a link's fill rule: a row inserted without
   its scope takes its parent's. Safe to run again."
  [{t :table :keys [scope-column parent-column parent-table parent-id parent-scope]}]
  (let [function (str "groundwork." (quote-id (str "fill_scope__" t)))]
    [(str "CREATE OR REPLACE FUNCTION " function "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
          "BEGIN\n"
          "  IF NEW." (quote-id scope-column) " IS NULL THEN\n"
          "    SELECT p." (quote-id parent-scope) " INTO NEW." (quote-id scope-column)
          " FROM " (quote-id parent-table) " p WHERE p." (quote-id parent-id) " = NEW." (quote-id parent-column) ";\n"
          "  END IF;\n"
          "  RETURN NEW;\n"
          "END $$")
     (str "CREATE OR REPLACE TRIGGER groundwork_fill_scope BEFORE INSERT ON " (quote-id t)
          " FOR EACH ROW EXECUTE FUNCTION " function "()")]))

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
