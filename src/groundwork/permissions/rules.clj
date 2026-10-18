(ns groundwork.permissions.rules
  "An app's permission description, checked and turned into SQL.

     {:scopes  {:company  {:actions {:company/report #{:read}}
                           :roles   {\"Director\" #{:company/report :orders/see}}
                           :parent  nil}
                :shop     {:actions {:orders/see  #{:read}
                                     :orders/edit #{:read :update}
                                     :money/move  #{:read :insert :update}}
                           :roles   {\"Manager\" #{:orders/see :orders/edit :groundwork/team}}
                           :parent  :company
                           :table   :shops :id :shop/id}           ; optional: the app's own table of shops
                :partner  {:actions {:agreements/see #{:read}} :roles {} :parent nil}
                :platform {:actions {:platform/support #{:read}} :roles {} :parent nil
                           :single? true}}                          ; exactly one scope: the back office
      :records {:order     {:table :orders :id :order/id
                            :whose {:order/shop-id :shop :order/customer-id :person}}
                :job       {:table :jobs :id :job/id :whose {:job/shop-id :shop}
                            :parent [:order :job/order-id]}         ; it hangs under an order
                :agreement {:table :agreements :id :agreement/id
                            :whose {:agreement/shop-id :shop :agreement/partner-id :partner}}}
      :rules   [{:allow :orders/see :on :order :to [:member :order/shop-id]}
                {:allow :orders/see :on :order :to [:me :order/customer-id]}
                {:allow :orders/edit :on :order :to [:member :order/shop-id] :when [:= :order/status \"open\"]}
                {:allow :orders/see :on :order
                 :via {:table :agreements :match {:order/shop-id :agreement/shop-id}
                       :to [:member :agreement/partner-id :agreements/see]
                       :when [:= :agreement/status \"active\"]}}
                {:allow :orders/see :on :order :to [:member :platform :platform/support]}
                {:deny :money/move :session :impersonated}
                {:deny :writes :scope :shop :when [:= :shop/status \"suspended\"]}]}

   The nouns are the app's: its kinds of scope, each action and what it does
   at the wall (:read, :insert, :update, :delete), its kinds of record and
   whose row each column names (:whose: a scope kind, :person or :visitor).
   The words are Groundwork's:

   - To whom (:to): :anyone, :signed-in, [:member <column>] (a member of the
     scope the row's column names), [:member <single-scope kind>] or
     [:member #uuid \"…\"] (of one fixed scope), each with an optional third
     element, the action the role must hold there (the sentence's own
     action unless said), and [:me <column>] (the person, or the visitor,
     in that column).
   - When (:when): a condition on the row's own columns, [:= col v] [:not=
     col v] [:in col [v …]] [:is-null col] [:not-null col] [:and …] [:or …],
     and [:none <scope column>] for a universal row that belongs to nobody.
   - :via instead of :to: the row is allowed while a related row exists for
     which a who and a when hold: {:table … :match {its column → the other's}
     :to <who on that row> :when <condition on that row>}. SQL's EXISTS, in
     any direction; read past the wall through a function Groundwork
     installs, so the other table's own wall never loops back.
   - Deny: {:deny <action, set of actions, or :writes> :session <flag>} and
     {:deny … :scope <kind> :when <condition on the kind's :table>}. Nothing
     else can say no, and a deny comes before every yes.

   From the sentences come the wall (row-level security per table and
   command: a command's sentences OR-ed), list filters, the scopes a request
   is about, the bells, the browser's \"can I?\", the leak test and the
   REPL's why. One sentence becomes one SQL condition over the record's
   table: the same text checks one record and filters a list."
  (:require
   [clojure.string :as str]
   [groundwork.postgres.names :as names]))

(def team-action :groundwork/team)

(def commands
  "What an action may do at the wall."
  #{:read :insert :update :delete})

(def write-commands #{:insert :update :delete})

(defn action-name
  "An action as stored: :orders/edit → \"orders/edit\"."
  [action]
  (subs (str action) 1))

(defn- problem [message data]
  (throw (ex-info message (assoc data :type ::bad-description))))

(defn- column [col]
  (names/key->column col))

(defn- table [t]
  (let [s (name t)]
    (when-not (re-matches #"[a-z_][a-z0-9_]*" s)
      (problem (str "Table " t " isn't a plain lowercase name") {:table t}))
    s))

(defn- quote-id [s] (str "\"" s "\""))

(defn quote-table [t] (quote-id (table t)))
(defn quote-column [c] (quote-id (column c)))

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

(defn- shown [rule]
  (let [s (pr-str rule)]
    (if (< 100 (count s)) (str (subs s 0 97) "…") s)))

(defn- as-set [x] (if (set? x) x #{x}))

;; =============================================================================
;; Reading the description: scopes and actions
;; =============================================================================

(defn actions
  "Every action: {action {:kind scope-kind :commands #{…}}}."
  [{:keys [scopes]}]
  (reduce-kv (fn [m kind {as :actions}]
               (reduce-kv (fn [m action cmds]
                            (when-let [other (get-in m [action :kind])]
                              (problem (str "Action " action " belongs to both " other " and " kind)
                                       {:action action}))
                            (assoc m action {:kind kind :commands (set cmds)}))
                          m as))
             {} scopes))

(defn action-kinds
  "Which kind of scope each action belongs to."
  [description]
  (update-vals (actions description) :kind))

(defn commands-of
  "What an action does at the wall: a set of :read :insert :update :delete."
  [description action]
  (get-in (actions description) [action :commands] #{}))

(defn writes?
  "Whether an action writes: it inserts, updates or deletes."
  [description action]
  (boolean (some write-commands (commands-of description action))))

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

(defn allowed-in-role?
  "The actions a role of this kind may hold: its kind's and those of the
   kinds below it, and :groundwork/team."
  [description kind]
  (let [kinds (action-kinds description)]
    (conj (set (filter #(under? description (kinds %) kind) (keys kinds))) team-action)))

;; =============================================================================
;; Reading the description: records
;; =============================================================================

(defn scope-columns
  "A record kind's scope columns: {column scope-kind}, in a stable order."
  [{:keys [whose]}]
  (into (sorted-map-by #(compare (str %1) (str %2)))
        (filter (fn [[_ v]] (not (#{:person :visitor} v))))
        whose))

(defn person-columns
  "A record kind's person columns."
  [{:keys [whose]}]
  (into (sorted-set-by #(compare (str %1) (str %2))) (keep (fn [[c v]] (when (= :person v) c))) whose))

(defn visitor-column
  "A record kind's visitor column, or nil."
  [{:keys [whose]}]
  (some (fn [[c v]] (when (= :visitor v) c)) whose))

(defn kind-of-table
  "The record kind whose table this is, or nil."
  [{:keys [records]} t]
  (some (fn [[kind spec]] (when (= (name t) (name (:table spec))) kind)) records))

(defn carried-columns
  "What a record kind carries from its parent, {its column → the parent's}:
   as written, or its one scope column from its parent's one."
  [{:keys [records]} kind]
  (let [{[parent-kind _ carried] :parent :as record} (records kind)]
    (when parent-kind
      (or carried
          {(key (first (scope-columns record))) (key (first (scope-columns (records parent-kind))))}))))

;; =============================================================================
;; Reading the description: sentences
;; =============================================================================

(defn- who-of
  "A who word read against the kind of record it is said of: a map with
   :word, and what it names. Throws on what isn't a word."
  [{:keys [records scopes] :as description} kind who action where]
  (let [record (records kind)
        bad    (fn [message] (problem (str where ": " message) {:who who}))
        kinds  (action-kinds description)
        held   (fn [h scope-kind]
                 (let [h (or h action)]
                   (when-not (qualified-keyword? h)
                     (bad (str "the action held in [:member …] must be like :orders/see, not " (pr-str h))))
                   (when-not (kinds h)
                     (bad (str "no action " h " in any scope kind")))
                   (when (and scope-kind
                              (not (some #(under? description (kinds h) %)
                                         (cons scope-kind (ancestors-of description scope-kind)))))
                     (bad (str "nobody in a " (name scope-kind) " or above can hold " h ", an action of "
                               (name (kinds h)) (when (= h action)
                                                  "; name the action they hold there: [:member … <action>]"))))
                   h))]
    (cond
      (= :anyone who)    {:word :anyone}
      (= :signed-in who) {:word :signed-in}

      (and (vector? who) (= :member (first who)) (<= 2 (count who) 3))
      (let [[_ x h] who]
        (cond
          (qualified-keyword? x)
          (let [scope-kind (get (scope-columns record) x)]
            (when-not scope-kind
              (bad (str x " isn't a scope column of " kind " (its :whose names the scope kind of each)")))
            {:word :member :column x :scope-kind scope-kind :holding (held h scope-kind)})

          (keyword? x)
          (do (when-not (contains? scopes x)
                (bad (str "no scope kind " x)))
              (when-not (get-in scopes [x :single?])
                (bad (str x " has many scopes, so [:member " x "] names none; a kind with exactly one"
                          " scope says :single? true, or the sentence names a column of the row")))
              {:word :member :kind x :holding (held h x)})

          (uuid? x)
          {:word :member :scope x :holding (held h nil)}

          :else (bad (str "[:member …] names a column of the row, a single-scope kind or a scope's id, not "
                          (pr-str x)))))

      (and (vector? who) (= :me (first who)) (= 2 (count who)))
      (let [col (second who)]
        (cond
          (contains? (person-columns record) col) {:word :me :column col :visitor? false}
          (= col (visitor-column record))         {:word :me :column col :visitor? true}
          :else (bad (str col " isn't a :person or :visitor column of " kind " (in its :whose)"))))

      :else (bad (str (pr-str who) " isn't a word: :anyone, :signed-in, [:member …] or [:me <column>]")))))

(defn- condition-ops
  "Every operator in a condition, with the columns it names: [[op col] …]."
  [condition]
  (let [[op & args] condition]
    (case op
      (:and :or)                               (mapcat condition-ops args)
      (:= :not= :in :is-null :not-null :none) [[op (first args)]]
      (problem (str "Unknown condition " (pr-str condition)) {:condition condition}))))

(defn- top-nones
  "The [:none col] conditions at the top of a condition (alone, or in an :and)."
  [condition]
  (let [[op & args] condition]
    (case op
      :none [(first args)]
      :and  (mapcat top-nones args)
      [])))

(defn- universal?
  "Whether a sentence is about universal rows: its :when names [:none …] at
   its top, or its kind has no scope column at all."
  [record {condition :when}]
  (or (empty? (scope-columns record)) (boolean (seq (some-> condition top-nones)))))

(defn- via-of [{:keys [records] :as description} via action where]
  (let [bad   (fn [message] (problem (str where ": " message) {:via via}))
        other (and (map? via) (some->> (:table via) (kind-of-table description)))]
    (when-not (map? via)
      (bad ":via is {:table … :match {its column → the other's} :to <who> :when <condition>}"))
    (when-let [extra (seq (remove #{:table :match :to :when} (keys via)))]
      (bad (str ":via doesn't know " (str/join " " extra))))
    (when-not other
      (bad (str ":via's table " (:table via) " isn't the table of a record kind: the row it looks for must be"
                " walled and ring like any record")))
    (when-not (and (map? (:match via)) (seq (:match via))
                   (every? qualified-keyword? (keys (:match via)))
                   (every? qualified-keyword? (vals (:match via))))
      (bad ":via's :match is {its column → the other row's column …}, at least one"))
    (when-not (contains? via :to)
      (bad ":via needs :to, who must hold on the other row"))
    (when (and (vector? (:to via)) (= :via (first (:to via))))
      (bad ":via's :to is a who; a via doesn't hold another via"))
    (when-let [nones (seq (some-> (:when via) condition-ops (->> (filter #(= :none (first %))))))]
      (bad (str ":via's :when can't say [:none " (second (first nones)) "]: the other row is found by its columns")))
    {:table   (table (:table via))
     :kind    other
     :match   (vec (sort-by (comp str first) (:match via)))
     :who     (who-of description other (:to via) action (str where ", on the other row"))
     :when    (:when via)
     :record  (records other)}))

(defn sentences
  "The allow sentences, one per action, each read: {:n its place in :rules,
   :rule as written, :action :kind :who :via :when :universal?}."
  [{:keys [records rules] :as description}]
  (vec (for [[n rule] (map-indexed vector rules)
             :when (contains? rule :allow)
             action (sort-by str (as-set (:allow rule)))
             :let [where  (str "Rule " n " " (shown rule))
                   kind   (:on rule)
                   record (records kind)]]
         (do
           (when-not record
             (problem (str where ": no record kind " kind) {:rule rule}))
           {:n          n
            :rule       rule
            :action     action
            :kind       kind
            :who        (when (contains? rule :to) (who-of description kind (:to rule) action where))
            :via        (when (contains? rule :via) (via-of description (:via rule) action where))
            :when       (:when rule)
            :universal? (universal? record rule)}))))

(defn denies
  "The deny sentences, each read: {:n :rule :actions (a set, or :writes)
   :session flag-or-nil :scope kind-or-nil :when condition-or-nil}."
  [{:keys [rules]}]
  (vec (for [[n rule] (map-indexed vector rules)
             :when (contains? rule :deny)]
         {:n       n
          :rule    rule
          :actions (if (= :writes (:deny rule)) :writes (as-set (:deny rule)))
          :session (:session rule)
          :scope   (:scope rule)
          :when    (:when rule)})))

(defn deny-applies?
  "Whether a deny is about an action: it names it, or it is :writes and the
   action writes."
  [description {:keys [actions]} action]
  (if (= :writes actions)
    (writes? description action)
    (contains? actions action)))

(defn open-word
  "The open word a sentence gives its action with, :anyone or :signed-in,
   on the row (:to) or on the related row (:via), or nil."
  [{:keys [who via]}]
  (some #{:anyone :signed-in} [(:word who) (get-in via [:who :word])]))

(defn open-level
  "Who an action is open to through its sentences: :anyone, :signed-in, or
   nil when none gives it to someone who belongs nowhere. A via open to
   anyone counts: what the wall allows, an endpoint can ask."
  [description action]
  (let [words (set (for [s (sentences description) :when (= action (:action s))] (open-word s)))]
    (cond (words :anyone) :anyone (words :signed-in) :signed-in)))

;; =============================================================================
;; Checking the description
;; =============================================================================

(defn- check-condition! [condition where]
  (doseq [[op col] (condition-ops condition)]
    (when-not (qualified-keyword? col)
      (problem (str where ": " op " names " (pr-str col) ", which isn't a column like :order/status")
               {:condition condition}))))

(defn check
  "Throws on the first thing wrong in a description, apart from the
   database: see columns-needed for that. Returns the description."
  [{:keys [scopes records rules] :as description}]
  (when-let [missing (seq (remove #(contains? description %) [:scopes :records :rules]))]
    (problem (str ":permissions needs " (str/join " " missing)) {:missing (vec missing)}))
  (doseq [[kind spec] scopes]
    (when-let [missing (seq (remove #(contains? spec %) [:actions :roles :parent]))]
      (problem (str "Scope kind " kind " needs " (str/join " " missing)) {:kind kind}))
    (when-let [parent (:parent spec)]
      (when-not (contains? scopes parent)
        (problem (str "Scope kind " kind " has an unknown parent " parent) {:kind kind})))
    (when-not (= (contains? spec :table) (contains? spec :id))
      (problem (str "Scope kind " kind " names its table with :table and :id together") {:kind kind}))
    (doseq [[action cmds] (:actions spec)]
      (when-not (qualified-keyword? action)
        (problem (str "Action " action " must be namespaced, like :orders/edit") {:action action}))
      (when-let [unknown (seq (remove commands cmds))]
        (problem (str "Action " action " does " (str/join " " unknown) ", which isn't a command: "
                      ":read :insert :update :delete")
                 {:action action}))
      (when (and (some #{:update :delete} cmds) (not (contains? (set cmds) :read)))
        (problem (str "Action " action " updates or deletes without :read: Postgres reads the rows an"
                      " update or a delete finds, so it needs :read too")
                 {:action action}))))
  (let [kinds (action-kinds description)]
    (doseq [kind (keys scopes)] (ancestors-of description kind))
    (doseq [[kind {:keys [roles]}] scopes
            [role role-actions] roles
            action role-actions
            :when (not= team-action action)]
      (let [action-kind (kinds action)]
        (when-not (and action-kind (under? description action-kind kind))
          (problem (str "Role " role " of " kind " holds " action ", which isn't an action of " kind " or below")
                   {:kind kind :role role :action action}))))
    (doseq [[kind {:keys [id whose parent] :as record}] records]
      (when (contains? scopes kind)
        (problem (str kind " is both a scope kind and a record kind") {:kind kind}))
      (when-let [missing (seq (remove #(contains? record %) [:table :id :whose]))]
        (problem (str "Record kind " kind " needs " (str/join " " missing)) {:kind kind}))
      (when-not (qualified-keyword? id)
        (problem (str "Record kind " kind ": :id must be a column like :order/id") {:kind kind}))
      (doseq [[col v] whose]
        (when-not (qualified-keyword? col)
          (problem (str "Record kind " kind ": :whose names columns like :order/shop-id, not " (pr-str col))
                   {:kind kind}))
        (when-not (or (#{:person :visitor} v) (contains? scopes v))
          (problem (str "Record kind " kind ": " col " names a " (pr-str v) "; a column names a scope kind,"
                        " :person or :visitor"
                        (when (contains? records v)
                          (str ". A record under another record keeps its own scope column and names the"
                               " parent: :parent [" v " <its column to the parent>]")))
                   {:kind kind})))
      (when (< 1 (count (filter #{:visitor} (vals whose))))
        (problem (str "Record kind " kind " names more than one visitor column") {:kind kind}))
      (when (and (visitor-column record) (< 1 (count (person-columns record))))
        (problem (str "Record kind " kind " has a visitor column and more than one person column: at sign-in a"
                      " visitor's row moves to the person, and there must be one column to move it to")
                 {:kind kind}))
      (when-let [[parent-kind col] parent]
        (let [above (records parent-kind)]
          (when-not above
            (problem (str "Record kind " kind " has an unknown parent " parent-kind) {:kind kind}))
          (when-not (qualified-keyword? col)
            (problem (str "Record kind " kind ": :parent's column must be like :job/order-id") {:kind kind}))
          (when (and (= 2 (count parent))
                     (not (= 1 (count (scope-columns record)) (count (scope-columns above)))))
            (problem (str "Record kind " kind " or its parent " parent-kind " doesn't have exactly one scope: say what "
                          kind " carries, :parent [" parent-kind " " col " {<its column> <the parent's column> …}]")
                     {:kind kind}))
          (let [carried   (carried-columns description kind)
                own       (scope-columns record)
                theirs    (scope-columns above)
                my-people (person-columns record)
                people-up (person-columns above)]
            (when-not (some #(contains? own (key %)) carried)
              (problem (str "Record kind " kind " must carry a scope from its parent " parent-kind) {:kind kind}))
            (doseq [[mine parents] carried]
              (cond
                (and (own mine) (theirs parents))
                (when-not (= (own mine) (theirs parents))
                  (problem (str "Record kind " kind " carries " mine " (a " (own mine) ") from " parents
                                " (a " (theirs parents) ")")
                           {:kind kind}))

                (and (my-people mine) (people-up parents)) nil

                :else
                (problem (str "Record kind " kind " carries " mine " from " parents
                              ": both must be scope columns, or both :person columns, in their :whose")
                         {:kind kind})))))))
    (doseq [[n rule] (map-indexed vector rules)
            :let [where (str "Rule " n " " (shown rule))]]
      (cond
        (and (contains? rule :allow) (contains? rule :deny))
        (problem (str where ": a sentence allows or denies, not both") {:rule rule})

        (contains? rule :allow)
        (do (when-let [extra (seq (remove #{:allow :on :to :when :via} (keys rule)))]
              (problem (str where ": an :allow sentence doesn't know " (str/join " " extra)) {:rule rule}))
            (when-not (contains? rule :on)
              (problem (str where ": say which kind of record it is on, :on <kind>") {:rule rule}))
            (when-not (= 1 (count (filter #(contains? rule %) [:to :via])))
              (problem (str where ": say to whom, :to <who>, or through which related row, :via {…}; one of them")
                       {:rule rule}))
            (doseq [action (as-set (:allow rule))]
              (when-not (kinds action)
                (problem (str where ": no action " action " in any scope kind") {:rule rule :action action})))
            (when-let [condition (:when rule)]
              (check-condition! condition where)
              (let [record (records (:on rule))]
                (doseq [[op col] (condition-ops condition)
                        :when (= :none op)]
                  (when-not (contains? (scope-columns record) col)
                    (problem (str where ": [:none " col "] names a scope column of " (:on rule)
                                  ", in its :whose")
                             {:rule rule})))))
            (when-let [condition (get-in rule [:via :when])]
              (check-condition! condition where)))

        (contains? rule :deny)
        (let [{actions :deny :keys [session scope]} rule]
          (when-let [extra (seq (remove #{:deny :session :scope :when} (keys rule)))]
            (problem (str where ": a :deny sentence doesn't know " (str/join " " extra)) {:rule rule}))
          (when-not (= 1 (count (filter #(contains? rule %) [:session :scope])))
            (problem (str where ": a deny holds by a session flag, :session <flag>, or a scope's state,"
                          " :scope <kind> :when <condition>; one of them")
                     {:rule rule}))
          (when-not (= :writes actions)
            (doseq [action (as-set actions)]
              (when-not (kinds action)
                (problem (str where ": no action " action " in any scope kind") {:rule rule}))))
          (when session
            (when-not (keyword? session)
              (problem (str where ": a session flag is a keyword, like :impersonated") {:rule rule}))
            (when (contains? rule :when)
              (problem (str where ": a session's deny holds by its flag alone; :when is for a scope's state")
                       {:rule rule})))
          (when scope
            (when-not (get-in scopes [scope :table])
              (problem (str where ": a scope's state lives in the app's own table: scope kind " scope
                            " names it, :table <table> :id <its id column>")
                       {:rule rule}))
            (when-not (contains? rule :when)
              (problem (str where ": say which state denies, :when <condition on " (name (get-in scopes [scope :table]))
                            ">")
                       {:rule rule}))
            (check-condition! (:when rule) where)))

        :else
        (problem (str where ": a sentence starts with :allow or :deny") {:rule rule})))
    ;; reading each sentence checks its words
    ;; an open write must be narrowed: by a :when on the row, or a :via (a
    ;; related row must exist)
    (doseq [{:keys [action who via n rule] condition :when} (sentences description)]
      (when (and (#{:anyone :signed-in} (:word who)) (writes? description action) (nil? via) (nil? condition))
        (problem (str "Rule " n " " (shown rule) ": " action " writes, and it is given to " (:word who)
                      " with nothing that narrows it. An open write needs a :when on the row (only a new"
                      " inquiry, say) or a :via instead of :to (only under an open ticket)")
                 {:rule rule}))))
  description)

;; =============================================================================
;; What the database must hold
;; =============================================================================

(defn- condition-columns [condition]
  (when condition (map second (condition-ops condition))))

(defn- who-columns [{:keys [column]}]
  (when column [column]))

(defn columns-needed
  "Every table the description names, with the columns it needs."
  [{:keys [records scopes] :as description}]
  (merge-with into
              (into {}
                    (map (fn [[_ {:keys [id parent whose] t :table}]]
                           [(table t) (set (map column (concat [id]
                                                               (keys whose)
                                                               (when parent [(second parent)])
                                                               (when parent (keys (nth parent 2 nil))))))]))
                    records)
              (apply merge-with into
                     {}
                     (for [{:keys [kind via] condition :when} (sentences description)]
                       (merge-with into
                                   {(table (get-in records [kind :table]))
                                    (set (map column (concat (condition-columns condition)
                                                             (map first (:match via)))))}
                                   (when via
                                     {(:table via) (set (map column (concat (map second (:match via))
                                                                            (who-columns (:who via))
                                                                            (condition-columns (:when via)))))}))))
              (apply merge-with into
                     {}
                     (for [{:keys [scope] condition :when} (denies description)
                           :when scope
                           :let [{t :table id :id} (scopes scope)]]
                       {(table t) (set (map column (cons id (condition-columns condition))))}))))

(defn via-indexes
  "The indexes the vias need, each {:table :columns [plain names] :why}: on
   both sides of what they match, and on the other row's who column, which
   the lookup searches by."
  [{:keys [records] :as description}]
  (distinct
   (for [{:keys [kind via]} (sentences description)
         :when via
         need (concat [{:table   (table (get-in records [kind :table]))
                        :columns (mapv (comp column first) (:match via))
                        :why     "what a via matches, on the row's side"}
                       {:table   (:table via)
                        :columns (mapv (comp column second) (:match via))
                        :why     "what a via matches, on the other row's side"}]
                      (when-let [c (get-in via [:who :column])]
                        [{:table (:table via) :columns [(column c)] :why "the via's who, which it is found by"}]))]
     need)))

(defn none-columns
  "The scope columns a sentence says [:none …] of, as {:table :column}: they
   must be able to be empty."
  [{:keys [records] :as description}]
  (distinct (for [{:keys [kind] condition :when} (sentences description)
                  :when condition
                  [op col] (condition-ops condition)
                  :when (= :none op)]
              {:table (table (get-in records [kind :table])) :column (column col)})))

;; =============================================================================
;; Compiling: a sentence as SQL
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
                  (if (empty? vs)
                    ["FALSE" []]
                    [(str (col (first args)) " IN (" (str/join ", " (repeat (count vs) "?")) ")") vs]))
      (:is-null :none) [(str (col (first args)) " IS NULL") []]
      :not-null [(str (col (first args)) " IS NOT NULL") []])))

(defn- nones-only
  "Of a condition, only its [:none …] at the top: what stays of :when on a
   changed row (the row's state may change; whether it belongs to nobody
   may not)."
  [condition]
  (when-let [nones (seq (some-> condition top-nones))]
    (if (= 1 (count nones)) [:none (first nones)] (into [:and] (map (fn [c] [:none c]) nones)))))

(def ^:private holding-sql "(SELECT groundwork.scopes_holding(CAST(? AS uuid), ?))")

(defn who-sql
  "A who word as SQL over `alias`: [sql params]; params name the person as
   :person and the visitor as :visitor."
  [alias {:keys [word column holding visitor?] :as who}]
  (case word
    :anyone    ["TRUE" []]
    :signed-in ["(CAST(? AS uuid) IS NOT NULL)" [:person]]
    :member    (cond
                 column        [(str alias "." (quote-column column) " IN " holding-sql)
                                [:person (action-name holding)]]
                 (:kind who)   [(str "EXISTS (SELECT 1 FROM groundwork.scopes gws WHERE gws.scope__kind = ?"
                                     " AND gws.scope__id IN " holding-sql ")")
                                [(name (:kind who)) :person (action-name holding)]]
                 (:scope who)  [(str "CAST(? AS uuid) IN " holding-sql) [(str (:scope who)) :person (action-name holding)]])
    :me        [(str alias "." (quote-column column) " = CAST(? AS uuid)") [(if visitor? :visitor :person)]]))

(defn via-function
  "The name of the database function a via is read through: (person,
   visitor, within) → the values its rows offer for what it matches. Read
   inside it, the other table's wall is passed by, so a policy that calls it
   never loops back through the other table's own. `within` is the scopes
   the related row must be in, as text (\"{…}\" or \"*\"), or NULL for any:
   the wall passes the transaction's own."
  [{:keys [table match who] condition :when}]
  (str "groundwork." (quote-id (str "via__" table "__" (format "%08x" (hash [table match who condition :within]))))))

(defn- via-sql [alias via]
  (let [mine (map (comp quote-column first) (:match via))
        outs (map-indexed (fn [i _] (str "via_" (inc i))) (:match via))]
    [(str (if (= 1 (count mine))
            (str alias "." (first mine))
            (str "(" (str/join ", " (map #(str alias "." %) mine)) ")"))
          " IN (SELECT " (str/join ", " outs) " FROM " (via-function via)
          "(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS text)))")
     [:person :visitor :within]]))

(defn- conj-sql
  "[sql params] parts joined with AND; nil parts are left out."
  [parts]
  (let [parts (remove nil? parts)]
    (if (empty? parts)
      ["TRUE" []]
      [(str "(" (str/join " AND " (map first parts)) ")") (vec (mapcat second parts))])))

(defn sentence-sql
  "One sentence as one SQL condition over `alias`: [sql params]. With
   :state? false, its :when keeps only [:none …] (a changed row's check)."
  ([s alias] (sentence-sql s alias {}))
  ([{:keys [who via] condition :when} alias {:keys [state?] :or {state? true}}]
   (conj-sql [(when who (who-sql alias who))
              (when via (via-sql alias via))
              (when-let [c (if state? condition (nones-only condition))] (condition-sql alias c))])))

(def ^:private within-of
  {:read  "current_setting('groundwork.scopes', true)"
   :write "current_setting('groundwork.write_scopes', true)"})

(defn inline
  "Compiled SQL with its params written in: the person and the visitor as
   the transaction's own, values as literals, and the scopes a via's related
   row must be in as the transaction's own for the command (read unless
   said)."
  ([sql-params] (inline sql-params :read))
  ([[sql params] command]
   (fill sql params #(case %
                       :person  "groundwork.wall_person()"
                       :visitor "groundwork.wall_visitor()"
                       :within  (within-of (if (= :read command) :read :write))
                       (sql-literal %)))))

(defn bind
  "A rule's params with the person filled in, and the visitor (nobody's,
   unless given), and the scopes a via's related row must be in (any,
   unless given as text: \"{…}\")."
  ([params person] (bind params person nil nil))
  ([params person visitor] (bind params person visitor nil))
  ([params person visitor within]
   (mapv #(case %
            :person  (some-> person str)
            :visitor (some-> visitor str)
            :within  within
            %)
         params)))

(defn compile-sentences
  "The sentences of an action on a kind as one condition over `alias`:
   {:sql :params}, nil when none gives it. `ss` are read sentences."
  [ss alias]
  (when (seq ss)
    (let [parts (distinct (map #(sentence-sql % alias) ss))]
      {:sql    (str "(" (str/join " OR " (map first parts)) ")")
       :params (vec (mapcat second parts))})))

;; =============================================================================
;; The wall
;; =============================================================================

(defn- scope-base
  "A row's scope verified for the command: read or write, through `cols`
   (the sentence's own column, or any of the row's). A row the sentence
   says may be universal passes when it has no scope at all. The verified
   scopes are a set the statement reads once (a hashed subquery), never a
   setting parsed again for every row."
  [record cols alias command universal]
  (let [all   (keys (scope-columns record))
        every (if (= :read command) "(SELECT groundwork.wall_reads_every())" "(SELECT groundwork.wall_writes_every())")
        set   (if (= :read command) "(SELECT groundwork.wall_scope_set())" "(SELECT groundwork.wall_write_set())")]
    (if (empty? all)
      nil
      (let [scoped (str "(" every " AND (" (str/join " OR " (map #(str alias "." (quote-column %) " IS NOT NULL") cols)) ")"
                        " OR " (str/join " OR " (map #(str alias "." (quote-column %) " IN " set) cols)) ")")]
        (if universal
          (str "(" scoped " OR (" (str/join " AND " (map #(str alias "." (quote-column %) " IS NULL") all)) "))")
          scoped)))))

(defn base-columns
  "Through which of the row's scope columns a sentence's row is verified
   for a request: the one its [:member <column>] names, exactly; any of
   them for an open word or a fixed scope's member; none for a person's own
   row or a row given through a related one (the related row is checked,
   in the via's function)."
  [record {:keys [who via]}]
  (cond
    (or via (= :me (:word who)))               nil
    (and (= :member (:word who)) (:column who)) [(:column who)]
    :else                                       (keys (scope-columns record))))

(defn- flag-sql
  "The session flags that deny this sentence's action for a command."
  [description ds action command]
  (for [{:keys [session] :as d} ds
        :when (and session
                   (if (= :writes (:actions d))
                     (and (write-commands command) (writes? description action))
                     (contains? (:actions d) action)))]
    (str "NOT (SELECT groundwork.wall_flag(" (sql-literal (name session)) "))")))

(defn- wall-part
  "A sentence's branch of a command's policy: its scope verified for the
   command through its own column (a person's own row, and a row a related
   row gives, need none), its words, no deny's flag."
  [description record ds command alias state? s]
  (let [cols  (base-columns record s)
        base  (when (seq cols) (scope-base record cols alias command (:universal? s)))
        flags (flag-sql description ds (:action s) command)
        sql   (inline (sentence-sql s alias {:state? state?}) command)]
    (str "(" (str/join " AND " (remove nil? (concat [base] flags [sql]))) ")")))

(defn- grouped-member-part
  "Sentences that differ only in the action a member holds, as one branch:
   one lookup of the scopes holding any of them, through their one column."
  [description record ds command alias state? group]
  (let [{:keys [who] :as s} (first group)
        held   (sort (distinct (map (comp action-name :holding :who) group)))
        base   (scope-base record [(:column who)] alias command (:universal? s))
        flags  (flag-sql description ds (:action s) command)
        member (str alias "." (quote-column (:column who)) " IN (SELECT groundwork.scopes_holding_any("
                    "groundwork.wall_person(), " (sql-literal (str "{" (str/join "," held) "}")) "::text[]))")
        state  (when-let [c (if state? (:when s) (nones-only (:when s)))] (inline (condition-sql alias c)))]
    (str "(" (str/join " AND " (remove nil? (concat [base] flags [member state]))) ")")))

(defn- command-sql
  "A command's policy over `alias`: the bypass, a job's whole scopes, or
   one of the sentences that give the command."
  [{:keys [records] :as description} kind command alias {:keys [state?] :or {state? true}}]
  (let [record (records kind)
        ds     (denies description)
        ss     (filter #(and (= kind (:kind %)) (contains? (commands-of description (:action %)) command))
                       (sentences description))
        ;; sentences that differ only in the action a member holds share one
        ;; branch: an app with dozens of actions on a kind would otherwise
        ;; carry dozens of lookups in every query's plan
        group  (fn [s]
                 (when (and (= :member (get-in s [:who :word])) (get-in s [:who :column]) (nil? (:via s)))
                   [(get-in s [:who :column]) (:when s) (:universal? s)
                    (vec (flag-sql description ds (:action s) command))]))
        groups (group-by group ss)
        parts  (concat (for [[k g] groups :when k] (grouped-member-part description record ds command alias state? g))
                       (for [s (get groups nil)] (wall-part description record ds command alias state? s)))
        whole  (when-let [cols (seq (keys (scope-columns record)))]
                 (str/join " OR " (map #(str alias "." (quote-column %) " IN (SELECT groundwork.wall_whole_set())") cols)))]
    (str "(SELECT groundwork.wall_bypass())"
         (when whole (str " OR " whole))
         (when (seq parts) (str "\n  OR " (str/join "\n  OR " (sort (distinct parts))))))))

(defn policies
  "The wall's policies for a record kind's table, one per command:
   {:select using, :insert check, :update [using check], :delete using}.

   A row shows when one of the sentences that give :read holds for it, and
   its scope was verified for the transaction (a person's own row needs no
   scope; a universal row has none). Writing is the same with the sentences
   that give the command, in the scopes the transaction may write in. A
   changed row is held to its sentence but its :when: its own record was
   checked with it before the write, and the row may move out of that
   state (closing an open order). A deny's session flag takes its action's
   sentences out. The system passes; so does a job, in the scopes it names."
  [{:keys [records] :as description} kind]
  (let [alias (quote-table (get-in records [kind :table]))]
    {:select (command-sql description kind :read alias {})
     :insert (command-sql description kind :insert alias {})
     :update [(command-sql description kind :update alias {})
              (command-sql description kind :update alias {:state? false})]
     :delete (command-sql description kind :delete alias {})}))

(defn insert-check
  "The insert policy's condition over another alias (NEW, in a trigger)."
  [description kind alias]
  (command-sql description kind :insert alias {}))

;; =============================================================================
;; Vias: their functions, and the guard on the rows they look for
;; =============================================================================

(defn vias
  "Every distinct via of the description."
  [description]
  (vec (distinct (keep :via (sentences description)))))

(defn via-sql-statement
  "The statement that installs a via's function, given the types of the
   other table's matched columns ({plain column → type}). The bypass is on
   only while the other rows are read, then back to what it was; an error
   ends the transaction, and the setting with it. STABLE: within one
   statement it answers the same, so a query asks it once and looks each
   row up in what it answered."
  [{:keys [table match who record] condition :when :as via} types]
  (let [others  (map (comp column second) match)
        ;; the related row is in the scopes asked for: one of its scope
        ;; columns, or none when it belongs to nobody; a person's own row,
        ;; or one of a kind with no scope, wherever it is
        cols    (keys (scope-columns record))
        in-sql  (if (or (= :me (:word who)) (empty? cols))
                  "TRUE"
                  (str "(within_every OR " (str/join " OR " (map #(str "gwo." (quote-column %) " = ANY (within_set)") cols))
                       " OR (" (str/join " AND " (map #(str "gwo." (quote-column %) " IS NULL") cols)) "))"))
        outs    (map-indexed (fn [i c] (str "via_" (inc i) " " (get types c))) others)
        [w wps] (who-sql "gwo" who)
        member? (and (= :member (:word who)) (:column who))
        w-sql   (if member?
                  (str "gwo." (quote-column (:column who)) " = ANY (held)")
                  (fill w wps #(case %
                                 :person  "person"
                                 :visitor "visitor"
                                 (sql-literal %))))
        c-sql   (when condition (let [[c ps] (condition-sql "gwo" condition)] (fill c ps sql-literal)))]
    (str "CREATE OR REPLACE FUNCTION " (via-function via) "(person uuid, visitor uuid, within text)\n"
         "RETURNS TABLE (" (str/join ", " outs) ") LANGUAGE plpgsql STABLE AS $$\n"
         "DECLARE before text := coalesce(current_setting('groundwork.bypass', true), '');\n"
         "        within_every boolean := within IS NULL OR within = '*';\n"
         "        within_set uuid[] := CASE WHEN within IS NULL OR within = '*' THEN '{}'::uuid[]\n"
         "                                  ELSE coalesce(nullif(within, ''), '{}')::uuid[] END;\n"
         ;; the scopes first, as values: the rows are then found by their who
         ;; column (an index on it), however many there are
         (when member?
           (str "        held uuid[] := ARRAY(SELECT groundwork.scopes_holding(person, "
                (sql-literal (action-name (:holding who))) "));\n"))
         "BEGIN\n"
         "  PERFORM set_config('groundwork.bypass', 'on', true);\n"
         "  RETURN QUERY SELECT DISTINCT " (str/join ", " (map #(str "gwo." (quote-id %)) others))
         " FROM " (quote-id table) " gwo\n"
         "    WHERE " w-sql (when c-sql (str " AND " c-sql)) " AND " in-sql ";\n"
         "  PERFORM set_config('groundwork.bypass', before, true);\n"
         "END $$")))

(defn via-tables
  "For the bells: {a via's table → the tables of the records it gives},
   since a row of it made, changed or gone changes who sees those."
  [{:keys [records] :as description}]
  (reduce (fn [m {:keys [kind via]}]
            (if via
              (update m (:table via) (fnil conj #{}) (table (get-in records [kind :table])))
              m))
          {}
          (sentences description)))

(defn guard-sql
  "The statements that guard a via's table: a change that would make one
   of its rows give something else (another row matched, another who on
   it, its condition holding again) is creating one, and only someone the
   table's insert sentences allow may do it. Safe to run again."
  [description kind]
  (let [{t :table} (get-in description [:records kind])
        ts      (table t)
        mine    (filter #(= ts (get-in % [:via :table])) (sentences description))
        cols    (sort (distinct (mapcat (fn [{:keys [via]}]
                                          (concat (map (comp column second) (:match via))
                                                  (keep #(some-> % column) [(get-in via [:who :column])])))
                                        mine)))
        whens   (distinct (keep (comp :when :via) mine))
        literal (fn [alias c] (let [[s ps] (condition-sql alias c)] (fill s ps sql-literal)))
        changed (concat (map #(str "NEW." (quote-id %) " IS DISTINCT FROM OLD." (quote-id %)) cols)
                        (map #(str "((" (literal "NEW" %) ") IS TRUE AND (" (literal "OLD" %) ") IS NOT TRUE)") whens))
        guard   (str "groundwork." (quote-id (str "via_guard__" ts)))]
    [(str "CREATE OR REPLACE FUNCTION " guard "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
          "BEGIN\n"
          "  IF (" (str/join "\n      OR " changed) ")\n"
          "     AND NOT (" (insert-check description kind "NEW") ") THEN\n"
          "    RAISE EXCEPTION 'Only someone who may create a row of " ts
          " may point it at another row or make it hold again' USING ERRCODE = '42501';\n"
          "  END IF;\n"
          "  RETURN NEW;\n"
          "END $$")
     (str "CREATE OR REPLACE TRIGGER groundwork_via_guard BEFORE UPDATE ON " (quote-id ts)
          " FOR EACH ROW EXECUTE FUNCTION " guard "()")]))

(defn guarded-kinds
  "The record kinds whose rows a via looks for."
  [description]
  (into (sorted-set) (map (comp :kind :via)) (filter :via (sentences description))))

;; =============================================================================
;; Narrowing a list by an action: a function per action and record kind
;; =============================================================================

(defn may-function
  "The name of the function a query file calls to narrow a list to the rows
   the person may do an action on: groundwork.\"may orders/edit on order\",
   (), the ids of those rows.

     SELECT order__id, order__note FROM orders
     WHERE order__id IN (SELECT * FROM groundwork.\"may orders/edit on order\"())"
  [action kind]
  (str "groundwork." (quote-id (str "may " (action-name action) " on " (name kind)))))

(defn deny-function
  "The name of the function that answers, past the wall, which of the
   transaction's scopes a scope's state denies in (for one state deny)."
  [{:keys [scope] condition :when}]
  (str "groundwork." (quote-id (str "denied scopes " (name scope) " " (format "%08x" (hash [scope condition]))))))

(defn deny-sql-statement
  "The statement that installs a state deny's function: the scopes, of the
   ones the transaction reads (or every one), whose row in the app's own
   table matches the deny's condition. The bypass is on only while that
   table is read. Safe to run again."
  [{:keys [scopes]} {:keys [scope] condition :when :as d}]
  (let [{t :table id :id} (scopes scope)
        [c ps]            (condition-sql "s" condition)]
    (str "CREATE OR REPLACE FUNCTION " (deny-function d) "()\n"
         "RETURNS SETOF uuid LANGUAGE plpgsql STABLE AS $$\n"
         "DECLARE before text := coalesce(current_setting('groundwork.bypass', true), '');\n"
         "BEGIN\n"
         "  PERFORM set_config('groundwork.bypass', 'on', true);\n"
         "  RETURN QUERY SELECT s." (quote-column id) " FROM " (quote-table t) " s WHERE " (fill c ps sql-literal)
         " AND (groundwork.wall_reads_every() OR s." (quote-column id) " IN (SELECT groundwork.wall_scope_set()));\n"
         "  PERFORM set_config('groundwork.bypass', before, true);\n"
         "END $$")))

(defn may-sql-statement
  "The statement that installs the function narrowing a kind's rows to
   those the person may do an action on: the action's sentences on the
   kind, but what a session flag or a scope's state denies. It runs as the
   caller, behind the wall: it answers only rows the transaction may read.
   `id-type` is the type of the kind's id column. Safe to run again."
  [{:keys [records] :as description} action kind id-type]
  (let [{t :table id :id :as record} (records kind)
        ss      (filter #(and (= action (:action %)) (= kind (:kind %))) (sentences description))
        ds      (filter #(deny-applies? description % action) (denies description))
        flags   (for [{:keys [session]} ds :when session]
                  (str "NOT (SELECT groundwork.wall_flag(" (sql-literal (name session)) "))"))
        states  (for [{:keys [scope] :as d} ds
                      :when scope
                      [col col-kind] (scope-columns record)
                      :when (= scope col-kind)]
                  (str "(r." (quote-column col) " IS NULL OR r." (quote-column col)
                       " NOT IN (SELECT * FROM " (deny-function d) "()))"))
        granted (str "(" (str/join " OR " (distinct (map #(inline (sentence-sql % "r")) ss))) ")")]
    (str "CREATE OR REPLACE FUNCTION " (may-function action kind) "()\n"
         "RETURNS SETOF " id-type " LANGUAGE sql STABLE AS $$\n"
         "  SELECT r." (quote-column id) " FROM " (quote-table t) " r\n"
         "  WHERE " (str/join "\n    AND " (concat [granted] flags states)) "\n"
         "$$")))

;; =============================================================================
;; Records under records: the matching link and the fill
;; =============================================================================

(defn parent-links
  "For every record kind under another record: the matching link the
   database must have, as plain names. For each column the child carries,
   its (parent column, column) must reference the parent's (id, column), ON
   UPDATE CASCADE, so it can't differ from its parent's and follows it when
   the parent changes. :carried is [{:column :parent-column :person?}];
   :scope-column and :parent-scope are the first carried scope's."
  [{:keys [records] :as description}]
  (for [[kind {t :table [parent-kind parent-col] :parent :as record}] records
        :when parent-kind
        :let  [{pt :table pid :id} (records parent-kind)
               scope-cols          (set (keys (scope-columns record)))
               carried             (vec (for [[mine parents] (sort-by (comp str key) (carried-columns description kind))]
                                          {:column        (column mine)
                                           :parent-column (column parents)
                                           :person?       (not (contains? scope-cols mine))}))
               first-scope         (first (remove :person? carried))]]
    {:kind          kind
     :table         (table t)
     :scope-column  (:column first-scope)
     :parent-column (column parent-col)
     :parent-table  (table pt)
     :parent-id     (column pid)
     :parent-scope  (:parent-column first-scope)
     :carried       carried}))

(defn fill-sql
  "The statements that install a link's fill rule: a row inserted without
   what it carries takes its parent's. And for a person it carries, a rule
   on the parent that hands a person named there later down to the rows
   under it (the link itself only follows a change from one person to
   another). Safe to run again."
  [{t :table :keys [parent-column parent-table parent-id carried]}]
  (let [function (str "groundwork." (quote-id (str "fill_scope__" t)))
        people   (filter :person? carried)
        carry    (str "groundwork." (quote-id (str "carry_person__" t)))]
    (concat
     [(str "CREATE OR REPLACE FUNCTION " function "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
           "BEGIN\n"
           (apply str
                  (for [{c :column p :parent-column} carried]
                    (str "  IF NEW." (quote-id c) " IS NULL THEN\n"
                         "    SELECT p." (quote-id p) " INTO NEW." (quote-id c)
                         " FROM " (quote-id parent-table) " p WHERE p." (quote-id parent-id)
                         " = NEW." (quote-id parent-column) ";\n"
                         "  END IF;\n")))
           "  RETURN NEW;\n"
           "END $$")
      (str "CREATE OR REPLACE TRIGGER groundwork_fill_scope BEFORE INSERT ON " (quote-id t)
           " FOR EACH ROW EXECUTE FUNCTION " function "()")]
     (when (seq people)
       ;; the bypass is on only while the rows under the parent are written,
       ;; then back to what it was
       [(str "CREATE OR REPLACE FUNCTION " carry "() RETURNS trigger LANGUAGE plpgsql AS $$\n"
             "DECLARE before text := coalesce(current_setting('groundwork.bypass', true), '');\n"
             "BEGIN\n"
             "  PERFORM set_config('groundwork.bypass', 'on', true);\n"
             (apply str
                    (for [{c :column p :parent-column} people]
                      (str "  IF NEW." (quote-id p) " IS DISTINCT FROM OLD." (quote-id p) " THEN\n"
                           "    UPDATE " (quote-id t) " c SET " (quote-id c) " = NEW." (quote-id p)
                           " WHERE c." (quote-id parent-column) " = NEW." (quote-id parent-id)
                           " AND c." (quote-id c) " IS DISTINCT FROM NEW." (quote-id p) ";\n"
                           "  END IF;\n")))
             "  PERFORM set_config('groundwork.bypass', before, true);\n"
             "  RETURN NULL;\n"
             "END $$")
        (str "CREATE OR REPLACE TRIGGER " (quote-id (str "groundwork_carry__" t)) " AFTER UPDATE ON "
             (quote-id parent-table) " FOR EACH ROW EXECUTE FUNCTION " carry "()")]))))

;; =============================================================================
;; What the other parts read
;; =============================================================================

(defn record-scope-columns
  "Every record table's scope columns, as plain names."
  [{:keys [records]}]
  (for [[kind record] records
        [col _]       (scope-columns record)]
    {:kind kind :table (table (:table record)) :column (column col)}))

(defn visitor-actions
  "The actions some sentence gives a visitor: their endpoints may be asked
   by someone not signed in."
  [description]
  (into #{} (keep (fn [{:keys [who via action]}]
                    (when (or (:visitor? who) (:visitor? (:who via))) action)))
        (sentences description)))

(defn visitor-moves
  "For sign-in: every table with a visitor column, and the person column a
   visitor's rows move to there (nil when the kind has none):
   [{:table :visitor-column :person-column}]."
  [{:keys [records]}]
  (vec (for [[_ {t :table :as record}] records
             :let [v (visitor-column record)]
             :when v]
         {:table          (table t)
          :visitor-column (column v)
          :person-column  (some-> (first (person-columns record)) column)})))

(defn ringing
  "How a record kind's table rings: its scope columns, its person and
   visitor columns, and whether its rows may belong to nobody."
  [{:keys [records] :as description} kind]
  (let [record (records kind)]
    {:scopes     (mapv column (keys (scope-columns record)))
     :people     (mapv column (concat (person-columns record) (some-> (visitor-column record) vector)))
     :universal? (boolean (or (empty? (scope-columns record))
                              (some #(and (= kind (:kind %)) (:universal? %)) (sentences description))))}))

(defn open-read-tables
  "The tables someone who belongs nowhere may read rows of: the kinds a
   sentence gives to :anyone or :signed-in, with :read."
  [{:keys [records] :as description}]
  (into #{} (keep (fn [{:keys [kind action] :as s}]
                    (when (and (open-word s) (contains? (commands-of description action) :read))
                      (table (get-in records [kind :table])))))
        (sentences description)))
