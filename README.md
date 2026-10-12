# Groundwork

An opinionated Clojure framework for database-backed web apps. One name for
each piece of data, from the column to the browser. Groundwork makes the
decisions and solves the hard parts; an app adds its settings, tables,
queries, permissions, login and pages.

```
keywords → postgres → parquery → re-frame → reagent
```

| Part              | Namespace             | Status |
|-------------------|-----------------------|--------|
| Starting an app   | `groundwork.core`     | built  |
| Keywords          | `groundwork.names`    | built  |
| db                | `groundwork.postgres` | built on next.jdbc + PgJDBC + HikariCP |
| parquery          |                       | next   |
| parquery, browser |                       |        |
| server            | `groundwork.server`   | built on http-kit |
| router            | `groundwork.web`      | built on reitit |
| migrations        | `groundwork.migrations` | built |
| sessions          |                       |        |
| permissions       |                       |        |
| page shell        | `groundwork.page`     | built  |
| background jobs   |                       | later ([#1](https://github.com/p4ulcristian/groundwork/issues/1)) |

Java 25 or newer. Every decision and its reason: [`decisions/`](decisions/).

---

## Starting an app

```clojure
(def app (gw/start {:environment :development
                    :db          {…}                          ; see Postgres, Start
                    :migrations  {:schema-snapshot "schema.sql"}
                    :page        {:lang "en" :title "Acme Coating" :icon "/icon.svg"
                                  :stylesheets ["/app.css"] :script "/js/main.js" :head []}
                    :routes      [["/health" {:get (fn [_] {:status 200 :body "ok"})}]]
                    :server      {:ip "0.0.0.0" :port 8080}}))
(gw/stop app)
```

- One key per part, every key required; an unknown key throws.
- Inside, Integrant starts the parts in order and stops them in reverse.
  That wiring is Groundwork's own: settings don't name or replace parts.
- Tests can replace a part: `(gw/start-with settings {:groundwork/db fake})`.

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
                   :statement-timeout-ms 30000    ; or :none
                   :time-zone            "UTC"}))
(pg/stop db)
```

- Every setting is required. No defaults: the config says it all.
- `:pool-size` 10 suits one app server. With virtual threads, keep it small
  and let requests wait for a connection.
- `:statement-timeout-ms` 30000 is the recommendation: Postgres cancels a
  statement that never finishes, before a proxy gives up on the request.
  Find slow queries with `:on-query`'s `:ms`, not with this.
- `:time-zone` is the session's zone, whatever machine the app runs on. It
  decides how a time without an offset is read.

### In the REPL

```clojure
(pg/rows db list-recent {:limit 20})   ;=> [{:post/id … :post/title … :author/name …} …]
(pg/row db get-post {:post/id id})     ;=> one map or nil; more than one throws
(pg/execute! db publish post)          ;=> rows touched; a row is a params map
(pg/statement list-recent {:limit 20}) ;=> ["SELECT … LIMIT $1" [20]]
```

- A missing param throws. `nil` means `NULL`.
- A map goes in as jsonb. `(pg/json x)` makes anything jsonb.
- Values come back as real types: UUID, `java.time`, `BigDecimal`.
- Every result column needs a name: `count(*)` is `:count`, but an unnamed
  expression (`?column?`) throws, naming the file. Write `… AS post__total`.
- Column names become keywords once per query, not once per row.
- jsonb keys shaped `entity__field` become `:entity/field`; other keys stay strings.

### Lists

```sql
-- posts_by_ids.sql
SELECT post__id, post__title FROM posts WHERE post__id = ANY(:post/ids)
```

```clojure
(pg/rows db posts-by-ids {:post/ids [id1 id2]})   ; a vector, seq or set
```

- A list goes in as the type Postgres expects in that place: a SQL array
  (`uuid[]`, `text[]`…) or json.
- An empty list works, no cast needed.
- Postgres is asked once per file, the first time it gets a list.
- With nothing around it to tell its type (`SELECT :x`), cast it: `:x::int[]`.

### Transactions

```clojure
(pg/with-transaction [tx db]
  (let [post (pg/row tx insert-post {:post/title "Hi" :post/author-id aid})]
    (pg/execute! tx tag-post {:post-tag/post-id (:post/id post) :post-tag/tag-id t})
    post))
```

- Any exception rolls back and rethrows.
- A nested `with-transaction` joins the outer one.

Work that is slow on purpose gets its own limit, until the transaction ends:

```clojure
(pg/with-transaction [tx db {:statement-timeout-ms 600000}]   ; or :none
  (pg/rows tx yearly-report {:year 2026}))
```

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

## 3. Migrations

```
resources/migrations/
  001_create_shops.sql
  002_add_shop_country.sql
```

- Numbered plain SQL files. At start, under a lock in Postgres, Groundwork
  runs its own new migrations, then the app's, in number order.
- Each file runs once, in its own transaction, with no time limit, and is
  remembered in `groundwork.migrations` with its fingerprint.
- A failing file rolls back and the app doesn't start. A file that ran and
  then changed or disappeared stops the start: a fix is a new file.
- In development the whole schema is written to `:schema-snapshot`
  (`nil` for none), when `pg_dump` is on the PATH.
- Groundwork's tables live in the `groundwork` schema; every connection's
  search path is only `public`, so the two never mix.

---

## 4. Server, routes and the page

- **Server:** http-kit; every request runs on its own virtual thread.
- **Routes:** the app's `:routes` are reitit data, next to Groundwork's own.
- **Files:** from `resources/public`, revalidated on every load (304 when
  unchanged), so a deploy is never hidden behind a stale cache.
- **The page:** any other page a browser asks for gets the page shell, and
  the app's routing in the browser takes over. It loads `:script` into
  `<div id="app">`, and is never cached.
- **Security headers** on every answer: no sniffing, no framing, a strict
  referrer; in production also Strict-Transport-Security.
