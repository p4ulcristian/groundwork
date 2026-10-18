(ns groundwork.permissions.sql
  "The permission description's sentences as SQL (see
   groundwork.permissions.description for the language): one sentence
   becomes one SQL condition over the record's table, so the same text
   checks one record and filters a list. From them come the wall's
   policies (row-level security per table and command, a command's
   sentences OR-ed), the vias' functions and the guards on the rows they
   look for, the functions that narrow a list by an action, and the fill
   rules of records under records."
  (:require
   [clojure.string :as str]
   [groundwork.permissions :as-alias permissions]
   [groundwork.permissions.description :as description]))

(defn- quote-id [s] (str "\"" s "\""))

(defn quote-table [t] (quote-id (description/table t)))
(defn quote-column [c] (quote-id (description/column c)))

(defn sql-literal
  "A value from the description as an SQL literal, for SQL Groundwork
   installs (functions, policies) where nothing can be bound."
  [v]
  (cond
    (string? v)                     (str "'" (str/replace v "'" "''") "'")
    (boolean? v)                    (if v "TRUE" "FALSE")
    (or (int? v) (decimal? v))      (str v)
    (instance? java.util.UUID v)    (str "'" v "'::uuid")
    :else (throw (ex-info (str "A condition's value " (pr-str v) " must be a string, number, boolean or UUID")
                          {:type ::permissions/bad-description :value v}))))

(defn- fill
  "SQL with each ? replaced, in order, by the text for its param. Split
   first, so a ? inside a value written in isn't taken for the next one."
  [sql params text-of]
  (let [parts (str/split sql #"\?" -1)]
    (when-not (= (count parts) (inc (count params)))
      (throw (ex-info "Params don't match the SQL's placeholders" {:sql sql :params params})))
    (apply str (first parts) (mapcat (fn [p part] [(text-of p) part]) params (rest parts)))))

;; =============================================================================
;; Compiling: a sentence as SQL
;; =============================================================================

(defn condition-sql
  "A condition on a table's own columns: [sql params]."
  [alias condition]
  (let [[op & args] condition
        col         #(str alias "." (quote-id (description/column %)))]
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
                                [:person (description/action-name holding)]]
                 (:kind who)   [(str "EXISTS (SELECT 1 FROM groundwork.scopes gws WHERE gws.scope__kind = ?"
                                     " AND gws.scope__id IN " holding-sql ")")
                                [(name (:kind who)) :person (description/action-name holding)]]
                 (:scope who)  [(str "CAST(? AS uuid) IN " holding-sql) [(str (:scope who)) :person (description/action-name holding)]])
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
              (when-let [c (if state? condition (description/nones-only condition))] (condition-sql alias c))])))

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
  (let [all   (keys (description/scope-columns record))
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
    :else                                       (keys (description/scope-columns record))))

(defn- flag-sql
  "The session flags that deny this sentence's action for a command."
  [description ds action command]
  (for [{:keys [session] :as d} ds
        :when (and session
                   (if (= :writes (:actions d))
                     (and (description/write-commands command) (description/writes? description action))
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
        held   (sort (distinct (map (comp description/action-name :holding :who) group)))
        base   (scope-base record [(:column who)] alias command (:universal? s))
        flags  (flag-sql description ds (:action s) command)
        member (str alias "." (quote-column (:column who)) " IN (SELECT groundwork.scopes_holding_any("
                    "groundwork.wall_person(), " (sql-literal (str "{" (str/join "," held) "}")) "::text[]))")
        state  (when-let [c (if state? (:when s) (description/nones-only (:when s)))] (inline (condition-sql alias c)))]
    (str "(" (str/join " AND " (remove nil? (concat [base] flags [member state]))) ")")))

(defn- command-sql
  "A command's policy over `alias`: the bypass, a job's whole scopes, or
   one of the sentences that give the command."
  [{:keys [records] :as description} kind command alias {:keys [state?] :or {state? true}}]
  (let [record (records kind)
        ds     (description/denies description)
        ss     (filter #(and (= kind (:kind %)) (contains? (description/commands-of description (:action %)) command))
                       (description/sentences description))
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
        whole  (when-let [cols (seq (keys (description/scope-columns record)))]
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

(defn via-sql-statement
  "The statement that installs a via's function, given the types of the
   other table's matched columns ({plain column → type}). The bypass is on
   only while the other rows are read, then back to what it was; an error
   ends the transaction, and the setting with it. STABLE: within one
   statement it answers the same, so a query asks it once and looks each
   row up in what it answered."
  [{:keys [table match who record] condition :when :as via} types]
  (let [others  (map (comp description/column second) match)
        ;; the related row is in the scopes asked for: one of its scope
        ;; columns, or none when it belongs to nobody; a person's own row,
        ;; or one of a kind with no scope, wherever it is
        cols    (keys (description/scope-columns record))
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
                (sql-literal (description/action-name (:holding who))) "));\n"))
         "BEGIN\n"
         "  PERFORM set_config('groundwork.bypass', 'on', true);\n"
         "  RETURN QUERY SELECT DISTINCT " (str/join ", " (map #(str "gwo." (quote-id %)) others))
         " FROM " (quote-id table) " gwo\n"
         "    WHERE " w-sql (when c-sql (str " AND " c-sql)) " AND " in-sql ";\n"
         "  PERFORM set_config('groundwork.bypass', before, true);\n"
         "END $$")))

(defn guard-sql
  "The statements that guard a via's table: a change that would make one
   of its rows give something else (another row matched, another who on
   it, its condition holding again) is creating one, and only someone the
   table's insert sentences allow may do it. Safe to run again."
  [description kind]
  (let [{t :table} (get-in description [:records kind])
        ts      (description/table t)
        mine    (filter #(= ts (get-in % [:via :table])) (description/sentences description))
        cols    (sort (distinct (mapcat (fn [{:keys [via]}]
                                          (concat (map (comp description/column second) (:match via))
                                                  (keep #(some-> % description/column) [(get-in via [:who :column])])))
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
  (str "groundwork." (quote-id (str "may " (description/action-name action) " on " (name kind)))))

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
        ss      (filter #(and (= action (:action %)) (= kind (:kind %))) (description/sentences description))
        ds      (filter #(description/deny-applies? description % action) (description/denies description))
        flags   (for [{:keys [session]} ds :when session]
                  (str "NOT (SELECT groundwork.wall_flag(" (sql-literal (name session)) "))"))
        states  (for [{:keys [scope] :as d} ds
                      :when scope
                      [col col-kind] (description/scope-columns record)
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
;; Records under records: the fill
;; =============================================================================

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
