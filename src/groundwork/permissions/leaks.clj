(ns groundwork.permissions.leaks
  "The built-in leak test, for an app's own tests: every endpoint whose
   :access is an action is called as a member of one scope, with ids from
   another, and must answer not-found. It catches what the declarations
   can't: a sentence that gives too much, a via whose condition forgot
   something. It proves the wall matches the sentences, not that the
   sentences are right: a sentence that is too open passes it.

     (deftest nothing-leaks
       (let [report (leaks/check (gw/endpoints app)
                                 {:person  peter                 ; a member of Acme
                                  :foreign {:shop    zinc        ; another shop, and records of it
                                            :order   zinc-order
                                            :job     zinc-job
                                            :machine zinc-machine}})]
         (is (empty? (:leaked report)) (pr-str (:leaked report)))))

   Each endpoint's params are generated from its input shape, then every
   id it carries (its :access's own, and every marked field) is set to
   the foreign one of that kind, as text (the way a JSON ask brings ids).
   An endpoint is skipped when no foreign id of a kind it needs was given,
   its shape can't be generated, or what was generated doesn't fit it; public
   and signed-in endpoints carry no ids and are skipped too. Writes are
   called like reads: they're refused before they run, or they leak.

   An endpoint about several scopes (:scopes) is asked with a list of two:
   a scope of that kind where the person holds the action (from their
   grants), and the foreign one; or the foreign one alone when they hold it
   nowhere. It must answer not-found: one scope they may not act in stops
   the whole request. A list on the way to the ids holds one element per
   scope, shaped like the generated one.

   A list carries no ids: it's called as it is, and its answer must hold
   none of the foreign ids, anywhere in it.

   An action a sentence opens to :anyone or :signed-in is everyone's, so its
   endpoints are called as a stranger: nobody for one open to anyone, a
   person who belongs nowhere for one open to the signed-in. They may
   answer; what they answer must hold none of the :sealed ids (give the ids
   no stranger may see: an unpublished row, a row of a kind no open sentence
   reaches). An open write that answers refused or an error changed nothing
   and showed nothing: it is checked, not leaked.

   The wall itself is asked too (wall-check): for every kind of record and
   every foreign scope, what nobody, a signed-in stranger and the person see
   behind it must be exactly the rows the sentences give them there. And so
   is every function that narrows a list by an action (may-check): it must
   answer exactly the rows the wall shows that the action's sentences give.

   An action a sentence gives a visitor may be asked by someone not signed
   in, so its endpoints are asked twice more with the foreign ids: as
   another visitor (a visitor session that isn't the record's) and as a
   signed-in stranger. Both must answer not-found.

   Generating needs org.clojure/test.check on the test classpath."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.endpoints :as endpoints]
   [groundwork.endpoints.wire :as wire]
   [groundwork.permissions.check :as check]
   [groundwork.permissions.description :as description]
   [groundwork.permissions.sql :as sql]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc]))

(defn- generate
  ([shape] (generate shape 42))
  ([shape seed]
   (require 'malli.experimental.time.generator)
   ((requiring-resolve 'malli.generator/generate) (wire/schema shape) {:seed seed :size 3})))

(defn- reaches?
  "Whether generated params have something at every step of a path but the
   last: a map, or a list holding one."
  [x path]
  (or (<= (count path) 1)
      (let [v (get x (first path))]
        (cond
          (sequential? v) (boolean (some #(and (map? %) (reaches? % (rest path))) v))
          (map? v)        (reaches? v (rest path))
          :else           false))))

(defn- generate-for
  "An endpoint's params, generated from its input shape. For one about
   several scopes, a seed whose params reach the scopes' path, so a list on
   the way has an element to shape the others like."
  [{:keys [access input]}]
  (if (and (map? access) (:scopes access))
    (let [path (endpoints/scopes-path access)]
      (or (some (fn [seed] (let [g (generate input seed)] (when (reaches? g path) g)))
                (range 42 74))
          (generate input)))
    (generate input)))

(defn- put-scopes
  "The params with the scopes' path set to these ids: a list on the way
   holds one element per id, shaped like its first map; a list at the end
   holds the ids."
  [x path ids]
  (let [[k & more] path
        v          (get x k)]
    (cond
      (empty? more)   (assoc x k (if (and (some? v) (not (coll? v))) (first ids) (vec ids)))
      (sequential? v) (assoc x k (mapv #(put-scopes (or (first (filter map? v)) {}) more [%]) ids))
      :else           (assoc x k (put-scopes (if (map? v) v {}) more ids)))))

(defn- own-scope
  "A scope of a kind where the person holds an action, from their grants,
   other than the foreign one; or nil."
  [perms {:keys [person flags]} action kind foreign]
  (when person
    (let [held (keep (fn [{:grant/keys [scope-id] a :grant/action}] (when (= a action) scope-id))
                     (check/grants perms {:person person :flags (or flags #{})}))]
      (first (sort-by str (disj (check/of-kind perms (set held) kind) foreign))))))

(defn- foreign-ids
  "The params with every id the endpoint carries set to the foreign one of
   its kind; nil when a kind has no foreign id. `own`, for an endpoint about
   several scopes: a scope the person holds its action in, named first."
  [{:keys [access ids]} params foreign scope-kinds own]
  (let [{:keys [record scope scopes]} access
        [own-kind own-param]   (or record scope scopes)
        ;; held in any kind of scope (:groundwork/team): any foreign scope
        own-kind               (if (= :groundwork/any own-kind)
                                 (some #(when (contains? foreign %) %) scope-kinds)
                                 own-kind)
        needed                 (cond-> (set (vals ids)) own-kind (conj own-kind))]
    (when (every? #(contains? foreign %) needed)
      ;; ids go in as text, the way a JSON ask brings them; a fixed scope
      ;; (an id in the :access itself) has no param to set
      (let [text   #(str (foreign %))
            params (cond
                     scopes               (put-scopes params (endpoints/scopes-path access)
                                                      (map str (cond-> [] own (conj own) :always (conj (foreign own-kind)))))
                     (keyword? own-param) (assoc params own-param (text own-kind))
                     :else                params)]
        (walk/postwalk (fn [x]
                         (if (map? x)
                           (reduce-kv (fn [m k kind]
                                        (if (contains? m k)
                                          (assoc m k (if (coll? (get m k)) [(text kind)] (text kind)))
                                          m))
                                      x ids)
                           x))
                       params)))))

(defn- holds-any?
  "Whether data holds any of the ids, at any depth."
  [data ids]
  (let [found (volatile! false)]
    (walk/postwalk (fn [x] (when (contains? ids x) (vreset! found true)) x) data)
    @found))

(defn- ids-seen
  "The ids of a kind's rows a transaction sees, as text."
  [tx {t :table id :id} where params]
  (into #{}
        (map (comp str val first))
        (jdbc/execute! (::pg/conn tx)
                       (into [(str "SELECT r." (sql/quote-column id) "::text AS id FROM " (sql/quote-table t) " r"
                                   (when where (str " WHERE " where)))]
                             params))))

(defn wall-check
  "What the wall shows, against what the sentences give: for every kind of
   record and every scope given ({scope-kind scope-id}), as nobody, as a
   signed-in stranger and as `person`, behind a wall verified for that one
   scope. The wall's policies and the sentences are compiled apart (the
   wall's with the transaction's own person and scopes written in, the
   sentences' with them bound), so a difference is a bug in one of them.
   Returns the differences, [{:kind :as :scope :shown ids :hidden ids}]:
   :shown, rows the wall shows that no sentence gives; :hidden, rows a
   sentence gives that the wall hides."
  [perms scopes {:keys [person]}]
  (let [description (:description perms)
        records     (:records description)]
    (vec
     (for [[_ scope]     (sort-by (comp str key) scopes)
           [kind record] (sort-by key records)
           [as caller]   [[:nobody nil] [:signed-in-stranger (random-uuid)] [:person person]]
           :when         (or caller (not= :person as))
           :let [ss      (filter #(and (= kind (:kind %))
                                       (contains? (description/commands-of description (:action %)) :read))
                                 (:sentences perms))
                 seen    (check/in-wall perms {:person caller :scopes #{scope} :read-only? true}
                                              #(ids-seen % record nil []))
                 cols    (keys (description/scope-columns record))
                 within  (str "{" scope "}")
                 parts   (for [s ss]
                           ;; the same rules as the wall's, written apart: the row's scope through
                           ;; the sentence's own column, none for a person's own row or one a
                           ;; related row gives (whose own scope the via's function holds to this one)
                           (let [[w ps] (sql/sentence-sql s "r")
                                 by     (sql/base-columns record s)
                                 base   (when (and (seq by) (seq cols))
                                          (str "((" (str/join " OR " (map #(str "r." (sql/quote-column %) " = CAST(? AS uuid)") by)) ")"
                                               (when (:universal? s)
                                                 (str " OR (" (str/join " AND " (map #(str "r." (sql/quote-column %) " IS NULL") cols)) ")"))
                                               ")"))]
                             [(str "(" (when base (str base " AND ")) w ")")
                              (into (if base (vec (repeat (count by) (str scope))) []) (sql/bind ps caller nil within))]))
                 given   (if (seq parts)
                           (check/as-system perms #(ids-seen % record (str/join " OR " (map first parts))
                                                                   (vec (mapcat second parts))))
                           #{})
                 shown   (set/difference seen given)
                 hidden  (set/difference given seen)]
           :when (or (seq shown) (seq hidden))]
       {:kind kind :as as :scope scope :shown (vec (sort shown)) :hidden (vec (sort hidden))}))))

(defn may-check
  "What each function that narrows a list by an action answers (see
   sql/may-function), against the sentences: for every action and kind,
   every scope given, as nobody, a signed-in stranger and `person`, behind a
   wall verified for that one scope, it must answer exactly the rows the
   wall shows that the action's sentences give, but those a scope's state
   denies. Returns the differences, [{:action :kind :as :scope :shown
   :hidden}]."
  [perms scopes {:keys [person]}]
  (let [description (:description perms)
        records     (:records description)]
    (vec
     (for [[_ scope]       (sort-by (comp str key) scopes)
           [[action kind] ss] (sort-by (comp str key) (:by-action-kind perms))
           [as caller]     [[:nobody nil] [:signed-in-stranger (random-uuid)] [:person person]]
           :when           (or caller (not= :person as))
           :let [record  (records kind)
                 answered (check/in-wall
                           perms {:person caller :scopes #{scope} :read-only? true}
                           (fn [tx]
                             (into #{}
                                   (map (comp str val first))
                                   (jdbc/execute! (::pg/conn tx)
                                                  [(str "SELECT x::text AS id FROM " (sql/may-function action kind) "() x")]))))
                 seen     (check/in-wall perms {:person caller :scopes #{scope} :read-only? true}
                                               #(ids-seen % record nil []))
                 {where :sql params :params} (sql/compile-sentences ss "r")
                 denied   (check/as-system perms #(set (keys (check/denied-scopes perms % action #{scope}))))
                 given    (if (or (seq denied) (check/blocked? perms action #{}))
                            #{}
                            (check/as-system perms #(ids-seen % record where (sql/bind params caller nil (str "{" scope "}")))))
                 expected (set/intersection seen given)
                 shown    (set/difference answered expected)
                 hidden   (set/difference expected answered)]
           :when (or (seq shown) (seq hidden))]
       {:action action :kind kind :as as :scope scope :shown (vec (sort shown)) :hidden (vec (sort hidden))}))))

(defn check
  "Call every endpoint with another scope's ids. Returns {:checked [names]
   :skipped {name reason} :leaked [{:endpoint :params :answer}] :hidden […]}.
   Open actions are called as a stranger and must show none of the :sealed
   ids. A row the wall shows that no sentence gives is in :leaked as {:wall
   {:kind :as :scope :shown}}; a row a sentence gives that the wall hides is
   in :hidden."
  [part {:keys [person flags foreign sealed]}]
  (reduce (fn [report [k entry]]
            (let [access (:access entry)
                  open   (when (map? access) (check/open-level (:perms part) (:action access)))]
              (cond
                (#{:public :signed-in} access)
                (assoc-in report [:skipped k] "carries no ids")

                open
                (let [generated (try (generate-for entry) (catch Exception e e))
                      params    (when-not (instance? Exception generated)
                                  (foreign-ids entry generated foreign (keys (get-in part [:perms :description :scopes])) nil))]
                  (cond
                    (instance? Exception generated)
                    (assoc-in report [:skipped k] (str "its input can't be generated: " (ex-message generated)))

                    (nil? params)
                    (assoc-in report [:skipped k] "no foreign id given for a kind it carries")

                    :else
                    (let [answer (endpoints/call part {:endpoint k :params params
                                                       :person   (when (= :signed-in open) (random-uuid))
                                                       :flags    #{} :request-id "leak-test"})]
                      (cond
                        (= :invalid (:status answer))
                        (assoc-in report [:skipped k] (str "the generated params don't fit its input: "
                                                           (pr-str (:errors answer))))
                        (and (= :ok (:status answer)) (holds-any? (:data answer) (set sealed)))
                        (update report :leaked conj {:endpoint k :params params :answer answer})
                        (#{:ok :not-found} (:status answer))
                        (update report :checked conj k)
                        ;; a write refused, or failing on what was generated, changed nothing
                        ;; and showed nothing: an open write may be narrowed by its :when or
                        ;; its :via, and the app's own checks may say no
                        (and (not (:read? entry)) (#{:refused :error :too-many} (:status answer)))
                        (update report :checked conj k)
                        :else
                        (update report :leaked conj {:endpoint k :params params :answer answer})))))

                (and (:list access) (empty? (:ids entry)))
                (let [generated (try (generate-for entry) (catch Exception e e))]
                  (if (instance? Exception generated)
                    (assoc-in report [:skipped k] (str "its input can't be generated: " (ex-message generated)))
                    (let [answer (endpoints/call part {:endpoint k :params generated :person person
                                                       :flags (or flags #{}) :request-id "leak-test"})]
                      (if (and (= :ok (:status answer)) (holds-any? (:data answer) (set (vals foreign))))
                        (update report :leaked conj {:endpoint k :params generated :answer answer})
                        (update report :checked conj k)))))

                :else
                (let [generated (try (generate-for entry) (catch Exception e e))
                      own       (when-let [[kind] (:scopes access)]
                                  (own-scope (:perms part) {:person person :flags flags} (:action access) kind
                                             (some-> (get foreign kind) str parse-uuid)))]
                  (if (instance? Exception generated)
                    (assoc-in report [:skipped k] (str "its input can't be generated: " (ex-message generated)))
                    (if-let [params (foreign-ids entry generated foreign (keys (get-in part [:perms :description :scopes])) own)]
                      (let [ask     {:endpoint k :params params :flags (or flags #{}) :request-id "leak-test"}
                            answer  (endpoints/call part (assoc ask :person person))
                            ;; a visitor's records: as another visitor, and as a signed-in stranger
                            others  (when (:visitors? entry)
                                      [(endpoints/call part (assoc ask :person nil :visitor (random-uuid)))
                                       (endpoints/call part (assoc ask :person (random-uuid)))])
                            through (first (remove #(= :not-found (:status %)) others))]
                        (cond
                          ;; the generated params didn't reach the check: not a leak, not checked either
                          (= :invalid (:status answer))
                          (assoc-in report [:skipped k] (str "the generated params don't fit its input: "
                                                             (pr-str (:errors answer))))
                          (not= :not-found (:status answer))
                          (update report :leaked conj {:endpoint k :params params :answer answer})
                          through
                          (update report :leaked conj {:endpoint k :params params :answer through
                                                       :as "another visitor, or a signed-in stranger"})
                          :else
                          (update report :checked conj k)))
                      (assoc-in report [:skipped k] "no foreign id given for a kind it carries")))))))
          (let [scopes (select-keys foreign (keys (get-in part [:perms :description :scopes])))
                walls  (wall-check (:perms part) scopes {:person person})
                mays   (may-check (:perms part) scopes {:person person})]
            {:checked [] :skipped {}
             :leaked  (vec (concat (for [w walls :when (seq (:shown w))] {:wall (dissoc w :hidden)})
                                   (for [m mays :when (seq (:shown m))] {:may (dissoc m :hidden)})))
             :hidden  (vec (concat (for [w walls :when (seq (:hidden w))] (dissoc w :shown))
                                   (for [m mays :when (seq (:hidden m))] (dissoc m :shown))))})
          (sort-by key (:endpoints part))))
