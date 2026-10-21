(ns groundwork.permissions.description
  "An app's permission description, read and checked.

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
   table: the same text checks one record and filters a list (see
   groundwork.permissions.sql)."
  (:require
   [clojure.string :as str]
   [groundwork.permissions :as-alias permissions]
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
  (throw (ex-info message (assoc data :type ::permissions/bad-description))))

(defn column
  "A column as the database names it: :order/shop-id → \"order__shop_id\"."
  [col]
  (names/key->column col))

(defn table
  "A table's plain name, or a problem when it isn't one."
  [t]
  (let [s (name t)]
    (when-not (re-matches #"[a-z_][a-z0-9_]*" s)
      (problem (str "Table " t " isn't a plain lowercase name") {:table t}))
    s))

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

(defn nones-only
  "Of a condition, only its [:none …] at the top: what stays of :when on a
   changed row (the row's state may change; whether it belongs to nobody
   may not)."
  [condition]
  (when-let [nones (seq (some-> condition top-nones))]
    (if (= 1 (count nones)) [:none (first nones)] (into [:and] (map (fn [c] [:none c]) nones)))))

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

(defn- read-sentences
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

(def ^:private last-read
  "The description read last and its sentences: making the wall asks for
   them once per table and command and once per list function, hundreds of
   times for one description, each read costing tens of milliseconds."
  (atom nil))

(defn sentences
  "The allow sentences, one per action, each read: {:n its place in :rules,
   :rule as written, :action :kind :who :via :when :universal?}. Read once
   for the description asked about last."
  [description]
  (let [[d ss] @last-read]
    (if (identical? d description)
      ss
      (let [ss (read-sentences description)]
        (reset! last-read [description ss])
        ss))))

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
;; Records under records: the matching link
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

;; =============================================================================
;; Vias
;; =============================================================================

(defn vias
  "Every distinct via of the description."
  [description]
  (vec (distinct (keep :via (sentences description)))))

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

(defn guarded-kinds
  "The record kinds whose rows a via looks for."
  [description]
  (into (sorted-set) (map (comp :kind :via)) (filter :via (sentences description))))

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
