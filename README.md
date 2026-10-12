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
| sessions          | `groundwork.sessions` | built  |
| permissions       | `groundwork.permissions` | built (endpoints wait for parquery) |
| page shell        | `groundwork.page`     | built  |
| background jobs   |                       | later ([#1](https://github.com/p4ulcristian/groundwork/issues/1)) |

Java 25 or newer. Every decision and its reason: [`decisions/`](decisions/).

---

## Starting an app

```clojure
(def app (gw/start {:environment :development
                    :db          {…}                          ; see Postgres, Start
                    :migrations  {:schema-snapshot "schema.sql"}
                    :page        {:lang "en" :title "Acme Coating" :description nil
                                  :icon "/icon.svg" :stylesheets ["/app.css"]
                                  :script "/js/main.js" :head []}
                    :web         {:routes                  [["/orders" {:get web/page :access :signed-in}]]
                                  :max-body-bytes          1048576
                                  :content-security-policy {}
                                  :login-path              "/login"
                                  :on-request              log-request   ; or nil
                                  :on-error                report-error  ; or nil
                                  :proxies                 {:trusted ["10.0.0.0/8"]
                                                            :client-ip-header "cf-connecting-ip"
                                                            :request-id-header "x-request-id"}
                                  :languages               {:supported ["en" "de"] :default "en"
                                                            :cookie "lang"}    ; or nil
                                  :test-sign-in            nil}               ; or {:secret …}, never in production
                    :server      {:ip "0.0.0.0" :port 8080}
                    :sessions    {:cookie-name "acme_session" :max-age-days 30 :visitor-hours 24
                                  :secure? true :origin "https://app.acme.example"}
                    :permissions {…}}))   ; see Permissions
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
- **Routes:** the app's `:web :routes` are reitit data, next to Groundwork's own.
- **Every route declares who may use it,** or the app doesn't start:

```clojure
["/pricing"          {:get web/page :access :public}]
["/orders"           {:get web/page :access :signed-in}]   ; signed out: a page goes to :login-path?next=…, else 401
["/orders/:order-id/photos"
                     {:post upload-photo
                      :access {:action :orders/edit :record [:order :order-id]}}]   ; not allowed: 404
["/payments/webhook" {:post handle-webhook :server-to-server true}]   ; checks the call itself
```

  A permission names the action and the record whose id is the path
  parameter (or form or query parameter); it's checked like an endpoint's,
  through the record's own scope. A record that isn't the person's answers
  404, the same as one that isn't there.
- **Hooks** for logs and metrics: `:on-request` hears every answer
  (`{:method :route :status :ms :person :flags :request-id :client-ip}`,
  route as its template, like `"/orders/:order-id"`); `:on-error` hears an
  exception no handler caught, with the same context, while the browser
  gets a plain 500. A hook that throws is logged and never breaks an answer.
- **Request ids and the visitor's address:** every answer carries
  `X-Request-Id` (`web/request-id`). Only a request from a `:proxies
  :trusted` address or range is believed about the visitor's address
  (`:client-ip-header`, its first entry) and its id; otherwise the
  connection's address and a new id (`web/client-ip`).
- **Languages** (`:languages`, or nil for one): a `/de/…` prefix is taken
  off before routing; the request's language (`web/language`) is the
  address's, then the cookie's (when named), then the browser's
  Accept-Language, then the default. The page shell's `lang` follows it, and
  its alternate links name the same page in every language (the default
  without a prefix, plus x-default), unless the route gives its own.
- **Test sign-in** for automated tests: with `:test-sign-in {:secret …}`
  (16+ characters; set in production, the app refuses to start), `POST
  /groundwork/test-sign-in?person=<uuid>&flags=impersonated&hours=1` with
  `X-Test-Secret` answers the session cookie. A wrong or missing secret is
  404.
- **Request bodies:** none larger than `:max-body-bytes`, or a route's own
  (`["/uploads" {:post upload :access :signed-in :max-body-bytes 104857600}]`); larger is 413,
  and the server never reads more than the largest any route accepts. A
  browser route's form is parsed into `:form-params`, multipart into
  `:multipart-params`: each file a temporary file (deleted after an hour)
  with `:real-type`, what its bytes really are (`web/sniff-type`), whatever
  name or type the browser gave. JSON and every server-to-server route's
  body stay untouched, so a webhook can check its signature. Storing files is
  the app's.
- **Files:** from `resources/public`, revalidated on every load (304 when
  unchanged), so a deploy is never hidden behind a stale cache. They answer
  byte ranges (206), so video plays and seeks everywhere, Safari included.
- **HEAD** is answered for every GET route, like GET without the body.
- **Outside production** `/robots.txt` disallows everything and every
  answer carries `X-Robots-Tag: noindex`, so a staging copy never shows up
  in search. In production the app's own `robots.txt` is served.
- **The page:** any other page a browser asks for gets the page shell, and
  the app's routing in the browser takes over. It loads `:script` into
  `<div id="app">`, and is never cached.
- **A page per route:** a route's `:page`, a map or a function of the
  request, is merged over the app's: its own `:lang :title :description`,
  `:canonical`, `:alternates` (hreflang links), extra `:stylesheets
  :scripts :head`, and `:body`, HTML the server writes inside
  `<div id="app">` for search engines and the first paint.

```clojure
["/coaters/:slug" {:get    web/page
                   :access :public
                   :page (fn [request] {:title (str "Coater " …) :body (render-coater …)})}]
```

- **Security headers** on every answer: no sniffing, no framing, a strict
  referrer; in production also Strict-Transport-Security.
- **A strict Content-Security-Policy:** scripts and styles only from the
  app's own origin or carrying the request's nonce, which the page shell
  stamps on every `<script>` and `<style>` it writes (the app's `:head` and
  `:body` included; `web/nonce` for a handler writing its own HTML). Style
  attributes stay allowed, since components set them. The app adds sources
  per directive: `:content-security-policy {:script-src ["https://js.payments.example"]
  :frame-src […] :connect-src […]}`. Development also allows eval, which its
  build needs.

```clojure
["/customize"        {:get web/page :access :public :embeddable true}]                     ; any https site may frame it
["/partner-widget"   {:get web/page :access :public :embeddable ["https://shop.example"]}] ; only these sites
["/label-editor"     {:get web/page :access :signed-in :embeddable :same-origin}]       ; only the app's own pages
["/payments/webhook" {:post handle-webhook :server-to-server true}]
```

- **Embeddable pages:** a route with `:embeddable` may be shown in other
  sites' frames, any https site or only the listed ones. Every other answer
  stays unframeable. `web/page` answers a route with the page shell.
- **Server-to-server routes** (a payment provider's webhook): no session is
  read or made and the Origin check doesn't apply; the handler checks the
  call itself, like its signature. Every other route is a browser route.

---

## 5. Sessions

```clojure
;; the app's login step, after the identity provider said who this is
(sessions/sign-in request response person-id)
(sessions/sign-in request response person-id {:flags #{:impersonated} :data {:login/via "sso"}
                                              :hours 1})   ; its own lifetime

(sessions/person request)    ;=> the person's id, or nil (also for a visitor)
(sessions/flags request)     ;=> #{:impersonated}
(sessions/data request)      ;=> {:cart/items […] :login/via "sso"}
(sessions/put-data request response {:cart/items items})   ; nil forgets a key
(sessions/put-data request response {:design/ids ids} {:keep-days 30})   ; a visitor's, kept longer
(sessions/sign-out request response)
(sessions/sign-out-everywhere! request person-id)
```

- A session is a row in `groundwork.sessions`. The cookie holds 32 random
  bytes; the table keeps only their SHA-256.
- The cookie is HttpOnly and SameSite=Lax, Secure when `:secure?`.
- Signing in ends the session the request already had.
- **Visitors** (not signed in) get a session the first time the app keeps
  something with `put-data`, like a cart; reading makes nothing, so crawlers
  cost no rows. It ends after `:visitor-hours` unseen (24 is a good start);
  each visit pushes the end out, at most once an hour. Once it holds
  something worth keeping (uploaded designs), `{:keep-days 30}` makes it
  live that long from each visit, its cookie too.
- A sign-in lives `:max-age-days`, or its own `:hours` (an impersonated
  session, an hour). At sign-in its data
  moves into the signed-in session, under a new key.
- **In another site's frame** the browser doesn't send the Lax cookie. A
  visitor session made on an `:embeddable` route uses a second cookie,
  `<cookie-name>_embed` (SameSite=None; Secure; Partitioned), which the
  framed page's own requests carry. It only ever holds a visitor: sign-in in
  a frame is refused, so signing in and checkout open on the app's own site,
  where the visitor's data moves into the signed-in session.
- A request that changes something (not GET, HEAD, OPTIONS) must carry the
  app's own `:origin`, or it's refused with 403.
- `:data` keys are shaped `:entity/field`, like every json key.

---

## 6. Permissions

The app describes its places, actions, roles and records once:

```clojure
{:scopes  {:shop    {:actions #{:orders/see :orders/edit :jobs/move}
                     :roles   {"Manager"    #{:orders/see :orders/edit :jobs/move :groundwork/team}
                               "Production" #{:orders/see :jobs/move}}
                     :parent  :company}
           :company {:actions #{:company/report} :roles {} :parent nil}}
 :records {:order {:table :orders :id :order/id :in [:shop :order/shop-id]
                   :people {:orders/see :order/customer-id}           ; optional
                   :only-when {:orders/edit [:= :order/status "open"]}} ; optional
           :job   {:table :jobs :id :job/id :in [:order :job/order-id]}}
 :links   [{:table :agreements :from [:partner :agreement/partner-id] :to [:shop :agreement/shop-id]
            :when [:= :agreement/status "active"] :grants {:orders/see :agreements/see}}]
 :session-blocks {:impersonated #{:money/move}}}
```

```clojure
(perm/allowed? perms {:person p :flags #{} :action :jobs/move :record [:job id]})
(perm/check! perms {… :also {:machine [machine-id]}})   ; every id; else not-found
(perm/list-filter perms {:person p :flags #{} :action :orders/see :kind :order})
(perm/in-wall perms {:person p :scopes #{shop-id}} (fn [tx] …))
(perm/as-system perms (fn [tx] …))
```

- Groundwork keeps scopes, roles, role actions, memberships, extra actions
  and invitations in the `groundwork` schema. Changes count on the next
  check; nothing is cached.
- A record reaches its scope through its own columns (job → order → shop),
  never a scope the browser names. Another scope's record is not-found.
- A membership in a parent scope counts in its children; an owner holds
  everything; a link gives access while its condition holds; a person on
  the record, a condition on its own columns and session flags are part of
  the rule.
- Each rule is one SQL condition: it checks one record and filters a list.
- The description is checked at start, against the database too.
- **The wall:** every record table gets forced row-level security at start.
  A transaction sees only its verified scopes and the person's own records,
  or everything as the system. Both last one transaction and can't nest.
- **Team:** `create-scope!`, `invite!`, `accept-invitation!`,
  `change-role!`, `create-role!`, `set-role-actions!`, `remove-member!`,
  `grant-extra!`, `revoke-extra!`. Managing needs `:groundwork/team`, and
  only actions the manager holds can be given; only an owner makes owners;
  a scope always keeps one.
