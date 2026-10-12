(ns groundwork.sql-test
  (:require
   [clojure.test :refer [deftest is are testing]]
   [groundwork.sql :as sql]))

(deftest params-become-positions
  (is (= {:sql "SELECT post__id FROM posts WHERE post__author_id = ? LIMIT ?"
          :params [:author-id :limit]}
         (sql/parse "SELECT post__id FROM posts WHERE post__author_id = :author-id LIMIT :limit")))
  (testing "a name used twice is two ?s"
    (is (= {:sql "WHERE a = ? OR b = ? AND c = ?" :params [:x :x :y]}
           (sql/parse "WHERE a = :x OR b = :x AND c = :y"))))
  (testing "a field of the data is namespaced"
    (is (= {:sql "INSERT INTO posts (post__title) VALUES (?)" :params [:post/title]}
           (sql/parse "INSERT INTO posts (post__title) VALUES (:post/title)"))))
  (testing "no space needed"
    (is (= {:sql "WHERE a=?" :params [:x]} (sql/parse "WHERE a=:x")))))

(deftest left-untouched
  (are [text] (= {:sql text :params []} (sql/parse text))
    "SELECT ':not-a-param'"
    "SELECT 'it''s :fine'"
    "SELECT E'\\' :still-text'"
    "SELECT \":odd name\" FROM t"
    "SELECT 1 -- :comment\n"
    "SELECT /* :a /* :nested */ :b */ 1"
    "SELECT $$ :body $$"
    "SELECT $fn$ :body $$ :x $fn$"
    "SELECT x::text, y::jsonb"
    "SELECT arr[1:n], arr[lo:hi]"
    "SELECT a$b"))

(deftest after-a-comment-params-work-again
  (is (= {:sql "SELECT 1 -- :no\nWHERE a = ?" :params [:yes]}
         (sql/parse "SELECT 1 -- :no\nWHERE a = :yes"))))

(deftest param-names
  (is (= [:post-id] (:params (sql/parse "= :post-id"))))
  (testing "a double hyphen starts a comment, not a longer name"
    (is (= {:sql "= ?--x" :params [:a]} (sql/parse "= :a--x")))))

(deftest a-question-mark-operator-is-escaped
  (is (= {:sql "WHERE post__meta ?? 'k' AND post__meta ??| ? AND x = '?'" :params [:keys]}
         (sql/parse "WHERE post__meta ? 'k' AND post__meta ?| :keys AND x = '?'"))))

(deftest positional-params-throw
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Positional"
                        (sql/parse "WHERE a = $1"))))

(deftest bind
  (let [stmt (sql/parse "WHERE a = :x AND b = :y")]
    (is (= [1 nil] (sql/bind stmt {:x 1 :y nil :extra 3} "f.sql")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing param :y for f.sql"
                          (sql/bind stmt {:x 1} "f.sql")))))
