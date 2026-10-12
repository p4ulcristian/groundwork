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
   the foreign one of that kind. An endpoint is skipped when no foreign id
   of a kind it needs was given, or its shape can't be generated; public
   and signed-in endpoints carry no ids and are skipped too. Writes are
   called like reads: they're refused before they run, or they leak.

   A list carries no ids: it's called as it is, and its answer must hold
   none of the foreign ids, anywhere in it.

   Generating needs org.clojure/test.check on the test classpath."
  (:require
   [clojure.walk :as walk]
   [groundwork.endpoints :as endpoints]
   [groundwork.parquery.wire :as wire]))

(defn- generate [shape]
  (require 'malli.experimental.time.generator)
  ((requiring-resolve 'malli.generator/generate) (wire/schema shape) {:seed 42 :size 3}))

(defn- foreign-ids
  "The params with every id the endpoint carries set to the foreign one of
   its kind; nil when a kind has no foreign id."
  [{:keys [permission ids]} params foreign]
  (let [{:keys [record scope]} permission
        [own-kind own-param]   (or record scope)
        needed                 (cond-> (set (vals ids)) own-kind (conj own-kind))]
    (when (every? #(contains? foreign %) needed)
      (let [params (cond-> params own-param (assoc own-param (foreign own-kind)))]
        (walk/postwalk (fn [x]
                         (if (map? x)
                           (reduce-kv (fn [m k kind]
                                        (if (contains? m k)
                                          (assoc m k (if (coll? (get m k)) [(foreign kind)] (foreign kind)))
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

(defn check
  "Call every endpoint with another scope's ids. Returns {:checked [names]
   :skipped {name reason} :leaked [{:endpoint :params :answer}]}."
  [part {:keys [person flags foreign]}]
  (reduce (fn [report [k entry]]
            (let [permission (:permission entry)]
              (cond
                (#{:public :signed-in} permission)
                (assoc-in report [:skipped k] "carries no ids")

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
                    (if-let [params (foreign-ids entry generated foreign)]
                      (let [answer (endpoints/call part {:endpoint k :params params :person person
                                                         :flags (or flags #{}) :request-id "leak-test"})]
                        (if (= :not-found (:status answer))
                          (update report :checked conj k)
                          (update report :leaked conj {:endpoint k :params params :answer answer})))
                      (assoc-in report [:skipped k] "no foreign id given for a kind it carries")))))))
          {:checked [] :skipped {} :leaked []}
          (sort-by key (:endpoints part))))
