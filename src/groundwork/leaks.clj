(ns groundwork.leaks
  "The built-in leak test, for an app's own tests: every endpoint with an
   action permission is called as a member of one scope, with ids from
   another, and must answer not-found. It catches what the declarations
   can't: a rule in the permission description that grants too much, a link
   whose condition forgot something.

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

   An open action (the description's :open) is everyone's, so its endpoints
   are called as a stranger: nobody for one open to anyone, a person who
   belongs nowhere for one open to the signed-in. They may answer; what
   they answer must hold none of the :sealed ids (give the ids no stranger
   may see: an unpublished row, a row of a kind the action doesn't reach).
   And the wall itself is asked, for every open action and every kind of
   record, what a stranger behind it sees of each foreign scope (open-wall):
   only the rows the action's rule allows, and nothing of any other kind.

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

(defn open-wall
  "What a stranger sees behind the wall, for every open action in every
   scope given ({scope-kind scope-id}), of every kind of record: the rows
   that show and shouldn't, [{:action :kind :ids}]. A row should show only
   when its kind lists the action, the row is in that scope, and the kind's
   :only-when for the action holds."
  [perms scopes]
  (let [description (:description perms)
        arrows      (fn [{:keys [in]}] (if (keyword? (first in)) [in] in))]
    (vec
     (for [action (sort (rules/open-actions description))
           :let   [scope (get scopes (permissions/action-kind perms action))]
           :when  scope
           [kind record] (sort-by key (:records description))
           :let   [stranger (when (= :signed-in (rules/open-level description action)) (random-uuid))
                   seen     (apply set/union
                                   (for [about [nil kind]]
                                     (permissions/in-wall perms {:person stranger :scopes #{scope} :action action
                                                                 :kind about :read-only? true}
                                                          #(ids-seen % record nil []))))
                   allowed  (if (contains? (:open record) action)
                              (let [[c ps] (if-let [condition (get-in record [:only-when action])]
                                             (rules/condition-sql "r" condition)
                                             ["TRUE" []])
                                    mine   (str "(" (str/join
                                                     " OR " (for [arrow (arrows record)]
                                                              (str (rules/scope-sql "r" arrow) " = CAST(? AS uuid)")))
                                                ")")]
                                (permissions/as-system
                                 perms #(ids-seen % record (str mine " AND " c)
                                                  (into (vec (repeat (count (arrows record)) (str scope))) ps))))
                              #{})
                   extra    (set/difference seen allowed)]
           :when  (seq extra)]
       {:action action :kind kind :ids (vec (sort extra))}))))

(defn check
  "Call every endpoint with another scope's ids. Returns {:checked [names]
   :skipped {name reason} :leaked [{:endpoint :params :answer}]}. Open
   actions are called as a stranger and must show none of the :sealed ids;
   a row the wall shows a stranger that it shouldn't is in :leaked as
   {:open-wall {:action :kind :ids}}."
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
                      (let [answer (endpoints/call part {:endpoint k :params params :person person
                                                         :flags (or flags #{}) :request-id "leak-test"})]
                        (case (:status answer)
                          :not-found (update report :checked conj k)
                          ;; the generated params didn't reach the check: not a leak, not checked either
                          :invalid   (assoc-in report [:skipped k] (str "the generated params don't fit its input: "
                                                                        (pr-str (:errors answer))))
                          (update report :leaked conj {:endpoint k :params params :answer answer})))
                      (assoc-in report [:skipped k] "no foreign id given for a kind it carries")))))))
          {:checked [] :skipped {}
           :leaked  (mapv (fn [leak] {:open-wall leak})
                          (open-wall (:perms part)
                                     (select-keys foreign (keys (get-in part [:perms :description :scopes])))))}
          (sort-by key (:endpoints part))))
