# Groundwork

A Clojure library for database-backed web apps. One name for each piece of
data, from the column to the browser.

```
keywords → postgres → parquery → re-frame → reagent
```

| Part     | Namespace             | Status |
|----------|-----------------------|--------|
| Keywords | `groundwork.names`    | built  |
| Postgres | `groundwork.postgres` | built on next.jdbc + PgJDBC + HikariCP |
| Parquery |                       | next   |
| re-frame |                       |        |
| Reagent  |                       |        |

Every decision and its reason: [`decisions/`](decisions/).

---

## 1. Keywords

Columns are named `entity__field` in the database itself.
In Clojure the same column is `:entity/field`.

```
:post/title        ↔  post__title
:post/created-at   ↔  post__created_at
:post-tag/post-id  ↔  post_tag__post_id
```

```clojure
(names/key->column :post/created-at)   ;=> "post__created_at"
(names/column->key "post__created_at") ;=> :post/created-at
(names/as-key "?column?")              ;=> nil, the lenient version
```

- Lowercase, single hyphens, no underscores, no dots. Anything else throws.
- Over 63 characters throws (Postgres would cut it silently).

---

## 2. Postgres

SQL lives in files. Nowhere else.

```
src/app/posts/
  db.clj
  list_recent.sql
  publish.sql
```

```sql
-- list_recent.sql
-- Newest published posts with their author.
SELECT post__id, post__title, author__name
FROM posts
JOIN authors ON author__id = post__author_id
WHERE post__published
ORDER BY post__created_at DESC
LIMIT :limit
```

```sql
-- publish.sql
-- Publishes a draft. Does nothing if it is already published.
UPDATE posts
SET post__published = true
WHERE post__id = :post/id AND NOT post__published
```

```clojure
(ns app.posts.db
  (:require [groundwork.postgres :as pg]))

(pg/defsql list-recent)   ; list_recent.sql, next to this file
(pg/defsql publish)
```

- Columns are written as they are: `post__title`.
- A param has a colon. A field of the data is namespaced (`:post/id`);
  a control of the query is plain (`:limit`).
- The first comment in the file is the docstring.
- A missing file fails when the namespace loads.
- Save a `.sql` file and the next call uses it.

### Start

```clojure
(def db (pg/start {:host                 "localhost"
                   :port                 5432
                   :database             "app"
                   :user                 "app"
                   :password             nil      ; nil for none
                   :pool-size            10
                   :statement-timeout-ms 30000})) ; or :none
(pg/stop db)
```

- Every setting is required. No defaults: the config says it all.
- `:pool-size` 10 suits one app server. With virtual threads, keep it small
  and let requests wait for a connection.
- `:statement-timeout-ms` 30000 is the recommendation: Postgres cancels a
  statement that never finishes, before a proxy gives up on the request.
  Find slow queries with `:on-query`'s `:ms`, not with this.

### In the REPL

```clojure
(pg/rows db list-recent {:limit 20})   ;=> [{:post/id … :post/title … :author/name …} …]
(pg/row db get-post {:post/id id})     ;=> one map or nil; more than one throws
(pg/execute! db publish post)          ;=> rows touched; a row is a params map
(pg/statement list-recent {:limit 20}) ;=> ["SELECT … LIMIT $1" [20]]
```

- A missing param throws. `nil` means `NULL`.
- Values come back as real types: UUID, `java.time`, `BigDecimal`.
- Column names become keywords once per query, not once per row.
- jsonb keys shaped `entity__field` become `:entity/field`; other keys stay strings.

### Transactions

```clojure
(pg/with-transaction [tx db]
  (let [post (pg/row tx insert-post {:post/title "Hi" :post/author-id aid})]
    (pg/execute! tx tag-post {:post-tag/post-id (:post/id post) :post-tag/tag-id t})
    post))
```

- Any exception rolls back and rethrows.
- A nested `with-transaction` joins the outer one.

### Hooks

```clojure
(pg/start {… :on-transaction (fn [tx] …)
             :on-query       (fn [{:keys [file ms error]}] …)})
```

- `:on-transaction` runs after `BEGIN`, only inside `with-transaction`.
  Row-level security settings go here.
- `:on-query` runs after every query. Logs and metrics go here.

### Errors

`ex-info` with a `:type`: `unique-violation`, `foreign-key-violation`,
`check-violation`, `not-null-violation`, `serialization-failure`,
`deadlock`, `query-canceled` (past the time limit), else `error`, all under
`:groundwork.postgres/`.

---

## Open

- [ ] A vector as a param: SQL array or jsonb? (now: maps are jsonb, `(pg/json x)` makes anything jsonb)
- [ ] `row` with more than one row: throw (now) or first?
- [ ] Odd column names like `?column?`: string (now) or throw?
- [ ] Lowest Java version
- [ ] How one long query gets more time than `:statement-timeout-ms`
