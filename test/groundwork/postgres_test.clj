(ns groundwork.postgres-test
  "Runs against a real Postgres: the local database groundwork_test, or
   GROUNDWORK_TEST_HOST / _PORT / _DB / _USER / _PASSWORD."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.names :as names]
   [groundwork.postgres :as pg]
   [groundwork.test-support :refer [sql!]])
  (:import
   (java.time OffsetDateTime)
   (java.util UUID)))

(defn- env [k default] (or (System/getenv k) default))

(def config
  {:host     (env "GROUNDWORK_TEST_HOST" "127.0.0.1")
   :port     (parse-long (env "GROUNDWORK_TEST_PORT" "5432"))
   :database (env "GROUNDWORK_TEST_DB" "groundwork_test")
   :user     (env "GROUNDWORK_TEST_USER" "groundwork")
   :password (env "GROUNDWORK_TEST_PASSWORD" nil)
   :pool-size 2
   :statement-timeout-ms 5000
   :time-zone "UTC"})

(def schema
  "DROP TABLE IF EXISTS posts, authors;
   CREATE TABLE authors (
     author__id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
     author__name text NOT NULL UNIQUE);
   CREATE TABLE posts (
     post__id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
     post__author_id  uuid NOT NULL REFERENCES authors,
     post__title      text NOT NULL,
     post__published  boolean NOT NULL DEFAULT false,
     post__created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
     post__meta       jsonb,
     post__tags       text[]);")

(def ^:dynamic *db* nil)
(def ^:private queries (atom []))

(use-fixtures :each
  (fn [t]
    (sql! schema)
    (reset! queries [])
    (let [db (pg/start (assoc config :on-query #(swap! queries conj %)))]
      (try
        (binding [*db* db] (t))
        (finally (pg/stop db))))))

(pg/defsql insert-author)
(pg/defsql insert-post)
(pg/defsql list-recent)
(pg/defsql get-post)
(pg/defsql posts-by-title)
(pg/defsql publish)
(pg/defsql count-posts)
(pg/defsql unnamed-column)
(pg/defsql author-with-posts)
(pg/defsql set-user)
(pg/defsql current-user-setting)
(pg/defsql author-by-name)
(pg/defsql many-rows)
(pg/defsql sleep)
(pg/defsql timeout-setting)
(pg/defsql time-zone-setting)
(pg/defsql local-time-as-timestamptz)
(pg/defsql posts-by-ids)
(pg/defsql count-by-ids)
(pg/defsql get-tags)
(pg/defsql set-tags)
(pg/defsql echo)

(defn- author! [name]
  (pg/row *db* insert-author {:author/name name}))

(defn- post! [author title & [meta]]
  (pg/row *db* insert-post {:post/author-id (:author/id author) :post/title title :post/meta meta}))

(deftest defsql
  (testing "the file's leading comment is the docstring"
    (is (= "Newest published posts with their author." (:doc (meta #'list-recent)))))
  (testing "a query is a value pointing at its file"
    (is (= "#groundwork/sql \"groundwork/list_recent.sql\"" (pr-str list-recent))))
  (testing "a missing file fails when the namespace loads"
    (let [e (try (binding [*ns* (the-ns 'groundwork.postgres-test)]
                   (eval '(groundwork.postgres/defsql no-such-query)))
                 (catch Exception e (ex-cause e)))]
      (is (= {:type ::pg/missing-file :path "groundwork/no_such_query.sql"} (ex-data e))))))

(deftest a-row-goes-straight-back-in
  (testing "a row from one query is the params map for the next"
    (let [post (post! (author! "Ana") "Hello")]
      (is (= 1 (pg/execute! *db* publish post))))))

(deftest rows-come-back-named-and-typed
  (let [ana  (author! "Ana")
        post (post! ana "Hello")]
    (is (= #{:author/id :author/name} (set (keys ana))))
    (is (instance? UUID (:author/id ana)))
    (is (instance? OffsetDateTime (:post/created-at post)))
    (is (= 1 (pg/execute! *db* publish post)))
    (is (= [{:post/id         (:post/id post)
             :post/title      "Hello"
             :post/created-at (:post/created-at post)
             :author/name     "Ana"}]
           (pg/rows *db* list-recent {:limit 10})))))

(deftest keys-are-made-per-query-not-per-row
  (let [calls  (atom 0)
        as-key names/as-key
        count-calls (fn [n]
                      (reset! calls 0)
                      (let [result (pg/rows *db* many-rows {:count n})]
                        (is (= n (count result)))
                        @calls))]
    (with-redefs [names/as-key (fn [s] (swap! calls inc) (as-key s))]
      (is (= (count-calls 1) (count-calls 10000)))
      (is (= {:row/n 3 :row/double 6} (nth (pg/rows *db* many-rows {:count 3}) 2))))))

(deftest unusual-columns
  (testing "count(*) is a plain keyword"
    (is (= {:count 0} (pg/row *db* count-posts))))
  (testing "a name outside the rules throws, naming the file and the column"
    (let [e (try (pg/row *db* unnamed-column) (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:type ::pg/bad-column :column "?column?" :file "groundwork/unnamed_column.sql"}
             (ex-data e)))
      (is (re-find #"AS entity__field" (ex-message e))))))

(deftest row-reads-at-most-two-rows
  (let [seen (atom nil)]
    (with-redefs-fn {#'pg/read-rows (let [read-rows @#'pg/read-rows]
                                      (fn [rs path] (reset! seen (read-rows rs path))))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than one row"
                             (pg/row *db* many-rows {:count 1000}))))
    (is (= 2 (count @seen)))))

(deftest row-and-execute
  (let [ana (author! "Ana")
        p   (post! ana "Same")]
    (is (nil? (pg/row *db* get-post {:post/id (UUID/randomUUID)})))
    (post! ana "Same")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than one row"
                          (pg/row *db* posts-by-title {:post/title "Same"})))
    (is (= 1 (pg/execute! *db* publish p)))
    (is (= 0 (pg/execute! *db* publish p)) "already published")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returns no rows, use execute!"
                          (pg/rows *db* publish p)))))

(deftest json
  (let [ana (author! "Ana")
        p   (post! ana "Hi" {:post/source "import" "Free Key" {:post/deep 1}})]
    (testing "entity__field keys become keywords at every depth; other keys stay strings"
      (is (= {:post/source "import" "Free Key" {:post/deep 1}}
             (:post/meta (pg/row *db* get-post p)))))
    (testing "nested json_build_object reads like columns"
      (is (= {:author/name  "Ana"
              :author/posts [{:post/id (str (:post/id p)) :post/title "Hi"}]}
             (pg/row *db* author-with-posts ana))))))

(deftest lists
  (let [ana (author! "Ana")
        a   (post! ana "A")
        b   (post! ana "B")
        ids (fn [rows] (mapv :post/id rows))]
    (testing "a list goes into ANY as the array Postgres expects"
      (is (= (ids [a b]) (ids (pg/rows *db* posts-by-ids {:post/ids [(:post/id a) (:post/id b)]}))))
      (is (= [] (pg/rows *db* posts-by-ids {:post/ids []})) "an empty list needs no cast")
      (is (= (ids [a]) (ids (pg/rows *db* posts-by-ids {:post/ids (map :post/id [a])}))) "a lazy seq")
      (is (= (ids [b]) (ids (pg/rows *db* posts-by-ids {:post/ids #{(:post/id b)}}))) "a set"))
    (testing "a list goes into jsonb as json"
      (let [p (post! ana "J" [1 "two" {:post/deep 3}])]
        (is (= [1 "two" {:post/deep 3}] (:post/meta (pg/row *db* get-post p))))))
    (testing "a row with an array column goes straight back in"
      (is (= 1 (pg/execute! *db* set-tags {:post/id (:post/id a) :post/tags ["x" "y"]})))
      (let [row (pg/row *db* get-tags a)]
        (is (= ["x" "y"] (:post/tags row)))
        (is (= 1 (pg/execute! *db* set-tags row)))
        (is (= row (pg/row *db* get-tags a)))))
    (testing "a list where Postgres can't tell the type throws, naming the param and file"
      (let [e (try (pg/row *db* echo {:x [1 2]}) (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:type ::pg/list-param :param :x :expects "text" :file "groundwork/echo.sql"}
               (ex-data e)))
        (is (re-find #"Cast it in the file, e.g. :x::int\[\]" (ex-message e)))))
    (testing "Postgres is asked once per file, and only when a list comes"
      (let [asked (atom 0)
            ask   @#'pg/ask-param-types]
        (with-redefs-fn {#'pg/ask-param-types (fn [conn sql] (swap! asked inc) (ask conn sql))}
          (fn []
            (pg/row *db* get-post a)
            (is (= 0 @asked) "no list, no question")
            (dotimes [_ 3]
              (is (= {:count 1} (pg/row *db* count-by-ids {:post/ids [(:post/id a)]}))))
            (is (= 1 @asked))))))))

(deftest params
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing param :limit for groundwork/list_recent.sql"
                        (pg/rows *db* list-recent {})))
  (is (= ["SELECT post__id FROM posts WHERE post__title = ?\n" ["x"]]
         (pg/statement posts-by-title {:post/title "x"}))))

(deftest errors
  (author! "Ana")
  (let [e (try (author! "Ana") (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::pg/unique-violation (:type (ex-data e))))
    (is (= "23505" (:sqlstate (ex-data e))))
    (is (= "authors_author__name_key" (:constraint (ex-data e))))
    (is (= "groundwork/insert_author.sql" (:file (ex-data e)))))
  (let [e (try (pg/row *db* insert-post {:post/author-id (UUID/randomUUID) :post/title "x" :post/meta nil})
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= ::pg/foreign-key-violation (:type (ex-data e))))))

(deftest transactions
  (testing "an exception rolls everything back"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pg/with-transaction [tx *db*]
                   (pg/row tx insert-author {:author/name "Bea"})
                   (pg/row tx insert-author {:author/name "Bea"}))))
    (is (nil? (pg/row *db* author-by-name {:author/name "Bea"}))))
  (testing "the body's value comes back"
    (is (= "Cy" (pg/with-transaction [tx *db*]
                  (:author/name (pg/row tx insert-author {:author/name "Cy"}))))))
  (testing "nested joins the outer one, which rolls both back"
    (is (thrown? RuntimeException
                 (pg/with-transaction [tx *db*]
                   (pg/with-transaction [inner tx]
                     (pg/row inner insert-author {:author/name "Dee"}))
                   (throw (RuntimeException. "boom")))))
    (is (nil? (pg/row *db* author-by-name {:author/name "Dee"}))))
  (testing "nested with different options throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must match"
                          (pg/with-transaction [tx *db*]
                            (pg/with-transaction [_ tx {:read-only? true}]))))))

(deftest on-transaction-hook
  (let [db (pg/start (assoc config :on-transaction #(pg/execute! % set-user {:user "ana"})))]
    (try
      (is (= {:setting/user "ana"}
             (pg/with-transaction [tx db] (pg/row tx current-user-setting))))
      (testing "outside a transaction the hook doesn't run"
        (is (contains? #{nil ""} (:setting/user (pg/row db current-user-setting)))))
      (finally (pg/stop db)))))

(deftest on-query-hook
  (author! "Ana")
  (try (author! "Ana") (catch Exception _))
  (let [[ok failed] @queries]
    (is (= "groundwork/insert_author.sql" (:file ok)))
    (is (number? (:ms ok)))
    (is (nil? (:error ok)))
    (is (= ::pg/unique-violation (:type (ex-data (:error failed)))))))

(deftest settings-are-required
  (testing "a missing setting throws, naming every one that is missing"
    (let [e (try (pg/start (dissoc config :pool-size :statement-timeout-ms))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:type ::pg/missing-settings :missing [:pool-size :statement-timeout-ms]}
             (ex-data e)))))
  (testing "a nil password is a choice, not a missing setting"
    (is (contains? config :password)))
  (testing "a timeout is a positive number of ms or :none"
    (doseq [bad [0 "30s" nil]]
      (is (= ::pg/bad-setting
             (:type (ex-data (try (pg/start (assoc config :statement-timeout-ms bad))
                                  (catch clojure.lang.ExceptionInfo e e)))))))))

(defn- with-db [settings f]
  (let [db (pg/start (merge config settings))]
    (try (f db) (finally (pg/stop db)))))

(deftest time-zone
  (testing "the session's zone is the setting, not the JVM's"
    (doseq [zone ["UTC" "America/New_York"]]
      (with-db {:time-zone zone}
        (fn [db]
          (is (= {:time-zone/value zone} (pg/row db time-zone-setting)))
          (testing "a time without an offset is read in that zone"
            (is (= (.toInstant (java.time.ZonedDateTime/of (java.time.LocalDateTime/of 2026 3 29 1 30)
                                                           (java.time.ZoneId/of zone)))
                   (.toInstant ^java.time.OffsetDateTime
                               (:moment/at (pg/row db local-time-as-timestamptz
                                                   {:at (java.time.LocalDateTime/of 2026 3 29 1 30)}))))))))))
  (testing "a name that can't be a zone throws at start"
    (is (= ::pg/bad-setting
           (:type (ex-data (try (pg/start (assoc config :time-zone "UTC -c x=1"))
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest statement-timeout
  (testing "Postgres cancels a statement that runs past the limit"
    (with-db {:statement-timeout-ms 100}
      (fn [db]
        (is (= {:statement-timeout/value "100ms"} (pg/row db timeout-setting)))
        (let [e (try (pg/row db sleep {:seconds 1}) (catch clojure.lang.ExceptionInfo e e))]
          (is (= ::pg/query-canceled (:type (ex-data e))))
          (is (= "groundwork/sleep.sql" (:file (ex-data e))))))))
  (testing "a transaction can get more time; the db's limit is back after it"
    (with-db {:pool-size 1 :statement-timeout-ms 100}
      (fn [db]
        (is (= 1 (pg/with-transaction [tx db {:statement-timeout-ms 5000}]
                   (is (= {:statement-timeout/value "5s"} (pg/row tx timeout-setting)))
                   (pg/execute! tx sleep {:seconds 0.3}))))
        (is (= {:statement-timeout/value "100ms"} (pg/row db timeout-setting))
            "the same connection, its one limit again")
        (is (= {:statement-timeout/value "0"}
               (pg/with-transaction [tx db {:statement-timeout-ms :none}]
                 (pg/row tx timeout-setting))))
        (is (= ::pg/bad-setting
               (:type (ex-data (try (pg/with-transaction [_ db {:statement-timeout-ms "10min"}])
                                    (catch clojure.lang.ExceptionInfo e e)))))))))
  (testing ":none is no limit, whatever the database role says"
    (with-db {:statement-timeout-ms :none}
      (fn [db]
        (is (= {:statement-timeout/value "0"} (pg/row db timeout-setting)))))))

(deftest a-changed-file-is-read-again
  (let [file  (io/file "test/groundwork/reload_me.sql")
        query (pg/->Query "groundwork/reload_me.sql")]
    (try
      (spit file "SELECT 1 AS n")
      (is (= {:n 1} (pg/row *db* query)))
      (spit file "SELECT 2 AS n")
      (.setLastModified file (+ (System/currentTimeMillis) 5000))
      (is (= {:n 2} (pg/row *db* query)))
      (finally (io/delete-file file true)))))
