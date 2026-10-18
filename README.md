# Groundwork

Groundwork is an opinionated Clojure framework for web apps on Postgres. It
divides an app's business from everything else. The app writes what is its
own: settings, tables and migrations, SQL files, endpoints, who may do what,
its login and its screens. Groundwork does the rest: the database pool and
transactions, the wall in Postgres that shows each request only its rows,
sessions, the transport between browser and server and its live updates,
routing on both sides, the page shell, the browser build and its
stylesheets, background jobs and the REPL.

The rules it holds an app to:

- Settings are data, every key required (but `:styles`); a missing or unknown key stops the start.
- Every ability is declared: each route, endpoint and table says who may use it or how it rings, or the start refuses.
- One name for a piece of data, from the column (`order__shop_id`) to the browser (`:order/shop-id`).
- SQL lives only in `.sql` files.
- Who may do what is one list of sentences, and Postgres enforces it.
- What Groundwork does, an app does only through Groundwork: no hand-built addresses, no replaced parts.

Java 25 or newer. The spec is [`decisions/`](decisions/), one file per
decision; [its index](decisions/README.md) lists the ones in force.
[`ADOPTING.md`](ADOPTING.md) holds the notes for moving an app to the
current names, the access language, the TLS setting and production's migrate.

Decisions: [Groundwork is an opinionated framework](decisions/2026-10-12-groundwork-is-an-opinionated-framework.md) · [Declared, through one door](decisions/2026-10-14-declared-through-one-door.md) · [Groundwork ships these parts](decisions/2026-10-18-groundwork-ships-these-parts.md) · [Groundwork ships no team endpoints and no eval bridge](decisions/2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md) · [Names are made consistent once](decisions/2026-10-18-names-are-made-consistent-once.md) · [One folder per area](decisions/2026-10-18-one-folder-per-area.md) · [The endpoints area holds its transport](decisions/2026-10-18-the-endpoints-area-holds-its-transport.md) · [Java 25 or newer](decisions/2026-10-12-java-25-or-newer.md).

---

## Starting an app

The smallest settings that start: one page, no tables of the app's own, no
permissions, no endpoints, no jobs. Each area below fills its part.

```clojure
(ns app.settings
  (:require [groundwork.web :as web]))

(def dev
  {:environment :development
   :db          {:host "localhost" :port 5432 :database "app" :user "app" :password nil
                 :pool-size 10 :statement-timeout-ms 30000 :time-zone "UTC" :tls :off}
   :migrations  {:schema-snapshot "schema.sql"}
   :page        {:title "Acme Orders" :description nil :icon "/icon.svg" :stylesheets [] :head []}
   :browser     {:build :app :entry 'app.browser/start
                 :output-dir "resources/public/js" :asset-path "/js" :watch? true}
   :web         {:routes                  [["/" {:get web/page :access :public}]]
                 :max-body-bytes          1048576
                 :content-security-policy {}
                 :login-path              "/login"
                 :on-request              nil
                 :on-error                nil
                 :proxies                 {:trusted [] :client-ip-header nil :request-id-header nil}
                 :languages               {:supported ["en"] :default "en" :cookie nil}
                 :test-sign-in            nil
                 :hashed-files            "(?i)\\.[0-9a-f]{8,}\\."
                 :gzip-min-bytes          1024}
   :server      {:ip "127.0.0.1" :port 8080}
   :sessions    {:cookie-name "app_session" :max-age-days 30 :visitor-hours 24
                 :secure? false :origin "http://127.0.0.1:8080"}
   :permissions {:scopes {} :records {} :rules []}
   :limits      {:public {:reads-per-minute 600 :writes-per-minute 60} :on-limit nil}
   :jobs        nil
   :endpoints   {:list [] :max-asks 50 :max-running 8 :bearer nil :on-answer nil
                 :bells {:global {} :personal {}}}})
```

The start also needs the database to exist, and a role that is no
superuser and has no BYPASSRLS: it refuses such a role. In development and
tests that one role owns the tables, and the start migrates.

**Development.** The app's `:dev` alias has `nrepl/nrepl` and
`thheller/shadow-cljs`. One call starts the app, the browser build's watcher
and an nREPL: `(groundwork.repl.dev/start! app.settings/dev {})`, or, with no
terminal, `clojure -X:dev groundwork.repl.dev/serve :settings app.settings/dev :nrepl-port 7888`
(see [The REPL](#the-repl)).

**Release.** Build the browser code and its stylesheets before packaging:
`clojure -X:build groundwork.build/release :settings app.settings/production`.
Groundwork has no main of its own: the app's main calls
`(gw/start app.settings/production)`.

**Production.** Two database roles do two jobs at two moments. The deploy
runs migrate first, as the role that owns the tables; then the app starts
as its own role (`:db :user`), which owns nothing and holds only what
migrate granted it.

```sh
# the deploy, before the start; the owner's name and password are this
# command's environment only, never the running app's
GROUNDWORK_OWNER_USER=app_owner GROUNDWORK_OWNER_PASSWORD=… clojure -X:migrate
```

```clojure
;; deps.edn
:migrate {:exec-fn groundwork.core/migrate :exec-args {:settings app.settings/production}}
```

`:settings` names a var holding the settings, or a function of no
arguments that returns them: the same settings the start gets. Where there
is no Clojure CLI (a release run with `java -cp`), call the function itself:
`(gw/migrate! settings {:user "app_owner" :password …})`, from the app's own
migrate main or `clojure.main -e`.

Migrate, under the migration lock:

1. refuses an app role that is the owner itself, doesn't exist, can pass by
   row-level security, or owns anything in `public` or `groundwork` (a
   schema, table, view, sequence or function; itself or through a role it
   is a member of);
2. runs Groundwork's new migrations, then the app's;
3. installs everything Groundwork puts into the database from the settings,
   and records its fingerprint in `groundwork.installs`: the wall
   (row-level security forced, its policies, the vias' functions and
   guards, the functions that narrow a list), the fill rules, the bells'
   triggers;
4. grants the app role, then checks it holds every grant:
   `USAGE` on the schemas `public` and `groundwork`; `SELECT`, `INSERT`,
   `UPDATE`, `DELETE` on every table (only `SELECT` on
   `groundwork.migrations` and `groundwork.installs`), `SELECT` on every
   view; `USAGE`, `SELECT`, `UPDATE` on every sequence; `EXECUTE` on every
   function. A grant the owner may not give stops it.

The production start migrates and installs nothing. It refuses, saying to
run migrate, when:

- no migration ran yet, a migration hasn't run, or one changed or went
  missing since;
- the app role owns anything in `public` or `groundwork`, or lacks a grant
  of the list above (a table made after the last migrate);
- the wall, fill rules or bells' triggers its settings make aren't the
  ones migrate installed: the fingerprints differ, because the settings or
  Groundwork changed since;
- the app role is a superuser or has BYPASSRLS (in every environment).

Then each start writes nothing to the schema: a start doesn't wait on the
statements that put up the wall.

Decisions: [Production migrates before it starts](decisions/2026-10-18-production-migrates-before-it-starts.md).

---

## Core

Starts and stops an app.

```clojure
(require '[groundwork.core :as gw])
(def app (gw/start settings))
(gw/stop app)
(gw/migrate! settings {:user "app_owner" :password …})   ; production, before the start
```

- The parts are db, migrations, permissions, sessions, limits, jobs,
  endpoints, their transport, page, router and server. Integrant starts each
  after the parts it needs; they stop in reverse. Settings don't name or
  replace parts.
- When a part refuses, the parts started before it stop again, and its own
  error is thrown, saying what to change.
- For the REPL and tests: `(gw/current)`, `(gw/part app :groundwork/db)`,
  `gw/db`, `gw/permissions`, `gw/sessions`, `gw/jobs`, `gw/endpoints`, `gw/port`.
- `(gw/swap-routes! app routes)` serves new routes without a restart,
  checked like at start; wrong ones leave the old in place.
- Tests can replace a part: `(gw/start-with settings {:groundwork/server nil})`.

Door: `groundwork.core`.

Decisions: [Integrant wires the parts](decisions/2026-10-12-integrant-wires-the-parts.md) · [The library does not need sops](decisions/2026-10-12-the-library-does-not-need-sops.md).

---

## Settings

One malli schema per part, each key with a description and an example.

- Every key is required but `:styles`; an unknown key stops the start.
  `:jobs` and `:styles` take `nil` for none. In production `:web :test-sign-in` must be `nil`.
- Every problem is reported at once, with its place:
  `:sessions :visitor-hours must be a positive whole number, got "24"`.
- The same schemas check a permission description, an endpoint's entry, a
  job's entry and a route's data. What needs more than a shape (an action
  the description lacks, a column the database lacks) is checked by its part.
- They write the [settings reference](#settings-reference),
  [`docs/example-settings.edn`](docs/example-settings.edn), and answer
  `(groundwork.repl/explain-setting [:sessions :visitor-hours])`.

Door: `groundwork.settings`; the docs come from `groundwork.settings.docs`.

Decisions: [Settings are required data](decisions/2026-10-12-settings-are-required-data.md).

---

## Postgres

The database: a HikariCP pool, `.sql` files as vars, rows as maps, transactions.

A column is `entity__field` in the database and `:entity/field` everywhere
else: `:order/shop-id ↔ order__shop_id`, `:count ↔ count`
(`groundwork.postgres.names/key->column`, `column->key`). Lowercase letters
and digits, words joined by single hyphens; anything else, or a name over 63
characters, throws.

```sql
-- src/app/orders/of_shop.sql
-- A shop's open orders, newest first.
SELECT order__id, order__note, order__total
FROM orders
WHERE order__shop_id = :shop/id AND order__status = 'open'
ORDER BY order__created_at DESC
LIMIT :limit
```

```clojure
(ns app.orders.db
  (:require [groundwork.postgres :as pg]))

(pg/defsql of-shop)   ; of_shop.sql, next to this file; its first comment is the docstring
(pg/defsql close)

(pg/rows db of-shop {:shop/id shop-id :limit 20})   ;=> [{:order/id … :order/note … :order/total …} …]
(pg/row db close {:order/id id})                    ;=> one map or nil; more than one throws
(pg/execute! db close {:order/id id})               ;=> rows touched
(pg/statement of-shop {:shop/id shop-id :limit 20}) ;=> ["SELECT … LIMIT ?" [shop-id 20]]

(pg/with-transaction [tx db] …)                     ; an exception rolls back and is rethrown
(pg/with-transaction [tx db {:read-only? true :isolation :repeatable-read}] …)
(pg/with-transaction [tx db {:statement-timeout-ms 600000}] …)   ; slow on purpose, or :none
```

- A param has a colon: a field of the data is namespaced (`:order/id`), a
  control of the query plain (`:limit`). A missing param throws; `nil` is `NULL`.
- A missing file fails when the namespace loads; a saved file on disk is
  read again on the next call.
- A map goes in as jsonb, `(pg/json x)` makes anything jsonb. A list
  (vector, seq, set) goes in as the type Postgres expects in its place, a
  SQL array or json; an empty list works. Where nothing tells the type, cast
  it: `:x::int[]`.
- Values come back as real types: UUID, `java.time`, `BigDecimal`. jsonb
  keys shaped `entity__field` become `:entity/field`; other keys stay text.
- Every result column needs a name: an unnamed expression (`?column?`)
  throws, naming the file. Column names become keywords once per query.
- A nested `with-transaction` joins the outer one; its options must match.
- Every connection's time zone is `:time-zone` and its search path only
  `public`; Groundwork's own tables live in the `groundwork` schema.
- `:tls` is required. `:verified`: encrypted, the server's certificate must
  lead to one the Java runtime trusts (or to one in the `:tls-root-cert` PEM
  file the app names) and must name `:host`; a server without TLS is
  refused, never used plain (PgJDBC's `sslmode=verify-full`). `:off`: plain;
  production refuses it unless the database is on the same machine
  (`localhost`, `127.x.x.x`, `::1`). Every connection Groundwork opens has
  it: the pool, the bells' and the jobs' listening connections, migrate, and
  `pg_dump` for the snapshot (`PGSSLMODE=verify-full`, and `PGSSLROOTCERT`
  the file or libpq's `system`). PgJDBC's own defaults on the classpath
  (`driverconfig.properties`) don't change it.
- `:on-transaction (fn [tx] …)` runs first in every `with-transaction`, and
  only there; `:on-query (fn [{:keys [file sql ms error]}] …)` after every query.
- An error is an `ex-info` whose `:type` is under `:groundwork.postgres/`:
  `unique-violation`, `foreign-key-violation`, `check-violation`,
  `not-null-violation`, `serialization-failure`, `deadlock`,
  `query-canceled` (past the time limit), else `error`.

Door: `groundwork.postgres`.

Decisions: [Postgres through next.jdbc and PgJDBC](decisions/2026-10-12-postgres-through-next-jdbc-and-pgjdbc.md) · [The database connection is encrypted and verified](decisions/2026-10-18-the-database-connection-is-encrypted-and-verified.md) · [Connections come from a HikariCP pool](decisions/2026-10-12-connections-come-from-a-hikaricp-pool.md) · [The time zone is a setting](decisions/2026-10-12-the-time-zone-is-a-setting.md) · [Queries have a time limit](decisions/2026-10-12-queries-have-a-time-limit.md) · [Slow work gets time through its transaction](decisions/2026-10-12-slow-work-gets-time-through-its-transaction.md) · [The transaction hook runs only in transactions](decisions/2026-10-12-the-transaction-hook-runs-only-in-transactions.md) · [SQL lives only in files](decisions/2026-10-12-sql-lives-only-in-files.md) · [SQL files are plain SQL](decisions/2026-10-12-sql-files-are-plain-sql.md) · [A param is named by what it is](decisions/2026-10-12-a-param-is-named-by-what-it-is.md) · [A list param takes the type Postgres expects](decisions/2026-10-12-a-list-param-takes-the-type-postgres-expects.md) · [Columns are named entity__field](decisions/2026-10-12-columns-are-named-entity__field.md) · [Every column has a name](decisions/2026-10-12-every-column-has-a-name.md) · [JSON keys convert only when shaped entity__field](decisions/2026-10-12-json-keys-convert-only-when-entity__field.md) · [Keys are made once per query](decisions/2026-10-12-keys-are-made-once-per-query.md) · [Values come back as real types](decisions/2026-10-12-values-come-back-as-real-types.md) · [row throws on more than one row](decisions/2026-10-12-row-throws-on-more-than-one-row.md).

### Migrations

Numbered plain SQL files: `resources/migrations/001_create_shops.sql`,
`002_create_orders.sql`.

- Under a lock in Postgres, Groundwork runs its own new migrations, then
  the app's, in number order. Each file runs once, in its own transaction,
  with no time limit, and is remembered with its fingerprint.
- Who runs them: in development and tests the start, as the app's one
  role; in production migrate, as the owner, before the start, which only
  checks that every one ran (see [Production](#starting-an-app)).
- A failing file rolls back and the app doesn't start (or migrate stops).
  A file that ran and then changed or went missing stops the start: a fix
  is a new file.
- In development, with `pg_dump` on the PATH, the whole schema is written to
  `:schema-snapshot` once the start is done, with the wall's policies, fill
  rules and triggers, so a changed permission rule shows as a difference.

Door: `groundwork.migrations`; production's migrate is `groundwork.core/migrate!`.

Decisions: [Migrations are numbered SQL files](decisions/2026-10-12-migrations-are-numbered-sql-files.md) · [The schema snapshot is written when the start is done](decisions/2026-10-13-the-schema-snapshot-is-written-when-the-start-is-done.md) · [Production migrates before it starts](decisions/2026-10-18-production-migrates-before-it-starts.md).

### Jobs

Work that shouldn't happen inside a request, or happens on a clock, queued
in Postgres.

```clojure
:jobs {:kinds     [app.mail/jobs]                                                 ; or :jobs nil
       :schedules [{:job :app.mail/send-reminders :params {} :cron "0 18 * * *"}]   ; UTC
       :workers 4 :poll-ms 5000 :on? true}

(def jobs                                                       ; in app.mail
  {:app.mail/send-invoice   {:run        (fn [ctx params] …)   ; ctx: :db (its transaction), :job, :attempt, :jobs
                             :params     [:map [:invoice/id :uuid]]
                             :timeout-ms 60000
                             :attempts   5
                             :runs-in    [:invoice :invoice/id]}   ; optional
   :app.mail/send-reminders {:run (fn [ctx params] …) :params [:map] :timeout-ms 300000 :attempts 3}})

(jobs/enqueue! ctx :app.mail/send-invoice {:invoice/id id})   ; in a write; {:in-ms n :key k} optional
```

- A job is queued in the write's own transaction: it exists only if that
  commits. Each due job is claimed by one worker on one server and runs on a
  virtual thread within `:timeout-ms`, in one transaction: as the system, or
  with `:runs-in` behind the wall of one scope.
- A failure waits longer each time (30 s, doubling, up to an hour); after
  `:attempts` the job is dead, kept with its error. Work that throws
  `(jobs/give-up "why")` makes it dead at once. A job left running by a
  stopped server is queued again, so its work must be safe to repeat.
- `:key`: one job per key while it is remembered (done jobs are kept a week).
- A due schedule queues its job once across servers.
- `:on? false` (a preview): jobs are queued and wait.
- At start, done jobs older than a week are deleted.

Door: `groundwork.jobs`.

Decisions: [A job can give up for good](decisions/2026-10-12-a-job-can-give-up-for-good.md).

---

## Permissions

Who may do what, where. The app describes its kinds of scope (where data
belongs and people are members), its kinds of record, and one list of
sentences. Groundwork keeps scopes, roles and members in its own tables,
checks the description against the database at start, and turns each
sentence into SQL: the wall, the checks, the bells' reach, "can I?".

```clojure
:permissions
{:scopes  {:shop     {:actions {:orders/see   #{:read}              ; what each action does at the wall
                                :orders/edit  #{:read :update}
                                :invoices/see #{:read}
                                :money/move   #{:read :insert}}
                      :roles   {"Manager" #{:orders/see :orders/edit :invoices/see :groundwork/team}}
                      :parent  nil
                      :table   :shops :id :shop/id}                 ; the app's table of shops, for a deny
           :partner  {:actions {:agreements/see #{:read}} :roles {} :parent nil}
           :platform {:actions {:platform/support #{:read}} :roles {} :parent nil
                      :single? true}}                               ; exactly one scope: the back office
 :records {:order     {:table :orders :id :order/id
                       :whose {:order/shop-id :shop :order/customer-id :person}}
           :invoice   {:table :invoices :id :invoice/id :whose {:invoice/shop-id :shop}
                       :parent [:order :invoice/order-id]}           ; it hangs under an order
           :agreement {:table :agreements :id :agreement/id
                       :whose {:agreement/shop-id :shop :agreement/partner-id :partner}}}
 :rules   [{:allow #{:orders/see :orders/edit} :on :order :to [:member :order/shop-id]}
           {:allow :orders/see :on :order :to [:me :order/customer-id]}
           {:allow :orders/see :on :order                           ; a partner, while the agreement is active
            :via {:table :agreements :match {:order/shop-id :agreement/shop-id}
                  :to [:member :agreement/partner-id :agreements/see]
                  :when [:= :agreement/status "active"]}}
           {:allow :orders/see :on :order :to [:member :platform :platform/support]}
           {:allow :invoices/see :on :invoice :to [:member :invoice/shop-id]}
           {:allow :agreements/see :on :agreement :to [:member :agreement/partner-id]}
           {:deny :money/move :session :impersonated}
           {:deny :writes :scope :shop :when [:= :shop/status "suspended"]}]}
```

**The words.** A sentence allows an action (or a set) on a kind of record:

- `:to` `:anyone`, `:signed-in`, `[:member <column>]` (a member of the
  scope the row's column names, whose role holds the action),
  `[:member <single-scope kind>]` or `[:member #uuid "…"]` (a member of one
  fixed scope, which reaches every scope's rows), `[:me <column>]` (the
  person or the visitor in that column). A `[:member …]` may name, third,
  the action the role must hold there.
- `:when` a condition on the row: `[:= col v]` `[:not= col v]` `[:in col [v …]]`
  `[:is-null col]` `[:not-null col]` `[:and …]` `[:or …]`, and
  `[:none <scope column>]` for a row that belongs to nobody.
- `:via` instead of `:to`: a related row exists for which its `:to` and
  `:when` hold, in any direction. Both sides of `:match` and the related
  row's who column need an index, or the start refuses with the SQL to add.
- `:deny` an action, a set or `:writes`, by a session flag or by a scope's
  state (a `:when` on the kind's `:table`). A deny comes before every yes;
  nothing else says no.
- An open write (an action that writes, given `:anyone` or `:signed-in`)
  needs a `:when` or a `:via` that narrows it. The writer sees the row only
  if a reading sentence gives it.

The start refuses an unknown word, kind, action, table or column, an action
nobody in that kind of scope or above can hold, and an open write nothing narrows.

**Records.**

- `:whose` says whose row each column names: a scope kind, `:person`, or
  `:visitor` (the id of a visitor's session). Every scoped table names its
  scope, however deep: an invoice keeps its shop, not only its order.
- A record under another names its `:parent`, and the database ties them:
  the child's (parent, scope) references the parent's (id, scope), `ON
  UPDATE CASCADE`. Groundwork fills what a row carries from its parent when
  it's inserted without it. The start refuses a missing or nullable scope
  column and a missing or non-cascading link; the error carries the SQL.
- A column some of a row's readers mustn't see (money, personal data) goes
  in a table of its own, with its own sentences: the wall works on whole rows.
- `[:me <visitor column>]` gives a row to a visitor; at sign-in the
  visitor's rows move to the person.

**What it guarantees.**

- **The wall:** every record kind's table gets forced row-level security, a
  policy per command, made from the sentences. A transaction behind the
  wall sees and writes only what the sentences give in the scopes verified
  for it.
- **Checks:** a record is checked through its own columns, never a scope the
  browser names. Another scope's record answers not-found, the same as one
  that isn't there; so does every other id a request carries.
- **Narrowing by an action:** for "only the orders I may edit", a query calls
  `groundwork."may orders/edit on order"()`, which Groundwork makes per action
  and record kind: `WHERE order__id IN (SELECT * FROM groundwork."may orders/edit on order"())`.
- **"Can I?":** the built-in endpoint `:groundwork/grants` answers every
  action the sentences give the person, and where (no scope: every scope).

```clojure
(require '[groundwork.permissions :as perm])
(def perms (gw/permissions app))
(perm/allowed? perms {:person p :flags #{} :action :orders/edit :record [:order id]})
(perm/check! perms {:person p :flags #{} :action :orders/edit :record [:order id]
                    :also {:agreement [agreement-id]}})            ; every id; else not-found
(perm/list-filter perms {:person p :flags #{} :action :orders/see :kind :order})   ; a WHERE with params
(perm/in-wall perms {:person p :scopes #{shop-id}} (fn [tx] …))    ; behind the wall
(perm/as-system perms (fn [tx] …))                                 ; every row
```

**Teams.** Scopes, roles, memberships, extra actions and invitations live in
the `groundwork` schema, uncached: `perm/create-scope!`, `invite!`,
`accept-invitation!`, `change-role!`, `create-role!`, `set-role-actions!`,
`remove-member!`, `grant-extra!`, `revoke-extra!`. A new scope gets an
`"Owner"` role and its kind's `:roles`. A change needs `:groundwork/team`,
hands out only actions the changer holds, and leaves the scope an owner; only
an owner makes or unmakes an owner. The app writes its own team endpoints around these.

Door: `groundwork.permissions`.

Decisions: [Groundwork ships permissions](decisions/2026-10-12-groundwork-ships-permissions.md) · [Who may see what is the app's; Groundwork enforces it](decisions/2026-10-15-who-may-see-what-is-the-apps.md) · [Access rules are sentences in Groundwork's language](decisions/2026-10-17-access-rules-are-sentences-in-groundworks-language.md) · [Every scoped table names its scope](decisions/2026-10-12-every-scoped-table-names-its-scope.md) · [A record of two scopes may have a parent and children](decisions/2026-10-13-a-record-of-two-scopes-may-have-a-parent-and-children.md) · [A record between fixed sides carries them](decisions/2026-10-13-a-record-between-fixed-sides-carries-them.md) · [A record may wait for its scope](decisions/2026-10-13-a-record-may-wait-for-its-scope.md) · [A record can belong to a visitor](decisions/2026-10-13-a-record-can-belong-to-a-visitor.md) · [Shared rows may belong to nobody](decisions/2026-10-16-shared-rows-may-belong-to-nobody.md) · [Money and personal data live in their own tables](decisions/2026-10-17-money-and-personal-data-live-in-their-own-tables.md).

### Sessions

Who is signed in. The app's login step ends with `sign-in`.

```clojure
(require '[groundwork.sessions :as sessions])
(sessions/sign-in request response person-id {:flags #{:impersonated} :data {:login/via "sso"} :hours 1})
(sessions/person request)   (sessions/visitor request)   (sessions/flags request)   (sessions/data request)
(sessions/put-data request response {:cart/items items})                  ; nil forgets a key
(sessions/put-data request response {:cart/items items} {:keep-days 30})  ; a visitor's, kept longer
(sessions/sign-out request response)
(sessions/sign-out-everywhere! (gw/sessions app) person-id)
```

- A session is a row in `groundwork.sessions`; the cookie (HttpOnly,
  SameSite=Lax, Secure when `:secure?`) holds 32 random bytes, the table
  only their SHA-256.
- Signing in ends the request's old session; a visitor's data and records
  come along. A sign-in lives `:max-age-days`, or its own `:hours`.
- A visitor gets a session only when the app keeps something for them, so
  crawlers cost no rows; it ends after `:visitor-hours` unseen.
- In another site's frame a visitor uses a second cookie,
  `<cookie-name>_embed` (SameSite=None; Secure; Partitioned); signing in
  there is refused.
- A request that changes something (not GET, HEAD, OPTIONS) must carry the
  app's `:origin`, or it gets 403.
- A server-to-server route has no session; one that signs someone in puts
  the part on first: `(sessions/on-request request (gw/sessions (gw/current)))`.

Door: `groundwork.sessions`.

Decisions: [Groundwork ships no logins](decisions/2026-10-12-groundwork-ships-no-logins.md) · [Sessions live in Postgres](decisions/2026-10-12-sessions-live-in-postgres.md) · [The session is the only truth](decisions/2026-10-12-the-session-is-the-only-truth.md) · [Pages for other sites, visitors, and servers](decisions/2026-10-12-pages-for-other-sites-visitors-and-servers.md).

### Limits

How often a caller may ask, for endpoints and routes alike.

```clojure
:invoices/send {:write … :rate-limit {:per-day 20 :by :person}}                    ; an endpoint's own
["/quote" {:post … :access :public :rate-limit {:per-minute 10 :by :client-ip}}]   ; a route's own
```

- `:per-minute` is a bucket in each server's memory; `:per-day` a cap in
  Postgres, across servers, reset at midnight UTC.
- `:by` `:person`, `:client-ip`, or `:scope` (endpoints only); without it the
  person, or the address when no one is signed in.
- A public endpoint or route without its own limit gets `:limits :public`;
  anything else without one has none.
- Checked after access, before the work: an endpoint answers `:too-many`
  with `:retry-after-seconds`, a route 429 with `Retry-After`. Every hit goes
  to `:on-limit`.

Door: `groundwork.limits`.

### The leak test

For the app's own tests: every endpoint whose `:access` is an action is
asked with another scope's ids and must answer not-found.

```clojure
(require '[groundwork.permissions.leaks :as leaks])

(deftest nothing-leaks
  (let [report (leaks/check (gw/endpoints app)
                            {:person  manager                                          ; a member of one shop
                             :foreign {:shop other-shop :order other-order :invoice other-invoice}
                             :sealed  #{a-draft-order}})]                              ; ids no stranger may see
    (is (empty? (:leaked report)) (pr-str (:leaked report)))))
```

- Params are generated from each input shape, every id set to the foreign
  one of its kind. A list's answer must hold none of the foreign ids; an
  open action's, asked as a stranger, none of the `:sealed` ones. A visitor's
  action is asked again as another visitor and as a signed-in stranger, and
  must answer not-found.
- The wall and every narrowing function are checked against the sentences:
  `:leaked` for a row shown that no sentence gives, `:hidden` for one hidden.
- It proves the wall matches the sentences, not that the sentences are
  right. The report lists which endpoints it `:checked` and which it `:skipped`, with why.
- It needs `org.clojure/test.check` on the test classpath.

---

## Endpoints

Everything the browser, or a tool, can ask the server, listed by area:

```clojure
(def order [:map [:order/id :uuid] [:order/note :string] [:order/total :int]])

(def endpoints
  {:orders/of-shop {:read   #'orders/of-shop          ; (fn [ctx params] answer)
                    :access {:action :orders/see :scope [:shop :shop/id]}
                    :input  [:map [:shop/id :uuid]]
                    :answer [:vector order]}
   :orders/hand-to {:write  #'orders/hand-to
                    :access {:action :orders/edit :record [:order :order/id]}
                    :input  [:map [:order/id :uuid]
                                  [:agreement/id {:groundwork/record :agreement} :uuid]]
                    :answer order}})

:endpoints {:list [app.orders/endpoints app.invoices/endpoints] …}
```

- Every endpoint declares its handler (`:read` or `:write`), `:access`,
  `:input` and `:answer`, or the start refuses; so does a name in two areas
  or under `groundwork/`.
- `:access`: `:public`, `:signed-in`, or an action on one record
  (`:record [kind param]`), a scope (`:scope [kind param]` or
  `[kind #uuid "…"]`, with the scopes below it), or every record of a kind
  the person may (`:list kind`). A write declares an action that writes.
- Every input field holding ids (`id`, `…-id`, `…-ids`) says its record
  kind, `{:groundwork/record :agreement}`, or `{:groundwork/record false}`.
  Each id must belong to the verified scopes.
- A read runs read-only; a write commits only when its answer fits its
  shape. Both run behind the wall. A write with a one-time key runs once.
- ctx: `:db` (the transaction), `:person`, `:visitor`, `:flags`, `:scopes`,
  `:every-scope?`, `:request-id`, `:request` (nil for a tool), `:jobs`, and
  `(:after-commit ctx)`, which takes `(fn [db] …)` to run after the commit.
- `:keeps-for-visitor? true`: asked without a session, the visitor gets one first.
- Answers: `:ok`, `:invalid`, `:signed-out`, `:not-found` (not allowed looks
  the same), `:refused` (`(throw (endpoints/refuse "This order is closed"))`),
  `:too-many`, `:error` (a plain message; details to `:web :on-error`).
- `(endpoints/call (gw/endpoints app) ask)` runs one ask;
  `endpoints/call-with-token` runs one for a tool, whose person `:bearer`
  finds. Groundwork reads no token from a request: the app's own route calls it.

Door: `groundwork.endpoints`.

Decisions: [What every endpoint declares](decisions/2026-10-12-what-every-endpoint-declares.md) · [Every endpoint declares its answer](decisions/2026-10-12-every-endpoint-declares-its-answer.md) · [Reads and writes share one path](decisions/2026-10-12-reads-and-writes-share-one-path.md).

### The transport

One address, `/parquery`: a POST for asks, a websocket for bells.

```
POST /parquery
[{"endpoint": "orders/of-shop", "params": {"shop/id": "…"}},
 {"endpoint": "orders/hand-to", "params": {…}, "key": "…"}]
→ [{"status": "ok", "data": […]}, {"status": "not-found"}]
```

- The answers come back in order. Each ask runs apart, in parallel, in its
  own transaction: one failing leaves the others. At most `:max-asks` per
  request and `:max-running` across the server.
- JSON typed by the endpoint's shapes: dates (`:time/local-date` …) and
  decimals (`:decimal`) travel as text and come back typed (`java.time` and
  `BigDecimal` on the server, js-joda and big.js in the browser). An id is a
  real UUID on the server, checked strictly, and text in the browser.
- The route is public; each endpoint checks its own access and limit, under
  the session and the Origin check.

Door: `groundwork.endpoints` (machinery: `groundwork.endpoints.route`, `.http`, `.websocket`, `.wire`).

Decisions: [parquery is the transport](decisions/2026-10-12-parquery-is-the-transport.md) · [parquery is one address](decisions/2026-10-12-parquery-is-one-address.md) · [Asks travel together and run apart](decisions/2026-10-12-asks-travel-together-and-run-apart.md) · [Each ask fails alone](decisions/2026-10-12-each-ask-fails-alone.md) · [JSON and malli on the wire](decisions/2026-10-12-json-and-malli-on-the-wire.md) · [Dates and decimals keep their types in the browser](decisions/2026-10-12-dates-and-decimals-keep-their-types-in-the-browser.md) · [An id is text in the browser](decisions/2026-10-14-an-id-is-text-in-the-browser.md).

### Bells

Live updates. A bell says which reads on screen changed; it carries no data,
and the browser asks again.

```clojure
:bells {:global   {:countries "reference data only the platform's staff change"}
        :personal {:notifications :notification/person-id}
        :silent   {:import_log "written once by the nightly import, shown nowhere"}}
```

- **Every table of the app says how it rings, or the start refuses,** naming
  the ones that don't: a record kind in the permission description, or in `:bells` as
  `:global` (every watcher, with the reason), `:personal` (its person) or
  `:silent` (nothing, with the reason). `:groundwork/every-other-table` in
  `:silent` covers a database the app shares with something else.
- **Who is rung comes from who may see:** a scope's rows ring the people the
  sentences let see them; a row that changes who sees it rings the old
  viewers and the new.
- Postgres rings: a trigger on each ringing table announces, on commit, the
  table and the changed rows' scopes, people and ids. A watch waits for the
  tables its read's SQL uses (Postgres's plan, plus the endpoint's `:reads`).
- A write's answer carries its own bell: the reads on screen it changed are
  asked again at once, and the websocket's bell for it isn't asked twice.
- A line lives as long as its session; a sweep every minute checks every
  line's watches again. A dropped listening connection reconnects and rings
  everything.

Door: `groundwork.endpoints` (machinery: `groundwork.endpoints.bells`, `.websocket`).

Decisions: [Postgres rings the bell](decisions/2026-10-12-postgres-rings-the-bell.md) · [Who is rung comes from who may see](decisions/2026-10-13-who-is-rung-comes-from-who-may-see.md) · [Asking again, and bells that miss nothing](decisions/2026-10-14-asking-again-and-bells-that-miss-nothing.md).

### The browser store

The browser's answers, kept by question (endpoint and params) in re-frame's db.

```clojure
(require '[groundwork.endpoints.store :as store] '[re-frame.core :as rf])
(def ask {:endpoint :orders/of-shop :params {:shop/id shop-id}})
(rf/dispatch [::store/ensure :orders-page [ask]])        ; a screen opens
@(rf/subscribe [::store/answer ask])                    ;=> {:status :ready :data […]}, :loading or :error
(rf/dispatch [::store/release :orders-page])            ; it closes
(rf/dispatch [::store/own :orders-page [ask …]])        ; exactly these now
(rf/dispatch [::store/ask-again ask])                   ; a "try again" button
(rf/dispatch [::store/once-answered ask [:orders/loaded]])
(rf/dispatch [::store/hear :orders-table [ask] [:orders-table/reload]])
(rf/dispatch [::store/write {:endpoint :orders/hand-to :params {…}} [:orders/handed]])
@(rf/subscribe [::store/can? :orders/edit shop-id])      ; from the grants
```

- Showing never fetches. Two asks for one question make one request.
- Old data stays while a fresh answer loads; only the newest answer counts.
- Bells are gathered for 200 ms; only what's on screen is asked again.
- An entry nothing needs is dropped after five minutes.
- A write is sent once, with a one-time key, and never repeated by itself.
- Nothing asks before the start: a question put before `gb/start!` throws,
  naming the endpoint.
- A dropped websocket comes back by itself and asks everything on screen again.

Door: `groundwork.endpoints.store` (ClojureScript; its halves `groundwork.endpoints.http`, `.websocket`).

Decisions: [The browser keeps answers by question](decisions/2026-10-12-the-browser-keeps-answers-by-question.md) · [Nothing asks before the start](decisions/2026-10-13-nothing-asks-before-the-start.md).

---

## Web

The router, and what every request gets. The app's `:web :routes` sit next
to Groundwork's own (`/health`, `/parquery`, the test sign-in), served by
http-kit, each request on its own virtual thread.

**Every route declares who may use it,** or the app doesn't start:

```clojure
["/pricing"          {:get web/page :access :public}]
["/orders"           {:get web/page :access :signed-in}]   ; signed out: a page goes to :login-path?next=…, else 401
["/orders/:order-id/attachments"
                     {:post upload :access {:action :orders/edit :record [:order :order-id]}}]   ; else 404
["/payments/webhook" {:post webhook :server-to-server? true}]   ; no session, no Origin check
["/order-widget"     {:get web/page :access :public :embeddable? ["https://shop.example"]}]
```

- A route's action is checked on the record whose id is the parameter; a
  record that isn't the person's answers 404, like a missing one. `:scope` and `:list` are an
  endpoint's: a route naming one stops the start.
- A server-to-server route's handler checks the call (its signature); its
  body stays untouched.
- `:embeddable?` `true` (any https site), a list of origins, or
  `:same-origin`; every other answer can't be framed.

What every request gets:

- **Headers:** no sniffing, no framing, a strict referrer; in production
  Strict-Transport-Security. A strict Content-Security-Policy: scripts and
  styles from the app's origin or with the request's nonce, which the page
  shell stamps on its tags (`web/nonce` for a handler's own HTML), plus the
  `:content-security-policy` sources. Development also allows eval and websockets.
- **Ids and addresses:** every answer carries `X-Request-Id`
  (`web/request-id`); only a `:web :proxies :trusted` address is believed about
  the visitor's address (`web/client-ip`) and the id.
- **Hooks:** `:on-request` hears every answer, `:on-error` every uncaught
  exception (the browser gets a plain 500). A hook that throws breaks nothing.
- **Bodies:** at most `:max-body-bytes` or the route's own, else 413. Forms
  and multipart are parsed on browser routes; an uploaded file is a
  temporary file (deleted after an hour) with `:real-type`, what its bytes
  are (`groundwork.web.files/sniff-type`).
- **Files** from `resources/public`: revalidated on every load, byte ranges,
  a year's caching when the name matches `:hashed-files`. **gzip** from
  `:gzip-min-bytes`. **HEAD** for every GET. **`GET /health`**: 200, or 503
  without the database.
- **Outside production** `/robots.txt` disallows everything and every answer
  says `X-Robots-Tag: noindex, nofollow`.
- **Test sign-in** (`:test-sign-in {:secret …}`, never in production):
  `POST /groundwork/test-sign-in?person=<uuid>&flags=impersonated&hours=1`
  with `X-Test-Secret` answers the session cookie; a wrong secret is 404.

**Languages** (`:web :languages`, the one place a language is set): with
more than one, a `/de/…` prefix is taken off before routing, and the
request's language (`web/language`) is the address's, the cookie's, the
browser's Accept-Language, or the default, in that order. The page's
`lang` and its alternate links follow it, and the browser's routing reads
the same languages. With one language there is no prefix and there are no
alternate links.

Door: `groundwork.web` (machinery: `groundwork.web.http`, `.files`, `.body`, `.languages`, `.page`, `.server`).

Decisions: [http-kit serves, reitit routes](decisions/2026-10-12-http-kit-and-reitit.md) · [Pages for other sites, visitors, and servers](decisions/2026-10-12-pages-for-other-sites-visitors-and-servers.md).

### The page shell

The one HTML page that loads the app. Any address no route has gets it
when the browser asks for HTML, and the browser's routing takes over.

```clojure
["/shops/:slug" {:get web/page :access :public
                 :page (fn [request] {:title "…" :description "…" :body "<h1>…</h1>"})}]

(defn shop-page [request]                          ; a handler that has to look first
  (if-let [shop (find-shop request)]
    (web/page request {:title (:shop/name shop)})
    (assoc (web/page request {:title "No such shop"}) :status 404)))
```

- It holds `<div id="app">`, the build's scripts, and what the browser start
  reads first: the person, the session's flags, the language and the app's
  languages, and with `:styles` each module's bundle. It is never cached.
- A route's `:page` (a map or a function of the request) is merged over the
  `:page` settings: `:title :description :canonical :alternates :module
  :body`, plus `:stylesheets :scripts :head`. `:body` is HTML for search
  engines and the first paint.
- With `:styles`, the page links its modules' bundles before `:stylesheets`;
  in production the app doesn't start without the styles manifest.
- `:web :unknown-page`, a handler, answers unknown addresses instead, for an
  app whose pages are all routes.

Door: `groundwork.web` (`web/page`, `web/page-routes`).

Decisions: [Groundwork ships the page shell](decisions/2026-10-12-groundwork-ships-the-page-shell.md).

---

## Browser

The browser half's one start. The app's `:browser :entry` makes one call:

```clojure
(ns app.browser
  (:require [groundwork.browser :as gb] [app.routes :as routes] [app.shapes :as shapes] [app.views :as views]))

(defn root [] [:main [views/nav] [gb/screen]])   ; gb/screen: the current route's screen

(defn start []
  (gb/start! {:root        root
              :routes      routes/routes                 ; route records, below
              :shapes      shapes/endpoints              ; {endpoint {:input … :answer …}}, shared with the server
              :unknown     views/not-found               ; an address no route has
              :placeholder views/loading                 ; while code loads on demand
              :on-move     (fn [{:keys [from to]}] …)}))
```

- It reads what the page shell put in the page, starts the store and its
  websocket, asks for a signed-in person's grants, starts routing, and mounts
  `:root` into `<div id="app">` with Reagent.
- `[::gb/person]` (the id, as text), `[::gb/flags]`, `[::gb/language]`,
  `[::gb/route]`, `[::gb/query]`, `[::gb/held-move]`.

Door: `groundwork.browser`.

Decisions: [re-frame and Reagent in the browser](decisions/2026-10-12-re-frame-and-reagent-in-the-browser.md) · [Groundwork starts the browser and owns the build](decisions/2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md).

### Routing

The app writes its pages once, as route records in a `.cljc` file both sides read:

```clojure
(ns app.routes
  #?(:cljs (:require [app.views :as views])))

(def routes
  [["/orders" {:name   :orders
               :access :signed-in                                  ; the server checks it
               :title  "Orders"                                    ; the tab's title, on both sides
               :page   {:description "Every order of the shop"}    ; the page shell's details
               #?@(:cljs [:view  views/orders
                          :reads (fn [route] [{:endpoint :orders/of-shop :params {…}}])])}]
   ["/orders/:order-id" {:name :order :access {:action :orders/see :record [:order :order-id]}
                         #?@(:cljs [:view views/order :title (fn [route] …)])}]
   ["/reports" {:name :reports :access :signed-in :title "Reports"
                #?@(:cljs [:load        #(shadow.lazy/load views/reports-loadable)   ; code loaded on demand
                           :placeholder views/reports-loading])}]])

;; the server's :web :routes
(into (web/page-routes app.routes/routes)
      [["/payments/webhook" {:post payments/webhook :server-to-server? true}]])
```

- Every move sets the screen, the title and the scroll, asks the route's
  `:reads` (what the next route shares is kept), and calls `:on-move`. Back
  and Forward work.
- One door: `(gb/href :order {:order-id id})` for an `<a>`;
  `(gb/navigate! :order {:order-id id})` or `(gb/navigate! "/orders/5")`;
  `{:replace? true}` adds no step to Back. An unknown address loads from the server.
- Filters live in the address: `(gb/push-query! {:status "open"})` as a step
  Back undoes, `(gb/replace-query! {…})` without one.
- With languages, `/de/orders` is the same route; links keep the visit's
  language; `(gb/href :orders nil nil {:language "de"})` switches.
- Unsaved changes: while `(gb/unsaved! true)` holds, a move waits under
  `[::gb/held-move]` for `(gb/continue-move!)` or `(gb/stay!)`, and closing
  the tab asks.
- `web/page-routes` makes each record the page shell under its `:access`,
  leaving out what only the browser reads.

Door: `groundwork.browser` (engine: `groundwork.browser.routing`).

Decisions: [Routing is Groundwork's, and a route is a record](decisions/2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md).

### The loader

Code loaded on demand (`:browser :loader? true`) arrives as a `<script>`
tag from the app's own address, which the strict policy allows. Its module's
stylesheet bundles load first, so nothing shows unstyled.

Door: `groundwork.browser.loader`.

---

## Build

Groundwork makes the shadow-cljs build from the `:browser` settings; an app
keeps no build config.

- Development: the development start watches it when `:watch? true`.
  `clojure -X:dev groundwork.build/compile :settings app.settings/dev` builds
  once, without watching.
- Release: `clojure -X:build groundwork.build/release :settings app.settings/production`
  writes fingerprinted files (`main.3F2A….js`) and `manifest.edn`, which the
  page shell reads.
- Modules: `:browser :modules` names them (`:init-fn` or `:entries`,
  `:depends-on`), and a page names the one it starts:
  `["/admin" {:get web/page :access :signed-in :page {:module :admin}}]`.
  A page that names none loads the modules that depend on no other.
- `thheller/shadow-cljs` must be on the classpath. Groundwork's npm packages
  are in its `src/deps.cljs`; the app's `package.json` holds its own.

Door: `groundwork.build`.

Decisions: [Groundwork starts the browser and owns the build](decisions/2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md) · [A build can have several modules](decisions/2026-10-12-a-build-can-have-several-modules.md).

### Styles

A namespace names the stylesheets it needs, next to its file. Classes are
plain text, stylesheets plain CSS.

```clojure
(ns app.orders.list
  {:css ["orders.css"]}            ; src/app/orders/orders.css
  (:require [app.orders.row]))

:styles {:sources    ["src"]
         :output-dir "resources/public/css"
         :asset-path "/css"
         :targets    "safari >= 16.4, chrome >= 119"}   ; a browserslist query
```

Without `:styles` (or with nil) the app brings its stylesheets through
`:page :stylesheets`. With it, every build:

- checks that every `.css` under `:sources` is named by a namespace, every
  named file exists, and `:targets` names browsers; else it stops with the list;
- makes one bundle per module, in the order the build loads the namespaces,
  each file once;
- runs lightningcss for `:targets`: nesting flattened where needed, vendor
  prefixes written;
- writes readable bundles in development (a saved stylesheet is swapped into
  the open page), minified fingerprinted ones in a release, and `manifest.edn`.

A `url()` in a stylesheet is an absolute address (`/img/logo.svg`). The app
needs `npm install --save-dev lightningcss browserslist`.

Door: `groundwork.build` (bundles: `groundwork.build.styles`).

Decisions: [A namespace names its stylesheets](decisions/2026-10-17-a-namespace-names-its-stylesheets.md).

---

## The REPL

A REPL into a running app: development always, stage only over SSH,
production off. What's tried and not saved to a file is gone on the next start.

```clojure
(require '[groundwork.repl :as repl] '[groundwork.repl.dev :as dev])
(def d (dev/start! app.settings/dev {}))   ; :environment :development only
(def app (:app d))
(repl/ask app :orders/of-shop {:shop/id shop-id} :as manager)   ; every check a browser's ask gets
(repl/why app manager :orders/edit [:order order-id])          ;=> {:allowed? false :because ["…" …]}
(repl/lines app) (repl/recent-bells app) (repl/migrations app)
(repl/sessions app manager) (repl/sign-out-everywhere! app manager)
(repl/queued-jobs app) (repl/dead-jobs app) (repl/retry-job! app job-id)
(repl/explain-setting [:sessions :visitor-hours]) (repl/settings)
(dev/restart! d) (dev/stop! d)
```

- `dev/start!` runs the app, an nREPL (with a ClojureScript REPL on the same
  port: `(shadow.cljs.devtools.api/repl :app)`), the build's watcher (with
  `:browser :watch?`), and a log of everything printed at
  `.groundwork/dev.log`. It writes `.nrepl-port`, and points `clojure-mcp`
  at the nREPL in `.mcp.json` unless `{:mcp-json? false}`. An app's
  `.gitignore` lists `.groundwork/`, `.nrepl-port` and `.mcp.json`.
- `clojure -X:dev groundwork.repl.dev/serve :settings app.settings/dev :nrepl-port 7888`
  runs the same until SIGTERM; [`docs/groundwork-dev.service`](docs/groundwork-dev.service)
  is a systemd user unit for it. Its dev map is `@dev/running`:
  `(swap! dev/running dev/restart!)`.
- In the browser build's REPL: `(groundwork.repl.browser/entries)`, every
  question the store holds; `(groundwork.repl.browser/answer endpoint params)`, one.

Door: `groundwork.repl` (also `groundwork.repl.dev`, `groundwork.repl.browser`).

Decisions: [REPLs by environment](decisions/2026-10-12-repls-by-environment.md) · [We try queries in the REPL](decisions/2026-10-12-we-try-queries-in-the-repl.md) · [Groundwork ships no team endpoints and no eval bridge](decisions/2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md).

---

## Settings reference

Generated from the schemas: after a schema change, regenerate it with
`(groundwork.settings-test/write-docs!)`; never edit it by hand.

### Every key

<!-- settings reference: generated, see groundwork.settings-test -->
Generated from `groundwork.settings`; `(groundwork.repl/explain-setting [:sessions :visitor-hours])`
answers the same in the REPL, and `docs/example-settings.edn` holds every key with an example.

#### `:environment`: Where the app runs

`:development, :test or :production`

#### `:db`: The database

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:db :host` | text | The database server's address | `"localhost"` |
| `:db :port` | port number | Its port | `5432` |
| `:db :database` | text | The database's name | `"app"` |
| `:db :user` | text | The role the app connects as; in production one that owns nothing and can't pass by row-level security (migrate connects as the owner) | `"app"` |
| `:db :password` | text or nil | Its password, or nil for none |  |
| `:db :pool-size` | positive whole number | Connections per server, a fixed number | `10` |
| `:db :statement-timeout-ms` | positive whole number or :none | Postgres cancels a statement that runs longer; :none for no limit | `30000` |
| `:db :time-zone` | time zone name | The session's time zone: how times without an offset are read | `"UTC"` |
| `:db :tls` | :verified or :off | How the connection travels: :verified (encrypted; the server's certificate checked against the trusted certificates, and its name against :host) or :off (plain; in production only for a database on the same machine: localhost, 127.x.x.x or ::1) | `:verified` |
| `:db :tls-root-cert` (optional) | text | With :tls :verified, a PEM file of the certificates the server's must lead to, instead of the Java runtime's trusted ones (a private certificate authority) | `"/etc/app/db-root.pem"` |
| `:db :on-transaction` (optional) | function | Runs first in every with-transaction: (fn [tx] …) | `app.db/on-transaction` |
| `:db :on-query` (optional) | function | After every query: (fn [{:keys [file sql ms error]}] …) | `app.db/on-query` |

#### `:migrations`: The schema's changes: run by the start in development and tests, by migrate before it in production

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:migrations :schema-snapshot` | text or nil | The file development writes the whole schema to, or nil for none | `"schema.sql"` |

#### `:page`: The page shell every screen loads into

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:page :title` | text | The browser tab's title | `"Acme Orders"` |
| `:page :description` | text or nil | A one-sentence summary for search results and link previews, or nil | `"Orders and invoices for small shops"` |
| `:page :icon` | text | The tab's icon | `"/icon.svg"` |
| `:page :stylesheets` | list of text | The CSS files every page loads | `["/app.css"]` |
| `:page :head` | list of text | Extra tags at the end of <head>, as HTML text | `[]` |

#### `:browser`: The app's browser code and its build

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:browser :build` | keyword | The browser build's id | `:app` |
| `:browser :entry` | namespaced symbol | The app's browser entry: the function that calls groundwork.browser/start! | `app.browser/start` |
| `:browser :output-dir` | text | Where the compiled files go, a folder whose public/ is served | `"resources/public/js"` |
| `:browser :asset-path` | path | Where the browser fetches them | `"/js"` |
| `:browser :watch?` | true or false | In development, whether the start also watches the build | `true` |
| `:browser :modules` (optional) | map of modules | The build's modules, for an app with more than one: module → {:init-fn … or :entries […], :depends-on #{…}, :web-worker? …}. Without it there is one, :main, starting :entry | `{:base {:init-fn app.browser/start}, :admin {:entries [ap…` |
| `:browser :modules … :init-fn` (optional) | value |  |  |
| `:browser :modules … :entries` (optional) | list |  |  |
| `:browser :modules … :depends-on` (optional) | value |  |  |
| `:browser :modules … :web-worker?` (optional) | true or false |  |  |
| `:browser :loader?` (optional) | true or false | Whether modules no page names load on demand (shadow-cljs's module loader) | `true` |
| `:browser :options` (optional) | map | Extra shadow-cljs build options, merged over Groundwork's: JS resolution, build hooks, compiler options | `{:js-options {:js-provider :shadow}}` |

#### `:styles`: The app's stylesheets, named by its namespaces and bundled per browser module; nil or left out when the app brings its own through :page :stylesheets

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:styles :sources` | list of text | Where the browser code and its stylesheets are: every .css under them is named by a namespace's {:css […]}, and in development a saved one rebuilds the bundles | `["src"]` |
| `:styles :output-dir` | text | Where the bundles and their manifest go, a folder whose public/ is served | `"resources/public/css"` |
| `:styles :asset-path` | path | Where the browser fetches them | `"/css"` |
| `:styles :targets` | browserslist query | The browsers the stylesheets are built for, a browserslist query: lightningcss flattens nesting and writes vendor prefixes for them, in development and release; every build stops on a query browserslist doesn't know or that names no browser | `"safari >= 16.4, chrome >= 119"` |

#### `:web`: Requests: the app's routes, limits, hooks, languages

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:web :routes` | reitit routes | The app's routes, reitit data; each route's data is checked like below | `[["/" {:get groundwork.web/page, :access :public}] ["/ord…` |
| `:web :max-body-bytes` | positive whole number | The largest request body any route accepts (a route may allow more) | `1048576` |
| `:web :content-security-policy` | directive → sources | Sources the app adds to the strict policy, per directive | `{:img-src ["https://cdn.example"]}` |
| `:web :login-path` | path | Where a signed-out browser asking for a page is sent | `"/login"` |
| `:web :on-request` | function or nil | After every answer, for logs and metrics, or nil | `app.log/request` |
| `:web :on-error` | function or nil | An exception no handler caught, or nil | `app.log/error` |
| `:web :proxies` | map | Which addresses are trusted proxies, and the headers believed from them | `{:trusted ["10.0.0.0/8"], :client-ip-header "cf-connectin…` |
| `:web :proxies :trusted` | list of addresses or ranges | Trusted proxy addresses or ranges | `["10.0.0.0/8" "::1"]` |
| `:web :proxies :client-ip-header` | text or nil | The header holding the visitor's address, or nil | `"cf-connecting-ip"` |
| `:web :proxies :request-id-header` | text or nil | The header holding a request id to keep, or nil | `"x-request-id"` |
| `:web :languages` | map | The languages the app speaks, the one place a language is set: the page takes the request's; an app of one language lists just it | `{:supported ["en" "de"], :default "en", :cookie "lang"}` |
| `:web :languages :supported` | list of language codes | Language codes | `["en" "de"]` |
| `:web :languages :default` | language code | The language when nothing else says | `"en"` |
| `:web :languages :cookie` | name or nil | A cookie holding the person's choice, or nil | `"lang"` |
| `:web :test-sign-in` | map or nil | Lets automated tests sign in, given the secret; nil to switch off, never in production |  |
| `:web :test-sign-in :secret` | text, 16+ characters | At least 16 characters | `"a-secret-only-tests-know"` |
| `:web :hashed-files` | regular expression text or nil | Files whose names match (a regular expression, as text) are cached a year; nil for none | `"(?i)\\.[0-9a-f]{8,}\\."` |
| `:web :gzip-min-bytes` | positive whole number or nil | Text-like answers at least this big go gzipped; nil for none | `1024` |
| `:web :unknown-page` (optional) | function or nil | What a browser gets at an address no route knows: nil (or left out) for the page shell, whose browser routing may know it; or a handler, for an app whose pages are all routes (its own 404) | `app.pages/not-found` |

#### `:server`: The web server

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:server :ip` | text | The address to listen on | `"0.0.0.0"` |
| `:server :port` | port number | The port to listen on; 0 picks a free one | `8080` |

#### `:sessions`: Who is signed in

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:sessions :cookie-name` | name | The session cookie's name | `"acme_session"` |
| `:sessions :max-age-days` | positive whole number | A signed-in session's lifetime | `30` |
| `:sessions :visitor-hours` | positive whole number | A visitor's session ends after this long unseen | `24` |
| `:sessions :secure?` | true or false | Cookies only over https; false only for plain-http development | `true` |
| `:sessions :origin` | origin | Where the app is served from; a changing request must come from here | `"https://app.acme.example"` |

#### `:permissions`: Who may do what, where: the permission description

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:permissions :scopes` | kind → scope kind | The kinds of scope: where data belongs and people are members | `{:shop {:actions #:orders{:see #{:read}, :edit #{:read :u…` |
| `:permissions :scopes <scope kind> :actions` | action → commands | The actions of this kind of scope, each with what it does at the wall: :read, :insert, :update, :delete | `#:orders{:see #{:read}, :edit #{:read :update}}` |
| `:permissions :scopes <scope kind> :roles` | role name → actions | The starting roles every new scope of this kind gets: name → actions | `{"Manager" #{:orders/see :orders/edit}}` |
| `:permissions :scopes <scope kind> :parent` | keyword or nil | The kind of scope above this one, or nil |  |
| `:permissions :scopes <scope kind> :single?` (optional) | true or false | This kind has exactly one scope (a platform's back office): [:member <kind>] names it | `true` |
| `:permissions :scopes <scope kind> :table` (optional) | keyword | The app's own table of these scopes, where a scope's state lives (with :id) | `:shops` |
| `:permissions :scopes <scope kind> :id` (optional) | column keyword | Its id column, the scope's id | `:shop/id` |
| `:permissions :records` | kind → record kind | The kinds of record, and whose row each column names | `{:order {:table :orders, :id :order/id, :whose #:order{:s…` |
| `:permissions :records <record kind> :table` | keyword | Its table | `:orders` |
| `:permissions :records <record kind> :id` | column keyword | Its id column | `:order/id` |
| `:permissions :records <record kind> :whose` | column → whose | Whose row each column names: column → a scope kind, :person or :visitor (the id of a visitor's session) | `#:order{:shop-id :shop, :customer-id :person}` |
| `:permissions :records <record kind> :parent` (optional) | [kind column] or [kind column {column column}] | The record it hangs under: [record-kind its-column], and what it carries from it when either has more than one scope, or a person: [record-kind its-column {its column → the parent's}] | `[:order :job/order-id]` |
| `:permissions :rules` | list of sentences | Who may do what: one list of sentences, read at start (see groundwork.permissions.description) | `[{:allow :orders/see, :on :order, :to [:member :order/sho…` |
| `:permissions :rules […] :allow` (optional) | action or set of actions | Allows this action (or each of a set of them): a sentence {:allow … :on … :to-or-:via … :when …} | `:orders/see` |
| `:permissions :rules […] :on` (optional) | keyword | The kind of record it is on | `:order` |
| `:permissions :rules […] :to` (optional) | who | To whom: :anyone, :signed-in, [:member <column \| single-scope kind \| scope id> <action held, optional>], [:me <column>] | `[:member :order/shop-id]` |
| `:permissions :rules […] :via` (optional) | map | Or through a related row: one that exists for which its :to and :when hold | `{:table :agreements, :match #:order{:shop-id :agreement/s…` |
| `:permissions :rules […] :via :table` | keyword | The related row's table; its record kind says whose its columns are | `:agreements` |
| `:permissions :rules […] :via :match` | column → column | Which columns match: the row's column → the related row's | `#:order{:shop-id :agreement/shop-id}` |
| `:permissions :rules […] :via :to` | who | Who must hold on the related row | `[:member :agreement/partner-id :agreements/see]` |
| `:permissions :rules […] :via :when` (optional) | condition | While this holds on the related row | `[:= :agreement/status "active"]` |
| `:permissions :rules […] :when` (optional) | condition | Only while the row matches; [:none <scope column>] for a row that belongs to nobody | `[:= :order/status "open"]` |
| `:permissions :rules […] :deny` (optional) | action, set of actions or :writes | Or denies this action, these, or :writes (every action that writes), before any yes | `:money/move` |
| `:permissions :rules […] :session` (optional) | keyword | A deny's session flag | `:impersonated` |
| `:permissions :rules […] :scope` (optional) | keyword | A deny's scope kind, whose state (its :when on the kind's :table) denies | `:shop` |

#### `:limits`: How often a caller may ask

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:limits :public` | map or nil | What a public endpoint or route without its own limit gets, per caller each minute, or nil | `{:reads-per-minute 600, :writes-per-minute 60}` |
| `:limits :public :reads-per-minute` | positive whole number | Reads, and GET routes | `600` |
| `:limits :public :writes-per-minute` | positive whole number | Writes, and changing routes | `60` |
| `:limits :on-limit` | function or nil | Every caller turned away: (fn [{:keys [id limit caller]}] …), or nil | `app.log/limited` |

#### `:jobs`: Background jobs and schedules, or nil for none

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:jobs :kinds` | list of job maps | The app's job kinds, gathered from its areas | `[app.mail/jobs]` |
| `:jobs :schedules` | list of schedules | Jobs run on a clock, cron lines in UTC | `[{:job :app.reminders/send, :params {}, :cron "0 18 * * *"}]` |
| `:jobs :schedules … :job` | namespaced keyword | The job kind | `:app.reminders/send` |
| `:jobs :schedules … :params` | map | Its params | `{}` |
| `:jobs :schedules … :cron` | cron line | When: minute hour day-of-month month day-of-week, in UTC | `"0 18 * * *"` |
| `:jobs :workers` | positive whole number | Jobs running at once on this server | `4` |
| `:jobs :poll-ms` | positive whole number | How often to look for due jobs when nothing wakes the workers | `5000` |
| `:jobs :on?` | true or false | Whether this server runs jobs; false on previews, where they wait | `true` |

#### `:endpoints`: The endpoints and how they're asked (at the address /parquery)

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:endpoints :list` | list of endpoint maps | The app's endpoint maps, gathered from its areas | `[app.jobs/endpoints app.orders/endpoints]` |
| `:endpoints :max-asks` | positive whole number | The most asks one request may carry | `50` |
| `:endpoints :max-running` | positive whole number | The most asks running at once across the server; keep it below the pool size | `16` |
| `:endpoints :bearer` | function or nil | Whose token a tool calls with: (fn [token] person-id-or-nil), or nil | `app.tools/token->person` |
| `:endpoints :on-answer` | function or nil | After every ask over HTTP: (fn [{:keys [endpoint status ms person request-id]}] …), or nil | `app.metrics/on-answer` |
| `:endpoints :bells` | map | How the tables outside the permission description ring | `{:global {:countries "reference data only the platform's …` |
| `:endpoints :bells :global` | table → reason | Tables every watcher of them hears about: table → the reason. A global table is one whose rows belong to nobody, or whose every row is read by everyone; a table whose rows belong to a scope goes in the permission description | `{:countries "reference data only the platform's staff cha…` |
| `:endpoints :bells :personal` | table → column | Tables only their person hears about: table → person column | `{:notifications :notification/person-id}` |
| `:endpoints :bells :silent` (optional) | table → reason | Tables that ring nothing, on purpose: table → the reason. Every table of the app says how it rings, here or in the permission description, or the start refuses; :groundwork/every-other-table covers the rest of a database the app shares with something else | `{:import_log "written once by the nightly import, shown n…` |

#### A route's data (in `:web :routes`)

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `<path> :access` (optional) | :public, :signed-in or {:action … :record\|:scope\|:list …} | Who may use it, the shape an endpoint's :access has; a route checks :public, :signed-in or an action on a record (or :server-to-server? true) | `:public` |
| `<path> :server-to-server?` (optional) | true or false | Called by another server (a webhook): no session, no Origin check; the handler checks the call | `true` |
| `<path> :embeddable?` (optional) | true, :same-origin or origins | Other sites may show it in a frame: true (any https site), :same-origin, or a list of origins | `true` |
| `<path> :page` (optional) | map or function | The page shell's own details for this route: a map, or a function of the request | `{:title "Design your part"}` |
| `<path> :max-body-bytes` (optional) | positive whole number | The largest body it accepts, over the app's :max-body-bytes | `104857600` |
| `<path> :rate-limit` (optional) | {:per-minute n :per-day n :by …} | How often a caller may use it, counted by :person or :client-ip (see groundwork.limits) | `{:per-minute 10, :by :client-ip}` |

#### An endpoint's entry (in `:endpoints :list`)

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `<endpoint name> :read` (optional) | function | Its handler, for a read: (fn [ctx params] answer) | `app.jobs/list` |
| `<endpoint name> :write` (optional) | function | Its handler, for a write: (fn [ctx params] answer) | `app.jobs/move` |
| `<endpoint name> :access` | :public, :signed-in or {:action … :record\|:scope\|:list …} | Who may ask it: :public, :signed-in, or an action on a record, a scope or a list (see groundwork.endpoints) | `{:action :jobs/see, :scope [:shop :shop/id]}` |
| `<endpoint name> :input` | malli shape | What may be sent, a malli :map; id fields say their record kind | `[:map [:shop/id :uuid]]` |
| `<endpoint name> :answer` | malli shape | What may be sent back, a malli shape | `[:vector [:map [:job/id :uuid]]]` |
| `<endpoint name> :reads` (optional) | list of tables | Tables a read uses that Postgres can't see in its SQL | `[:jobs]` |
| `<endpoint name> :rate-limit` (optional) | {:per-minute n :per-day n :by …} | How often a caller may ask it (see groundwork.limits) | `{:per-day 20, :by :person}` |
| `<endpoint name> :keeps-for-visitor?` (optional) | true or false | A write that keeps something for someone not signed in: a visitor without a session gets one, so the row can be theirs | `true` |
<!-- end of the settings reference -->

---

## File layout

An area's plain file is its door, the namespace an app requires; its folder
holds its machinery, one job per file.

```
src/deps.cljs                     the npm packages the browser half needs
src/groundwork/
  core.clj                        starting and stopping an app, and production's migrate
  settings.clj                    the settings' schemas and their checks
  settings/docs.clj               the settings reference, the example file, explain-setting
  postgres.clj                    the pool, transactions, .sql files as vars, rows
  postgres/sql.clj                a .sql file's :name params made a JDBC statement
  postgres/names.clj              one name from the column to the browser
  migrations.clj                  the schema's changes (run at start in development, by migrate in production); the snapshot
  migrations/roles.clj            production's roles: the app's role checked, migrate's grants
  migrations/installs.clj         what Groundwork installed, by its fingerprint
  jobs.clj                        background jobs and schedules
  permissions.clj                 who may do what: the door
  permissions/description.clj     the description, read and checked
  permissions/sql.clj             the sentences as SQL: the wall, vias, may functions, fills
  permissions/check.clj           checking a request, grants, the bells' reach, why
  permissions/members.clj         scopes, roles and members, written
  permissions/install.clj         the database checked and the wall put up (or, in production, compared)
  permissions/leaks.clj           the leak test
  sessions.clj                    who is signed in, and visitors
  limits.clj                      how often a caller may ask
  endpoints.clj                   the endpoints: checked at start, run per ask
  endpoints/route.clj             /parquery, and the transport part
  endpoints/http.clj              the asks over HTTP, server half
  endpoints/http.cljs             the asks over HTTP, browser half
  endpoints/websocket.clj         the lines that carry the bells, server half
  endpoints/websocket.cljs        the line that hears the bells, browser half
  endpoints/bells.clj             what rings and what each read waits for
  endpoints/wire.cljc             the wire: shapes, JSON, ids, dates, decimals
  endpoints/store.cljs            the browser's answers, kept by question
  web.clj                         the router: routes, access, rate limits, Groundwork's routes
  web/http.clj                    headers, CSP, frames, request ids, hooks, gzip, HEAD, no indexing
  web/files.clj                   static files, caching, ranges, sniff-type
  web/body.clj                    request bodies, forms and uploads
  web/languages.clj               the request's language
  web/page.clj                    the page shell
  web/server.clj                  http-kit
  browser.cljs                    the browser half's start, and the door to routing
  browser/routing.cljs            the browser's routing engine
  browser/loader.cljs             modules loaded on demand, their stylesheets first
  build.clj                       the browser build
  build/styles.clj                the stylesheets' check and bundles
  repl.clj                        the REPL's tools for a running app
  repl/dev.clj                    the development start: nREPL, watcher, .mcp.json, log, serve
  repl/browser.cljs               the browser's REPL: the store's contents
```

Beside it: `resources/groundwork/` holds Groundwork's own `.sql` files and
migrations, `resources/clj-kondo.exports/` the lint config an app imports,
`docs/` the example settings and the development service unit, `test/` the
tests and an example app (`test/example/hello`). Every push runs the tests
on GitHub ([`.github/workflows/tests.yml`](.github/workflows/tests.yml)): the
JVM tests against Postgres 18, clj-kondo, and the browser half's tests on node.
