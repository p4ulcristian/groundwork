(ns groundwork.names-test
  (:require
   [clojure.test :refer [deftest is are]]
   [groundwork.names :as names]))

(deftest both-directions
  (are [k column] (and (= column (names/key->column k))
                       (= k (names/column->key column)))
    :post/title          "post__title"
    :post/created-at     "post__created_at"
    :post-tag/post-id    "post_tag__post_id"
    :count               "count"
    :a1/b2-c3            "a1__b2_c3"))

(deftest invalid-names-throw
  (are [k] (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a valid name" (names/key->column k))
    :Post/title
    :post/title_x
    :post/created--at
    :post.tag/id
    :post/-x
    "post/title")
  (are [s] (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a valid column" (names/column->key s))
    "?column?"
    "Post__title"
    "post___title"
    "post__"
    "__title"
    "post__title__x"
    "post-title"))

(deftest too-long-throws
  (let [k (keyword "a" (apply str (repeat 61 "b")))]
    (is (= 64 (count (str "a__" (name k)))))
    (is (thrown? clojure.lang.ExceptionInfo (names/key->column k)))
    (is (nil? (names/as-column k)))))

(deftest lenient-versions-return-nil
  (is (nil? (names/as-key "?column?")))
  (is (nil? (names/as-key nil)))
  (is (nil? (names/as-column :Bad)))
  (is (= :post/id (names/as-key "post__id"))))

(deftest class-keywords-and-class-names
  (are [k class-name] (and (= class-name (names/key->class k))
                           (= k (names/class->key class-name)))
    :css.card/frame            "card__frame"
    :css.job-card/title        "job-card__title"
    :css.job-card/group-color  "job-card__group-color"
    :css.a1/b2                 "a1__b2")
  (is (= "job-card" (names/class-namespace "job-card__group-color")))
  (is (names/class-key? :css.card/frame))
  (is (not (names/class-key? :card/frame)) "data, an endpoint, an event: no marker, no class")
  (doseq [k [:job-card/title :css.Job/title :css.job_card/title :css.job-card/Title :css.job--card/title
             :css.a.b/c :css/title]]
    (is (nil? (names/as-class k)) (str k))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a class keyword" (names/key->class k)) (str k)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a class name" (names/class->key "vc-day")))
  (are [s] (and (nil? (names/as-class-key s)) (nil? (names/class-namespace s)))
    "jobcard"
    "job-card__"
    "__title"
    "Job__title"
    "a__b__c"
    "job_card__title"
    "vc-day"))
