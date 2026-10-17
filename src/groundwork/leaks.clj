(ns groundwork.leaks
  "The built-in leak test, for an app's own tests: every endpoint with an
   action permission is called as a member of one scope, with ids from
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
   id it carries (its permission's own, and every marked field) is set to
   the foreign one of that kind, as text (the way a JSON ask brings ids).
   An endpoint is skipped when no foreign id of a kind it needs was given,
   its shape can't be generated, or what was generated doesn't fit it; public
   and signed-in endpoints carry no ids and are skipped too. Writes are
   called like reads: they're refused before they run, or they leak.

   A list carries no ids: it's called as it is, and its answer must hold
   none of the foreign ids, anywhere in it.

   An action a sentence opens to :anyone or :signed-in is everyone's, so its
   endpoints are called as a stranger: nobody for one open to anyone, a
   person who belongs nowhere for one open to the signed-in. They may
   answer; what they answer must hold none of the :sealed ids (give the ids
   no stranger may see: an unpublished row, a row of a kind no open sentence
   reaches).

   The wall itself is asked too (wall-check): for every kind of record and
   every foreign scope, what nobody, a signed-in stranger and the person see
   behind it must be exactly the rows the sentences give them there.

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
   [groundwork.parquery.wire :as wire]
   [groundwork.permissions :as permissions]
   [groundwork.permissions.rules :as rules]
   [groundwork.postgres :as pg]
   [next.jdbc :as jdbc]))

(defn- generate [shape]
  (require 'malli.experimental.time.generator)
  ((requiring-resolve 'malli.generator/generate) (wire/schema shape) {:seed 42 :size 3}))

(defn- foreign-ids
  "The params with every id the endpoint carries set to the foreign one of
   its kind; nil when a kind has no foreign id."
  [{:keys [permission ids]} params foreign scope-kinds]
  (let [{:keys [record scope]} permission
        [own-kind own-param]   (or record scope)
        ;; held in any kind of scope (the team endpoints): any foreign scope
        own-kind               (if (= :groundwork/any own-kind)
                                 (some #(when (contains? foreign %) %) scope-kinds)
                                 own-kind)
        needed                 (cond-> (set (vals ids)) own-kind (conj own-kind))]
    (when (every? #(contains? foreign %) needed)
      ;; ids go in as text, the way a JSON ask brings them; a fixed scope
      ;; (an id in the permission itself) has no param to set
      (let [text   #(str (foreign %))
            params (cond-> params (keyword? own-param) (assoc own-param (text own-kind)))]
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
                       (into [(str "SELECT r." (rules/quote-column id) "::text AS id FROM " (rules/quote-table t) " r"
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
                                       (contains? (rules/commands-of description (:action %)) :read))
                                 (:sentences perms))
                 seen    (permissions/in-wall perms {:person caller :scopes #{scope} :read-only? true}
                                              #(ids-seen % record nil []))
                 cols    (keys (rules/scope-columns record))
                 parts   (for [s ss]
                           (let [[w ps] (rules/sentence-sql s "r")
                                 base   (when-not (= :me (get-in s [:who :word]))
                                          (when (seq cols)
                                            (str "((" (str/join " OR " (map #(str "r." (rules/quote-column %) " = CAST(? AS uuid)") cols)) ")"
                                                 (when (:universal? s)
                                                   (str " OR (" (str/join " AND " (map #(str "r." (rules/quote-column %) " IS NULL") cols)) ")"))
                                                 ")")))]
                             [(str "(" (when base (str base " AND ")) w ")")
                              (into (if base (vec (repeat (count cols) (str scope))) []) (rules/bind ps caller))]))
                 given   (if (seq parts)
                           (permissions/as-system perms #(ids-seen % record (str/join " OR " (map first parts))
                                                                   (vec (mapcat second parts))))
                           #{})
                 shown   (set/difference seen given)
                 hidden  (set/difference given seen)]
           :when (or (seq shown) (seq hidden))]
       {:kind kind :as as :scope scope :shown (vec (sort shown)) :hidden (vec (sort hidden))}))))

(defn check
  "Call every endpoint with another scope's ids. Returns {:checked [names]
   :skipped {name reason} :leaked [{:endpoint :params :answer}] :hidden […]}.
   Open actions are called as a stranger and must show none of the :sealed
   ids. A row the wall shows that no sentence gives is in :leaked as {:wall
   {:kind :as :scope :shown}}; a row a sentence gives that the wall hides is
   in :hidden."
  [part {:keys [person flags foreign sealed]}]
  (reduce (fn [report [k entry]]
            (let [permission (:permission entry)
                  open       (when (map? permission) (permissions/open-level (:perms part) (:action permission)))]
              (cond
                (#{:public :signed-in} permission)
                (assoc-in report [:skipped k] "carries no ids")

                open
                (let [generated (try (generate (:input entry)) (catch Exception e e))
                      params    (when-not (instance? Exception generated)
                                  (foreign-ids entry generated foreign (keys (get-in part [:perms :description :scopes]))))]
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
                        :else
                        (update report :leaked conj {:endpoint k :params params :answer answer})))))

                (and (:list permission) (empty? (:ids entry)))
                (let [generated (try (generate (:input entry)) (catch Exception e e))]
                  (if (instance? Exception generated)
                    (assoc-in report [:skipped k] (str "its input can't be generated: " (ex-message generated)))
                    (let [answer (endpoints/call part {:endpoint k :params generated :person person
                                                       :flags (or flags #{}) :request-id "leak-test"})]
                      (if (and (= :ok (:status answer)) (holds-any? (:data answer) (set (vals foreign))))
                        (update report :leaked conj {:endpoint k :params generated :answer answer})
                        (update report :checked conj k)))))

                :else
                (let [generated (try (generate (:input entry)) (catch Exception e e))]
                  (if (instance? Exception generated)
                    (assoc-in report [:skipped k] (str "its input can't be generated: " (ex-message generated)))
                    (if-let [params (foreign-ids entry generated foreign (keys (get-in part [:perms :description :scopes])))]
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
          (let [walls (wall-check (:perms part)
                                  (select-keys foreign (keys (get-in part [:perms :description :scopes])))
                                  {:person person})]
            {:checked [] :skipped {}
             :leaked  (vec (for [w walls :when (seq (:shown w))] {:wall (dissoc w :hidden)}))
             :hidden  (vec (for [w walls :when (seq (:hidden w))] (dissoc w :shown)))})
          (sort-by key (:endpoints part))))
