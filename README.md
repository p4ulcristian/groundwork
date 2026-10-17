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
| endpoints         | `groundwork.endpoints` | built |
| parquery          | `groundwork.parquery.*` | built: HTTP and the bells |
| parquery, browser | `groundwork.parquery.*`, `groundwork.parquery-store` (cljs) | built on re-frame |
| server            | `groundwork.server`   | built on http-kit |
| router            | `groundwork.web`      | built on reitit |
| migrations        | `groundwork.migrations` | built |
| sessions          | `groundwork.sessions` | built  |
| permissions       | `groundwork.permissions` | built  |
| page shell        | `groundwork.page`     | built  |
| styles            | `groundwork.styles`, `groundwork.loader` (cljs) | built: a bundle per module, the check, lightningcss |
| REPL              | `groundwork.repl` (clj and cljs) | built |
| background jobs   | `groundwork.jobs`     | built ([#1](https://github.com/p4ulcristian/groundwork/issues/1)) |

Java 25 or newer. Every decision and its reason: [`decisions/`](decisions/).
Every push runs the tests on GitHub ([`.github/workflows/tests.yml`](.github/workflows/tests.yml)):
the JVM tests against Postgres 18, clj-kondo, the browser half's tests on node.

---

## Starting an app

```clojure
(def app (gw/start {:environment :development
                    :db          {…}                          ; see Postgres, Start
                    :migrations  {:schema-snapshot "schema.sql"}
                    :page        {:lang "en" :title "Acme Orders" :description nil
                                  :icon "/icon.svg" :stylesheets ["/app.css"] :head []}
                    :browser     {:build :app :entry 'app.browser/start      ; see In the browser
                                  :output-dir "resources/public/js" :asset-path "/js"
                                  :watch? true}
                    :styles      {…}                          ; see Styles; nil or left out: the app brings its own CSS
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
                                  :test-sign-in            nil                ; or {:secret …}, never in production
                                  :eval-bridge             nil                ; or {:secret … :timeout-ms … :max-chars …}, never in production
                                  :hashed-files            "(?i)\\.[0-9a-f]{8,}\\."   ; or nil
                                  :gzip-min-bytes          1024}               ; or nil
                    :server      {:ip "0.0.0.0" :port 8080}
                    :sessions    {:cookie-name "acme_session" :max-age-days 30 :visitor-hours 24
                                  :secure? true :origin "https://app.acme.example"}
                    :permissions {…}                          ; see Permissions
                    :limits      {:public {:reads-per-minute 600 :writes-per-minute 60}   ; see Limits
                                  :on-limit nil}
                    :parquery    {:endpoints [app.jobs/endpoints …]   ; see Endpoints
                                  :max-asks 50 :max-running 16
                                  :bearer nil
                                  :bells {:global {} :personal {} :silent {}}}}))
(gw/stop app)
```

- One key per part, every key required (but `:styles`, which an app
  without Groundwork's styles leaves out); an unknown key throws.
- Every problem is reported at once, each with its place and a plain
  message: `:sessions :visitor-hours must be a positive whole number, got "24"`.
- [`docs/example-settings.edn`](docs/example-settings.edn) holds every key with
  an example and what it is; `(repl/explain-setting [:sessions :visitor-hours])`
  answers in the REPL. Both, and the reference below, come from one malli
  schema, `groundwork.settings`.
- Inside, Integrant starts the parts in order and stops them in reverse.
  That wiring is Groundwork's own: settings don't name or replace parts.
- Tests can replace a part: `(gw/start-with settings {:groundwork/db fake})`.
- In the REPL, `(gw/swap-routes! app routes)` serves new routes without a
  restart; they're checked like at start, and wrong ones leave the old in
  place.


### Settings reference

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
| `:db :user` | text | The user Groundwork connects as | `"app"` |
| `:db :password` | text or nil | Its password, or nil for none |  |
| `:db :pool-size` | positive whole number | Connections per server, a fixed number | `10` |
| `:db :statement-timeout-ms` | positive whole number or :none | Postgres cancels a statement that runs longer; :none for no limit | `30000` |
| `:db :time-zone` | time zone name | The session's time zone: how times without an offset are read | `"UTC"` |
| `:db :on-transaction` (optional) | function | Runs first in every with-transaction: (fn [tx] …) | `app.db/on-transaction` |
| `:db :on-query` (optional) | function | After every query: (fn [{:keys [file sql ms error]}] …) | `app.db/on-query` |

#### `:migrations`: The schema's changes, run at start

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:migrations :schema-snapshot` | text or nil | The file development writes the whole schema to, or nil for none | `"schema.sql"` |

#### `:page`: The page shell every screen loads into

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:page :lang` | text | The page's language | `"en"` |
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
| `:browser :modules` (optional) | map of modules | The build's modules, for an app with more than one: module → {:init-fn … or :entries […], :depends-on #{…}}. Without it there is one, :main, starting :entry | `{:base {:init-fn app.browser/start}, :admin {:entries [ap…` |
| `:browser :modules … :init-fn` (optional) | value |  |  |
| `:browser :modules … :entries` (optional) | list |  |  |
| `:browser :modules … :depends-on` (optional) | value |  |  |
| `:browser :modules … :web-worker` (optional) | true or false |  |  |
| `:browser :loader?` (optional) | true or false | Whether modules no page names load on demand (shadow-cljs's module loader) | `true` |
| `:browser :options` (optional) | map | Extra shadow-cljs build options, merged over Groundwork's: JS resolution, build hooks, compiler options | `{:js-options {:js-provider :shadow}}` |

#### `:styles`: The app's stylesheets, named by its namespaces and bundled per browser module; nil or left out when the app brings its own through :page :stylesheets

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:styles :sources` | list of text | Where the browser code and its stylesheets are: every .css under them is named by a namespace's {:css […]}, and in development a saved one rebuilds the bundles | `["src"]` |
| `:styles :output-dir` | text | Where the bundles and their manifest go, a folder whose public/ is served | `"resources/public/css"` |
| `:styles :asset-path` | path | Where the browser fetches them | `"/css"` |

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
| `:web :languages` | map or nil | The languages the app speaks, or nil for one | `{:supported ["en" "de"], :default "en", :cookie "lang"}` |
| `:web :languages :supported` | list of language codes | Language codes | `["en" "de"]` |
| `:web :languages :default` | language code | The language when nothing else says | `"en"` |
| `:web :languages :cookie` | name or nil | A cookie holding the person's choice, or nil | `"lang"` |
| `:web :test-sign-in` | map or nil | Lets automated tests sign in, given the secret; nil to switch off, never in production |  |
| `:web :test-sign-in :secret` | text, 16+ characters | At least 16 characters | `"a-secret-only-tests-know"` |
| `:web :eval-bridge` | map or nil | Evaluates code from outside, given the secret; nil to switch off, never in production |  |
| `:web :eval-bridge :secret` | text, 16+ characters | At least 16 characters | `"a-secret-only-the-previews-know"` |
| `:web :eval-bridge :timeout-ms` | positive whole number | How long one evaluation may run | `30000` |
| `:web :eval-bridge :max-chars` | positive whole number | The longest result sent back | `20000` |
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
| `:permissions :scopes` | kind → scope kind | The kinds of scope: where data belongs and people are members | `{:shop {:actions #{:orders/see :orders/edit}, :roles {"Ma…` |
| `:permissions :scopes <scope kind> :actions` | set of actions | The actions of this kind of scope | `#{:orders/see :orders/edit}` |
| `:permissions :scopes <scope kind> :roles` | role name → actions | The starting roles every new scope of this kind gets: name → actions | `{"Manager" #{:orders/see :orders/edit}}` |
| `:permissions :scopes <scope kind> :parent` | keyword or nil | The kind of scope above this one, or nil |  |
| `:permissions :records` | kind → record kind | The kinds of record, each in a scope | `{:order {:table :orders, :id :order/id, :in [:shop :order…` |
| `:permissions :records <record kind> :table` | keyword | Its table | `:orders` |
| `:permissions :records <record kind> :id` | column keyword | Its id column | `:order/id` |
| `:permissions :records <record kind> :in` | [kind column] or a list of them | Its scope column: [scope-kind column], or a list of them for a record shared by scopes | `[:shop :order/shop-id]` |
| `:permissions :records <record kind> :parent` (optional) | [kind column] or [kind column {column column}] | The record it hangs under: [record-kind its-column], and what it carries from it when either has more than one scope, or a person: [record-kind its-column {its column → the parent's}] | `[:order :job/order-id]` |
| `:permissions :records <record kind> :people` (optional) | action → column | An action a person on the record holds: action → their column | `#:orders{:see :order/customer-id}` |
| `:permissions :records <record kind> :visitors` (optional) | action → column | An action a visitor on the record holds: action → the column with their visitor session's id. At sign-in the row moves to the person, into the kind's person column | `#:designs{:edit :design/visitor-id}` |
| `:permissions :records <record kind> :only-when` (optional) | action → condition | An action allowed only when the record matches: action → condition | `#:orders{:edit [:= :order/status "open"]}` |
| `:permissions :records <record kind> :open` (optional) | set of actions | The open actions that reach this kind (each is in the description's :open); its :only-when for them still holds | `#{:catalogue/see}` |
| `:permissions :links` | list of links | Scopes reaching other scopes through a linking record | `[]` |
| `:permissions :links […] :table` | keyword | The linking record's table | `:agreements` |
| `:permissions :links […] :from` | [kind column] | The scope the person is in: [kind column] | `[:partner :agreement/partner-id]` |
| `:permissions :links […] :to` | [kind column] | The scope the link reaches: [kind column] | `[:shop :agreement/shop-id]` |
| `:permissions :links […] :when` | condition | While this holds | `[:= :agreement/status "active"]` |
| `:permissions :links […] :grants` | action → action | What it gives in :to: given action → the action needed in :from | `#:orders{:see :agreements/see}` |
| `:permissions :passes` | list of passes | Rows that are a pass to one record: a reading action on the record a row names, for the receiving scope's people or one person, while a condition holds | `[]` |
| `:permissions :passes […] :table` | keyword | The passing row's table; it is a record kind itself, in the giving and the receiving scope | `:handoffs` |
| `:permissions :passes […] :record` | [kind column] | The one record it gives: [record-kind column] | `[:order :handoff/order-id]` |
| `:permissions :passes […] :from` | [kind column] | The giving scope, the record's own: [kind column] | `[:shop :handoff/from-shop-id]` |
| `:permissions :passes […] :to` (optional) | [kind column] | The receiving scope: [kind column] | `[:shop :handoff/to-shop-id]` |
| `:permissions :passes […] :to-person` (optional) | column keyword | Or the receiving person's column | `:handoff/driver-id` |
| `:permissions :passes […] :when` | condition | While this holds | `[:= :handoff/status "open"]` |
| `:permissions :passes […] :grants` | action → action, or a set of actions | The reading actions it gives on the record → the action needed in :to; for :to-person, a set of given actions | `#:orders{:see :handoffs/work}` |
| `:permissions :passes […] :under` | set of kinds | The record kinds right under the record that come with it | `#{:job}` |
| `:permissions :open` | map | The open actions, the one list of them: reading actions anyone, or anyone signed in, may do without a membership. No role holds one; each reaches only the record kinds that list it | `{:anyone #{:catalogue/see}, :signed-in #{:shops/name}}` |
| `:permissions :open :anyone` | set of actions | Open to everyone, signed in or not | `#{:catalogue/see}` |
| `:permissions :open :signed-in` | set of actions | Open to everyone who is signed in | `#{:shops/name}` |
| `:permissions :session-blocks` | flag → actions | A session flag blocks actions: flag → actions | `{:impersonated #{:money/move}}` |

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

#### `:parquery`: The endpoints and how they're asked

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `:parquery :endpoints` | list of endpoint maps | The app's endpoint maps, gathered from its areas | `[app.jobs/endpoints app.orders/endpoints]` |
| `:parquery :max-asks` | positive whole number | The most asks one request may carry | `50` |
| `:parquery :max-running` | positive whole number | The most asks running at once across the server; keep it below the pool size | `16` |
| `:parquery :bearer` | function or nil | Whose token a tool calls with: (fn [token] person-id-or-nil), or nil | `app.tools/token->person` |
| `:parquery :on-answer` | function or nil | After every ask over HTTP: (fn [{:keys [endpoint status ms person request-id]}] …), or nil | `app.metrics/on-answer` |
| `:parquery :bells` | map | How the tables outside the permission description ring | `{:global {:countries "reference data only the platform's …` |
| `:parquery :bells :global` | table → reason | Tables every watcher of them hears about: table → the reason. A global table is one whose rows belong to nobody, or whose every row is read by everyone; a table whose rows belong to a scope goes in the permission description | `{:countries "reference data only the platform's staff cha…` |
| `:parquery :bells :personal` | table → column | Tables only their person hears about: table → person column | `{:notifications :notification/person-id}` |
| `:parquery :bells :silent` (optional) | table → reason | Tables that ring nothing, on purpose: table → the reason. Every table of the app says how it rings, here or in the permission description, or the start refuses; :groundwork/every-other-table covers the rest of a database the app shares with something else | `{:import_log "written once by the nightly import, shown n…` |

#### A route's data (in `:web :routes`)

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `<path> :access` (optional) | :public, :signed-in or {:action … :record [kind param]} | Who may use it (or :server-to-server true) | `:public` |
| `<path> :server-to-server` (optional) | true or false | Called by another server (a webhook): no session, no Origin check; the handler checks the call | `true` |
| `<path> :embeddable` (optional) | true, :same-origin or origins | Other sites may show it in a frame: true (any https site), :same-origin, or a list of origins | `true` |
| `<path> :page` (optional) | map or function | The page shell's own details for this route: a map, or a function of the request | `{:title "Design your part"}` |
| `<path> :max-body-bytes` (optional) | positive whole number | The largest body it accepts, over the app's :max-body-bytes | `104857600` |
| `<path> :rate-limit` (optional) | {:per-minute n :per-day n :by …} | How often a caller may use it, counted by :person or :client-ip (see groundwork.limits) | `{:per-minute 10, :by :client-ip}` |

#### An endpoint's entry (in `:parquery :endpoints`)

| Key | Type | What it is | Example |
|-----|------|------------|---------|
| `<endpoint name> :read` (optional) | function | Its handler, for a read: (fn [ctx params] answer) | `app.jobs/list` |
| `<endpoint name> :write` (optional) | function | Its handler, for a write: (fn [ctx params] answer) | `app.jobs/move` |
| `<endpoint name> :permission` | :public, :signed-in or {:action … :record\|:scope\|:list …} | Who may ask it | `{:action :jobs/see, :scope [:shop :shop/id]}` |
| `<endpoint name> :input` | malli shape | What may be sent, a malli :map; id fields say their record kind | `[:map [:shop/id :uuid]]` |
| `<endpoint name> :answer` | malli shape | What may be sent back, a malli shape | `[:vector [:map [:job/id :uuid]]]` |
| `<endpoint name> :reads` (optional) | list of tables | Tables a read uses that Postgres can't see in its SQL | `[:jobs]` |
| `<endpoint name> :rate-limit` (optional) | {:per-minute n :per-day n :by …} | How often a caller may ask it (see groundwork.limits) | `{:per-day 20, :by :person}` |
| `<endpoint name> :keeps-for-visitor` (optional) | true or false | A write that keeps something for someone not signed in: a visitor without a session gets one, so the row can be theirs | `true` |
<!-- end of the settings reference -->

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
  (`nil` for none), when `pg_dump` is on the PATH. It is written when the
  whole start is done, after everything Groundwork installs in the database
  (the wall's policies, the rules that fill a row's scope, the bells'
  triggers): it shows the database as it runs, a changed permission rule
  shows as a difference in it, and a database's first start writes the same
  file as every later one.
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
  A file whose name carries its content's hash (it matches `:hashed-files`,
  a regular expression as text) is cached for a year, since its name changes
  whenever it does.
- **gzip:** text-like answers (HTML, CSS, JavaScript, JSON, SVG…) of at
  least `:gzip-min-bytes` go gzipped to a browser that accepts it; images,
  video, archives and fonts are compressed already and go as they are.
- **Health:** `GET /health` answers 200 when the database answers, 503
  when it doesn't, for a load balancer or a monitor.
- **HEAD** is answered for every GET route, like GET without the body.
- **Outside production** `/robots.txt` disallows everything and every
  answer carries `X-Robots-Tag: noindex`, so a staging copy never shows up
  in search. In production the app's own `robots.txt` is served.
- **The page:** any other page a browser asks for gets the page shell, and
  the app's routing in the browser takes over. It loads `:script` into
  `<div id="app">`, and is never cached. An app whose pages are all routes
  says what an unknown address gets instead, `:web :unknown-page`, a
  handler: its own 404, `(assoc (web/page request {:title "Not found"}) :status 404)`.
  With `:styles`, the page links the stylesheet bundles of its modules
  (see Styles), before the app's own `:stylesheets`.
- **A page per route:** a route's `:page`, a map or a function of the
  request, is merged over the app's: its own `:lang :title :description`,
  `:canonical`, `:alternates` (hreflang links), extra `:stylesheets
  :scripts :head`, and `:body`, HTML the server writes inside
  `<div id="app">` for search engines and the first paint.

```clojure
["/shops/:slug" {:get    web/page
                   :access :public
                   :page (fn [request] {:title (str "Shop " …) :body (render-shop …)})}]
```

- **A handler that has to look first** (a record that may not exist) gives
  the page itself, and may change the answer:
  `(assoc (web/page request {:title "No such shop"}) :status 404)`.
- **Security headers** on every answer: no sniffing, no framing, a strict
  referrer; in production also Strict-Transport-Security.
- **A strict Content-Security-Policy:** scripts and styles only from the
  app's own origin or carrying the request's nonce, which the page shell
  stamps on every `<script>` and `<style>` it writes (the app's `:head` and
  `:body` included; `web/nonce` for a handler writing its own HTML). Style
  attributes stay allowed, since components set them. The app adds sources
  per directive: `:content-security-policy {:script-src ["https://js.payments.example"]
  :frame-src […] :connect-src […]}`. Development also allows eval and
  websockets, which its build needs (code arrives as text, and the watcher
  tells the page about a save over a websocket on its own port).

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
- A server-to-server route has no session part. One that signs someone in
  itself (an app's own test login, proven by a secret) puts it on:
  `(sessions/sign-in (sessions/on-request request (gw/sessions (gw/current))) response person-id)`.
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
           :job   {:table :jobs :id :job/id :in [:shop :job/shop-id]  ; its own scope column
                   :parent [:order :job/order-id]}}                    ; the record it hangs under
 :links   [{:table :agreements :from [:partner :agreement/partner-id] :to [:shop :agreement/shop-id]
            :when [:= :agreement/status "active"] :grants {:orders/see :agreements/see}}]
 :passes  [{:table :handoffs :record [:order :handoff/order-id]     ; a row that is a pass to one record
            :from [:shop :handoff/from-shop-id] :to [:shop :handoff/to-shop-id]
            :when [:= :handoff/status "open"] :grants {:orders/see :handoffs/work} :under #{:job}}]
 :open    {:anyone #{:catalogue/see} :signed-in #{}}                ; the open actions, one list
 :session-blocks {:impersonated #{:money/move}}}
```

```clojure
(perm/allowed? perms {:person p :flags #{} :action :jobs/move :record [:job id]})
(perm/check! perms {… :also {:machine [machine-id]}})   ; every id; else not-found
(perm/list-filter perms {:person p :flags #{} :action :orders/see :kind :order})
(perm/in-wall perms {:person p :scopes #{shop-id} :action :orders/see :kind :order} (fn [tx] …))
(perm/as-system perms (fn [tx] …))
```

- Groundwork keeps scopes, roles, role actions, memberships, extra actions
  and invitations in the `groundwork` schema. Changes count on the next
  check; nothing is cached.
- A record is checked through its own scope column, never a scope the
  browser names. Another scope's record is not-found.
- **Every scoped table names its scope,** however deep: a job keeps its
  shop, not only its order. A record under another record names the parent,
  and the database ties them with a matching link, so the two can't differ:
  the child's (parent, scope) references the parent's (id, scope),
  `ON UPDATE CASCADE`, and a parent moving to another scope takes its
  children along. At start Groundwork installs a rule that fills the scope
  from the parent when a row is inserted without one, and refuses to start
  when a scope column is missing or may be empty, or a link is missing or
  doesn't cascade; the error carries the SQL to add.
- **A record of two scopes may have a parent and children.** What a record
  carries from its parent is a map, its column → the parent's (left out
  when both have one scope):

  ```clojure
  ;; both scopes from the parent: a price sheet under an agreement between a shop and a partner
  :price-sheet {:table :price_sheets :id :price-sheet/id
                :in [[:shop :price-sheet/shop-id] [:partner :price-sheet/partner-id]]
                :parent [:agreement :price-sheet/agreement-id
                         {:price-sheet/shop-id    :agreement/shop-id
                          :price-sheet/partner-id :agreement/partner-id}]}
  ;; one from the parent, one of its own: a service on an order names the partner who does it
  :order-service {:table :order_services :id :order-service/id
                  :in [[:shop :order-service/shop-id] [:partner :order-service/partner-id]]
                  :parent [:order :order-service/order-id {:order-service/shop-id :order/shop-id}]}
  ```

  Every carried column is filled from the parent at insert, tied to it by
  a matching link and follows it; a carried scope column is NOT NULL on
  both sides. A scope column that is the record's own may wait empty.
  Either side sees the row and is rung for it, and nobody else; seeing it
  gives nothing else (the partner who sees a service on an order doesn't
  see the order). A foreign key holding several carried columns counts
  while none of them can be empty.
- **A record between fixed sides carries them.** A record shared by a
  small, fixed set of sides that all read and write it (a conversation
  between a customer, a shop, a partner and the platform's staff) has a
  scope column per scope side and a person column for the person; a side
  that is "the platform's staff" is the platform's own scope. It is an
  ordinary record of several scopes. **A person column is carried down
  like a scope column:** the records under it name the person in what they
  carry (`{:message/customer-id :conversation/customer-id}`), and it is
  filled, tied and follows the same way. A person column may be empty (the
  row is then nobody's through it), in a foreign key of its own two
  columns; a person named on the parent later reaches the rows already
  under it.
- A membership in a parent scope counts in its children; an owner holds
  everything; a link gives access while its condition holds; a person on
  the record, a condition on its own columns and session flags are part of
  the rule.
- Each rule is one SQL condition: it checks one record and filters a list.
- The description is checked at start, against the database too.
- **The wall:** every record table gets forced row-level security at start.
  A transaction sees only its verified scopes and the person's own records,
  or everything as the system. Both last one transaction and can't nest.
  The app must connect as a role that doesn't pass by row-level security:
  a superuser or a role with BYPASSRLS (a hosted database often makes its
  owner one) refuses the start, because no policy would apply to it.
- **The wall knows the action.** An endpoint's transaction carries its
  action and the kind of record it's about, so a list's SQL needs no
  permission `WHERE`: that kind's rows show only as the action's rule allows
  (a link and its condition, an only-when, a person column), and every
  other kind only where the person is a member. What a link gives is the
  linked action on the endpoint's own kind, nothing else of that scope; a
  customer asking for their order sees no other. A write leaves only-when
  out for the rows it changes: its own record was checked before it ran.
  Links are read through functions Groundwork installs at start, so the
  database's rules never loop through the tables behind them.
- **An action can be open:** to anyone, or to anyone signed in. It needs
  no membership and only reads. The description's `:open` is the one list of
  them, and a record kind lists the open actions that reach it:

  ```clojure
  :open    {:anyone #{:catalogue/see} :signed-in #{}}
  :records {:package {:table :packages :id :package/id :in [:shop :package/shop-id]
                      :open      #{:catalogue/see}
                      :only-when {:catalogue/see [:= :package/published true]}}}
  ```

  An endpoint declares it like any action, about a `:scope` or a `:record`
  (`{:action :catalogue/see :scope [:shop :shop/id]}`), and must be a
  `:read`; open to anyone, it is asked signed out too. The scope it names is
  verified for that one request, and the wall shows the caller only the
  kinds that list the action, as their `:only-when` allows: the shop's
  published packages, none of its orders. A member of the shop still sees
  the other kinds as a member, and a person their own records. No role holds
  an open action and it can't be granted; a write, a `:list` of every
  scope's, a link or an id field of a kind it doesn't reach refuses the
  start. A watch on such a read holds the scope, so a visitor on one shop's
  page hears that shop's bell, for the tables the action reaches, and no
  other shop's.
- **Shared rows have an owner.** A row every scope reads (the platform's
  own categories next to each shop's) belongs to a real scope, the one
  whose people keep it, never to no scope: an empty scope column means
  "nobody's yet" everywhere. Its people edit it as members, through the
  wall; everyone else reads it through an open action in that scope. An
  endpoint names "the scope in the request, and always also this fixed
  one", and one read returns both:

  ```clojure
  :permission {:action :catalogue/see :scope [:shop :shop/id] :and-scope [:shop platform-id]}
  ```

  Both scopes must verify for the action (for an open action both do; for
  one people hold, the person must hold it in both), or the ask is not
  found. The watch holds both.
- **A row can be a pass to one record.** A link gives an action in the
  whole linked scope; a pass gives a reading action on the one record its
  row names, while its condition holds:

  ```clojure
  :passes [{:table  :handoffs                      ; the row is the pass; nothing else is stored
            :record [:order :handoff/order-id]     ; the one record it gives
            :from   [:shop :handoff/from-shop-id]  ; the giving scope: the record's own
            :to     [:shop :handoff/to-shop-id]    ; the receiving scope, or :to-person <column>
            :when   [:= :handoff/status "open"]
            :grants {:orders/see :handoffs/work}   ; given on the record → needed in :to
            :under  #{:job}}]                      ; kinds right under the record that come with it
  ```

  The receiving scope's own roles decide who uses it (the needed action);
  for `:to-person`, `:grants` is a set of actions. The holder's other
  requests for the giving scope's records answer not-found, and when the
  condition stops holding, or the row is gone, so is the access. **A pass
  only reads:** a write that declares the given action gets nothing from
  it. **A scope can't write itself a pass:** the pass's table is a record
  kind of its own, in the giving and the receiving scope; a foreign key
  ties its (record, giver) to the record's own (id, scope), checked at
  start; and a guard Groundwork installs lets only the giving scope's
  people (or the system) insert a pass, point one at another record, giver
  or receiver, or make its condition hold again. A transaction about the
  record also sees the `:under` kinds' rows the pass gives. Bells: a change
  to the record rings its own scope and whoever holds a pass on it, and a
  pass made or ended rings the reads of the records it gives. Index the
  pass table's receiver column: passes are found by it.
- **A record can belong to a visitor,** someone not signed in: a design,
  an upload, a cart made before the login. A record kind names a visitor
  column beside, or instead of, its person column:

  ```clojure
  :design {:table :designs :id :design/id :in [:shop :design/shop-id]
           :people   {:designs/mine :design/customer-id}
           :visitors {:designs/mine :design/visitor-id}}
  ```

  The column holds the id of the visitor's session, which is Groundwork's
  own: the app doesn't make one and the browser never sends it. An endpoint
  gets it as `(:visitor ctx)` (nil for someone signed in, or with no
  session), and an action a kind gives a visitor may be asked signed out.
  The wall shows the row to that visitor, to read and to change, and to
  nobody else. A write that keeps something for a visitor says
  `:keeps-for-visitor true`: asked without a session, the visitor gets one
  first, and its cookie goes out with the answer. **At sign-in Groundwork
  moves the visitor's rows to the person** (`sessions/sign-in`): in every
  table with a visitor column the person is set and the visitor emptied, no
  hook to write; a kind with no person column keeps its rows. When the
  visitor's session has ended, its rows are nobody's: only the system sees
  them, and the app's cleanup may remove them. A visitor's own line hears of
  their rows.
- **Team:** `create-scope!`, `invite!`, `accept-invitation!`,
  `change-role!`, `create-role!`, `set-role-actions!`, `remove-member!`,
  `grant-extra!`, `revoke-extra!`. Managing needs `:groundwork/team`, and
  only actions the manager holds can be given; only an owner makes owners;
  a scope always keeps one.

---

## 7. Endpoints

Everything the browser, or a tool, can ask. The app lists them by area:

```clojure
(def endpoints
  {:jobs/of-shop {:read       #'jobs/of-shop                         ; (fn [ctx params] answer)
                  :permission {:action :orders/see :scope [:shop :shop/id]}
                  :input      [:map [:shop/id :uuid]]
                  :answer     [:vector job]}
   :jobs/move    {:write      #'jobs/move
                  :permission {:action :jobs/move :record [:job :job/id]}
                  :input      [:map [:job/id :uuid]
                                    [:machine/id {:groundwork/record :machine} :uuid]]
                  :answer     job}})
```

- Every endpoint declares its handler (`:read` or `:write`), `:permission`,
  `:input` and `:answer`, or the app doesn't start; a name in two areas
  doesn't start either.
- `:permission`: `:public`, `:signed-in`, or an action on one record
  (`:record`), on a scope (`:scope`, with every scope below it) or on every
  record of a kind the person may (`:list`).
- Every input field holding ids says which kind of record they are
  (`{:groundwork/record :machine}`) or that they aren't one
  (`{:groundwork/record false}`); each id must belong to the verified
  scopes, or the answer is not-found.
- A read runs read-only; a write commits only when its answer fits its
  shape. Both run behind the wall, on the verified scopes.
- A write with a one-time key runs once; a repeat gets the first answer.
- Answers: `:ok`, `:invalid` (the input shape's errors), `:signed-out`,
  `:not-found`, `:refused` (`(endpoints/refuse "message")`), `:too-many`,
  `:error` (a plain message and the request id; details to `:on-error`).
- `(endpoints/call part ask)` runs one; `(endpoints/call-with-token part token
  ask)` for a tool, through `:bearer`.
- `:groundwork/grants` is built in: every action the person holds, and
  where, for the browser's "can I?".

### The team endpoints

A scope's people and roles, for the app's own screens (Groundwork ships no
UI). The app adds them to its list:
`:parquery {:endpoints [groundwork.team/endpoints app.jobs/endpoints …]}`.

| Endpoint | | |
|---|---|---|
| `:groundwork.team/members` | read | who is in the scope: role, owner, extra actions |
| `:groundwork.team/roles` | read | the roles and their actions (the roles grid) |
| `:groundwork.team/invite` | write | an email with a role → `{:invitation/token …}` for the link the app mails |
| `:groundwork.team/accept` | write | the signed-in person joins; needs `:person/email` in the session |
| `:groundwork.team/change-role` | write | a member gets another role |
| `:groundwork.team/set-role-actions` | write | a role's actions (ticks in the grid) |
| `:groundwork.team/create-role` | write | a new role with its actions |
| `:groundwork.team/remove-member` | write | a person leaves the scope |
| `:groundwork.team/grant-extra`, `revoke-extra` | write | one action on top of a role |

- All but accept need `:groundwork/team` in the scope named by
  `:team/scope-id`; another scope's id answers not-found. Every action
  handed out or taken back must be one the person holds; only an owner makes
  or removes owners, and a scope always keeps one.
- The app's login keeps the person's email in the session as
  `:person/email` (`sessions/put-data`), so an invitation can be accepted only
  by the address it was sent to.
- They run outside the wall: Groundwork's team functions check, then open
  their own transactions as the system.


### Limits

How often a caller may ask (`groundwork.limits`), for endpoints and plain
routes alike:

```clojure
:limits {:public   {:reads-per-minute 600 :writes-per-minute 60}   ; or nil
         :on-limit (fn [{:keys [id limit caller]}] …)}             ; or nil

:ai/suggest {:write … :rate-limit {:per-day 20 :by :person}}       ; an endpoint's own
["/quote" {:post … :access :public :rate-limit {:per-minute 10 :by :client-ip}}]   ; a route's own
```

- `:per-minute`: a bucket of that size, refilled over the minute, in each
  server's memory. `:per-day`: a cap counted in Postgres, across servers and
  restarts, reset at midnight UTC.
- `:by` is `:person`, `:scope` (endpoints only: a whole shop's cap) or
  `:client-ip` (the trusted proxy's header); without it, the person, or
  the address when no one is signed in.
- A public endpoint or route without its own limit gets `:public`, reads
  and writes each their own. Signed-in calls without one have none.
- Checked after the permission, before the work. An endpoint answers
  `:too-many` with `:limit` and `:retry-after-seconds`, which the store
  keeps as the entry's error; a route answers 429 with `Retry-After`. Every
  hit goes to `:on-limit`; writes are never repeated by themselves.


### Background jobs

Work that shouldn't happen inside a request, or happens on a clock, queued
in Postgres (`groundwork.jobs`):

```clojure
:jobs {:kinds     [app.mail/jobs]
       :schedules [{:job :app.reminders/send :params {} :cron "0 18 * * *"}]   ; UTC
       :workers 4 :poll-ms 5000 :on? true}                                    ; or nil

{:app.mail/send-confirmation {:run        (fn [ctx params] …)
                              :params     [:map [:order/id :uuid]]
                              :timeout-ms 60000 :attempts 5
                              :scope      [:order :order/id]}}   ; optional

(jobs/enqueue! ctx :app.mail/send-confirmation {:order/id id})   ; in a write; {:in-ms n} for later
```

- A job is queued in the write's own transaction: it exists only if that
  commits, and the workers hear about it then (or within `:poll-ms`).
- Each due job is claimed by one worker (`FOR UPDATE SKIP LOCKED`), on any
  server, and runs on a virtual thread within its `:timeout-ms`, in one
  transaction: as the system, or behind the wall of its `:scope` only.
- A failure waits longer each time (30 s, 1 min, 2 min… up to an hour);
  after `:attempts` the job is dead, kept with its error. Work that no
  retry can fix throws `(jobs/give-up "why")` and is dead at once;
  `(repl/retry-job! app id)` runs it again. A job left running by a stopped
  server is queued again. Work must be safe to repeat.
- A due schedule is claimed by one server, which queues its job and moves
  it on: it runs once across servers.
- `:on? false` (previews): jobs are queued and wait. Done jobs are kept a
  week. In the REPL: `repl/queued-jobs`, `repl/dead-jobs`.

---

## 8. parquery

The transport between browser and server, at one address, `/parquery`:

- `parquery/http`: a POST carries the asks a page makes at one moment, as
  a JSON list; the answers come back in the same order. Each ask runs on its
  own, in parallel, with its own transaction, so one failing leaves the
  others. At most `:max-asks` per request; at most `:max-running` running
  across the server.
- `parquery/wire`: the message format, the same on both sides. JSON, typed
  by the endpoint's shapes: dates (`:time/local-date` …) and decimals
  (`:decimal`) travel as text and come back as what they are. An id
  (`:uuid`) is a real UUID on the server, strictly checked when it arrives,
  and text in the browser: there it is a name tag, the same whether it came
  from an answer, the address or a form control.
- `parquery/websocket`: the bells. A browser opens one line to
  `/parquery` and sends the reads it shows, `{"watch": [[endpoint params] …]}`;
  each is checked like an ask. When something it shows changes, it hears
  `{"changed": [[endpoint params] …]}` and asks again over HTTP. A bell
  carries no data.
- Postgres rings: at start every table in the permission description, and
  the `:bells` setting's `:global` and `:personal` tables, gets a trigger
  that announces, after a commit, the table, the scopes and people of the
  changed rows, and their ids; a rollback rings nothing. A watch waits for
  the tables its read's SQL uses (Postgres plans each file it ran; `:reads`
  adds what the plan can't see), in its verified scopes. A read about one
  record rings only when that record changed.
- **Every table of the app says how it rings, or the start refuses,** naming
  every one that doesn't: it's in the permission description, or in `:bells`
  as `:global` (everyone watching it, with the reason), `:personal` (only
  its person) or `:silent` (nothing, on purpose, with the reason). No table
  is quiet by accident.
- **Who is rung comes from who may see.** A table whose rows belong to a
  scope is in the permission description, behind the wall, and its bell
  reaches the people the description lets see the row. A `:global` table is
  one whose rows belong to nobody (reference data the app's own staff keep),
  or whose every row is read by everyone; its declaration says which. There
  is no way to ring by a scope column without the wall: a bell aimed by the
  column alone misses the readers outside the scope, and ringing everyone
  for a scope's rows grows with the square of the scopes online. Groundwork's own team tables ring too (memberships, roles, a
  role's actions, extra actions, invitations), so a team screen and
  "can I?" follow a change.
- When a part refuses at start, the parts started before it are stopped and
  the part's own error is thrown, with the message saying what to change.
- A line lives as long as its session. A session that ends (signed out,
  signed out everywhere, deleted) rings on the same channel, and every
  server closes its lines; each watch message checks the session first; a
  sweep every minute closes lines whose session expired and checks every
  line's watches again, so someone removed from a scope stops hearing
  about it.
- The route is public; each endpoint checks its own permission, under the
  browser's session and the Origin check (the websocket's too).

```
POST /parquery
[{"endpoint": "jobs/of-shop", "params": {"shop/id": "…"}},
 {"endpoint": "jobs/move", "params": {…}, "key": "…"}]
→ [{"status": "ok", "data": […]}, {"status": "not-found"}]
```

### In the browser

The app's browser entry (the `:browser` settings' `:entry`) makes one call:

```clojure
(ns app.browser
  (:require [groundwork.browser :as gb] [app.routes :as routes] [app.views :as views] [app.shapes :as shapes]))

(defn root [] [:main [views/nav] [gb/screen]])   ; gb/screen: the current route's screen

(defn start []
  (gb/start! {:root        root
              :routes      routes/routes       ; route records, below
              :shapes      shapes/answers      ; the same shapes as the server's (.cljc)
              :unknown     views/not-found     ; for an address no route has
              :placeholder views/loading       ; while a screen's code loads on demand
              :on-move     (fn [{:keys [from to]}] …)}))   ; after every move
```

- Groundwork reads what the page shell put in the page (the person signed
  in, their session's flags, the language and the app's languages), starts
  the store and its websocket, asks for the person's grants, starts routing
  at the page's address, and mounts `:root` into `<div id="app">` with
  Reagent. `@(rf/subscribe [::gb/person])` (the id, as text), `[::gb/flags]`,
  `[::gb/language]`, `[::gb/route]`, `[::gb/query]`.

**Nothing asks before the start.** `gb/start!` makes the store and sets how
answers are read (which fields are times, which are decimals). A question
put to the store before it (`ensure`, `own`, `ask-again`, a `write`,
`once-answered`, `hear`) throws, naming the endpoint, in development and in
production alike: code that asks while its namespace loads would otherwise
be answered into nothing, with its screen waiting forever. What a screen
needs on arrival is its route's `:reads`.

**Routing is Groundwork's, and a route is a record.** An app writes its
pages once, in a `.cljc` file the server and the browser both read:

```clojure
(ns app.routes
  #?(:cljs (:require [app.views :as views])))

(def routes
  [["/orders" {:name   :orders
               :access {:action :orders/see :scope [:shop :shop/id]}   ; who may open it (the server)
               :title  "Orders"                                        ; the tab's title, on both sides
               :page   {:description "Every order of the shop"}        ; the page shell's details (the server)
               #?@(:cljs [:view  views/orders                          ; its screen
                          :reads (fn [route] [{:endpoint :orders/list :params {…}}])])}]   ; its data, asked on arrival
   ["/orders/:order-id" {:name :order :access …
                         #?@(:cljs [:view views/order :title (fn [route] …) :reads …])}]
   ["/reports" {:name :reports :access … :title "Reports"
                #?@(:cljs [:load        #(shadow.lazy/load views/reports-loadable)   ; code that loads on demand
                           :placeholder views/reports-skeleton])}]])
```

```clojure
;; the server's settings: its pages come from the same records
:routes (into (web/page-routes app.routes/routes)
              [["/webhooks/payments" {:post payments/webhook :server-to-server true}]])
```

- **What every route gets:** an address leads to its screen, with the
  address's values (`:path-params`) and query; Back and Forward work; the
  title is set on every move; a new page starts at the top, an `#anchor` is
  scrolled to, a change of the query alone doesn't scroll, and Back puts the
  page where it was; **its `:reads` are asked on arrival** (the store's, for
  the owner `:groundwork/route`: what the next route shares is kept, the
  rest let go); an address no route has shows `:unknown`; `:on-move` hears
  every move.
- **One door:** an app moves and makes links only through Groundwork, never
  by building or reading addresses by hand. `(gb/href :order {:order-id id})`
  for an `<a>` (plain links move in-app); `(gb/navigate! :order {:order-id id})`
  or by address, `(gb/navigate! "/orders/5")`; `{:replace? true}` for no
  step in Back's history. An address no route has is loaded from the server.
- **Filters live in the address:** `@(rf/subscribe [::gb/query])` reads the
  query; `(gb/push-query! {:stage "curing"})` changes it as a step Back
  undoes, `(gb/replace-query! {…})` without one. A link to a filtered list
  can be shared.
- **Languages** (the `:web :languages` setting; the page shell tells the
  browser): `/de/orders` is the same route in German, `[::gb/language]` says
  which, and links keep the language the visit is in; the default language
  has no prefix. `(gb/href :orders nil nil {:language "de"})` for a language
  switch.
- **Declared, only where a route says so:** `:load`, a function returning a
  promise of the screen, for code that loads on demand (`:placeholder` shows
  meanwhile; see `:browser :modules`); and **unsaved changes**: a screen
  says `(gb/unsaved! true)` when its form is dirty. While it holds, a move
  by link, by `navigate!` or by Back and Forward waits under
  `@(rf/subscribe [::gb/held-move])` (the route someone tried to go to) for
  `(gb/continue-move!)` (discard and go) or `(gb/stay!)`, and closing the
  tab asks the browser's own question. `(gb/unsaved! false)` once saved. The screen
  swapping its own query in place (`gb/replace-query!`) is not a move away
  and is never held.
- `web/page-routes` makes each record the page shell under its `:access`,
  with its `:page`; a `:title` that is text is the first load's title too.
  What only the browser reads (`:view`, `:reads`, `:load`, `:placeholder`, a
  title function) is left out; records in a group keep their group.
- Tests: the engine on node with the browser replaced, and
  `groundwork.browser-routing-test` in headless Chrome over its debugging
  protocol (the browser's own history, links, titles, unsaved changes
  holding Back). `GROUNDWORK_CHROME` names the Chrome; without one it's
  skipped.
- **The build is Groundwork's** (`groundwork.build`): it makes the
  shadow-cljs build from the `:browser` settings, so an app keeps no build
  config. In development the one start (`repl/start!`) watches it; a save
  recompiles and renders again; `groundwork.build/compile` builds the same
  once, without watching (a second copy of the app on one checkout, a check
  before a commit). Before packaging, a release:
  `clojure -X:build groundwork.build/release :settings app.settings/production`
  compiles with fingerprinted names (`main.3F2A….js`) and a manifest the
  page shell reads, so the page and the build can't disagree and the file
  is cached a year (with `:hashed-files` like `"(?i)\\.[0-9a-f]{8,}\\."`).
- **An app with several modules** names them in `:browser :modules`
  (module → `:init-fn` or `:entries`, and `:depends-on`), and a page names
  the one it starts: `["/admin" {:get web/page :page {:module :admin}}]`.
  The page shell loads that module after the ones it depends on; a page
  that names none loads the module(s) that depend on no other. With
  `:loader? true`, modules no page names load on demand: as `<script>`
  tags from the app's own address, which the page's strict policy allows
  (after a release the page shell tells the loader the fingerprinted
  names). `:options` are extra shadow-cljs build options, merged over
  Groundwork's.
- Groundwork's own npm packages are in its `src/deps.cljs`; the app's
  `package.json` holds only its own, and `lightningcss` with `:styles`. The build needs `thheller/shadow-cljs`
  on the classpath (an app's `:dev` and `:build` aliases).

Then the store, under the screens:

```clojure
(rf/dispatch [::store/ensure :jobs-page [{:endpoint :jobs/list :params {:shop/id id}}]])
@(rf/subscribe [::store/answer {:endpoint :jobs/list :params {:shop/id id}}])   ;=> {:status :ready :data […]}
(rf/dispatch [::store/release :jobs-page])
(rf/dispatch [::store/own :jobs-page [asks…]])   ; exactly these now: shared ones kept, the rest let go
(rf/dispatch [::store/ask-again {:endpoint :jobs/list :params {:shop/id id}}])   ; a "try again" button
(rf/dispatch [::store/write {:endpoint :jobs/move :params {…}} [:jobs/moved]])
@(rf/subscribe [::store/can? :jobs/move shop-id])
```

- `parquery/http` (cljs): the asks of one moment travel in one POST; each
  answer comes back typed by its shape.
- `parquery/websocket` (cljs): one line for the visit; it sends what's on
  screen and hears what changed; a dropped line comes back by itself and
  asks everything on screen again.
- `groundwork.parquery-store`: answers kept by question in re-frame's db. A screen
  says what it needs when it opens and lets go when it closes; showing
  never fetches. Old data stays while a fresh answer loads; only the newest
  answer counts; bells are gathered for 200 ms and only what's on screen is
  asked again (once more after the answer when the bell rang while the
  question was out); an entry nothing needs goes after five minutes. Writes are
  sent once, with a one-time key. **A write's answer carries its own bell:**
  the write says which reads are on screen, the server answers which of them
  its transaction changed (from the same triggers that ring the websocket),
  and those are asked again at once, with no wait and no websocket needed.
  The websocket's bell for that write carries the same tag, so nothing is
  asked twice. Work committed outside the endpoint's own transaction
  (after-commit work, jobs, the team endpoints) isn't in the answer; it
  reaches screens through the websocket. `ask-again` asks a question again now, for
  what no change in the database announces (a "try again" button, a refresh,
  a timer); out already, it's asked once more when its answer lands.
- "Can I?" reads `:groundwork/grants`.
- An id is text in the browser: in params, answers and the store's keys. A
  UUID value given in params counts as its text.
- Dates come back as js-joda values, decimals as big.js (exact; trailing
  zeros aren't kept). An app's build needs the npm packages in
  `src/deps.cljs`.
- Tests: `npm install && npx shadow-cljs compile test` (headless, on node).

### The leak test

```clojure
(deftest nothing-leaks
  (let [report (leaks/check (gw/endpoints app)
                            {:person  peter                                  ; a member of one shop
                             :foreign {:shop zinc :order zinc-order :job zinc-job}})]   ; another shop's ids
    (is (empty? (:leaked report)) (pr-str (:leaked report)))))
```

- Every endpoint with an action permission is called with params generated
  from its input shape and every id set to the foreign one of its kind; it
  must answer not-found. It catches a permission description that grants
  too much, like a link whose condition forgot the status.
- A list that carries no ids is called as it is; its answer must hold none
  of the foreign ids, anywhere in it.
- **Open actions** are called as a stranger (nobody, or a person who
  belongs nowhere): they may answer, and what they answer must hold none of
  the `:sealed` ids you give (`:sealed #{an-unpublished-package an-order}`):
  the ids no stranger may see. The wall itself is asked too, for every open
  action and every kind of record, what a stranger behind it sees of each
  foreign scope (`leaks/open-wall`): only the rows the action's rule allows,
  and nothing of any other kind.
- **Passes:** run it once more as the holder of a pass on one record, with
  the giving scope's other records as the foreign ids: a pass on one must
  answer not-found for another.
- **A visitor's records:** an endpoint whose action a kind gives a visitor
  is asked twice more with the foreign ids, as another visitor and as a
  signed-in stranger; both must answer not-found.
- Public and signed-in endpoints are skipped, and so is one that needs a
  foreign id nobody gave; the report says which and why.
- Generating needs `org.clojure/test.check` on the test classpath.

---

## 9. Styles

A namespace names the stylesheets it needs, next to its own file
([the decision](decisions/2026-10-17-a-namespace-names-its-stylesheets.md)).
Classes are plain text, stylesheets plain CSS.

```clojure
(ns app.orders.list
  {:css ["orders.css"]}            ; src/app/orders/orders.css
  (:require [app.orders.row]))

(defn row [order]
  [:li {:class "orders-row"} …])
```

```clojure
:styles {:sources    ["src"]                  ; where the browser code and its stylesheets are
         :output-dir "resources/public/css"   ; where the bundles go
         :asset-path "/css"}                  ; where the browser fetches them
```

`:styles` is the one part an app may leave out (or set to nil): then it
brings its stylesheets through `:page :stylesheets` and nothing below runs.
With it, the browser build (`groundwork.build`) does the rest:

- **A bundle per module:** each browser module gets one bundle with the
  stylesheets its namespaces name, in the order the build loads them, so a
  namespace's come after those of the namespaces it requires. A file comes
  once; one a module it depends on carries already is left out.
- **Loading:** the page links the bundles of the modules it starts with. A
  module loaded on demand brings its own bundle, and its code runs only
  once the bundle is in, so nothing shows unstyled.
- **Checks at every build:** every `.css` under `:sources` is named by some
  namespace, and every named file is there. Otherwise the build stops with
  the list, like a compile error:

  ```
  The stylesheets have 2 problems:
    src/app/orders/old.css  is named by no namespace's {:css […]}
    src/app/orders/list.cljs  names src/app/orders/gone.css, which isn't there
  ```
- **Development:** bundles under plain names (`main.css`). Saving a
  stylesheet runs the build again, and the open page swaps the changed
  bundle in without a reload.
- **Release** (`groundwork.build/release`): every bundle minified by
  lightningcss, which needs the npm package `lightningcss` in the app's
  `package.json`. A stylesheet it can't parse stops the release, naming
  the file and the line. Bundles get fingerprinted names
  (`main.3F2A91C0.css`) and a `manifest.edn` the page shell reads; in
  production the app doesn't start without it.
- A bundle is served from `:asset-path`, not from where its stylesheets
  are, so a `url()` in them is an absolute address (`/img/logo.svg`).
- How an app likes its CSS (design tokens, no raw colours, no
  `!important`) is the app's own check.
- Tests: the namespaces, the check and the bundles (`groundwork.styles-test`),
  the build and the page (`groundwork.build-test`), the loader on node
  (`groundwork.loader-test`), and in headless Chrome
  (`groundwork.browser-routing-test`): a release's page links the
  fingerprinted bundle, a module loaded on demand has its bundle in before
  its code runs, and in development a save shows in the open page.

---

## 10. The REPL

Where a REPL into a running app may exist: development always; previews
through the eval bridge; stage only over SSH; production off (see
[the decision](decisions/2026-10-12-repls-by-environment.md)). What's tried
and not saved to a file is gone on the next start.

```clojure
(def dev (repl/start! settings {}))   ; :environment :development only
(repl/ask (:app dev) :jobs/list {:shop/id acme} :as peter)
(repl/why (:app dev) peter :orders/edit [:order order-id])
;=> {:allowed? false
;    :because ["The order … is in shop …."
;              "… is Production in shop …, but Production doesn't hold :orders/edit."]}
(repl/why (:app dev) pia :orders/see acme)              ; in a scope
(repl/lines (:app dev))          ; open websocket lines: who, session, what they watch
(repl/recent-bells (:app dev))   ; the last 50 bells this server heard
(repl/sessions (:app dev) peter) (repl/sign-out-everywhere! (:app dev) peter)
(repl/migrations (:app dev))     ; ran, waiting, changed, missing
(repl/restart! dev)  (repl/stop! dev)
```

- **One start:** the app, an nREPL (with the browser build's middleware
  when shadow-cljs is there, so the same port has a ClojureScript REPL:
  `(shadow.cljs.devtools.api/repl :app)`), the browser build's watcher (when
  `:browser :watch?`), and everything
  printed also in `.groundwork/dev.log` (the last run's kept as
  `dev.log.1`). The port goes to `.nrepl-port`. It needs `nrepl/nrepl` and
  `thheller/shadow-cljs` on the classpath, as an app's `:dev` alias has them.
  An app's `.gitignore` lists `.groundwork/`, `.nrepl-port` and `.mcp.json`.
- **As a service:** `groundwork.repl/serve` runs the same start until the
  process is stopped, with no terminal:
  `clojure -X:dev groundwork.repl/serve :settings app.settings/dev :nrepl-port 7888`.
  [`docs/groundwork-dev.service`](docs/groundwork-dev.service) is a systemd
  user unit for it; `systemctl --user stop` (SIGTERM) stops the app, the
  watcher and the nREPL cleanly. The running dev map is `@repl/running`, so
  from the nREPL: `(swap! repl/running repl/restart!)`. Checked by running
  the example app (`test/example/hello`) as a transient user service:
  served, recompiled on save, evaluated and restarted over the nREPL, and
  stopped with every port closed.
- **clojure-mcp by default:** the start points a `clojure-mcp` server at its
  nREPL in the app's `.mcp.json` (Claude Code's project MCP servers),
  keeping any other servers there, so Claude Code evaluates in the running
  app. A fixed `:nrepl-port` keeps the file the same from start to start;
  `{:mcp-json? false}` leaves it alone. clojure-mcp is installed once per
  machine as the Clojure tool `mcp`.
- **ask** goes through every check a browser's ask does; a write runs too,
  with a fresh one-time key.
- **why** says it in plain words: the record's scope, the person's
  memberships, roles and extra actions there and above, the links followed,
  a person column, an only-when, a session's flags.
- **Previews: the eval bridge.** With `:web :eval-bridge {:secret …
  :timeout-ms 30000 :max-chars 20000}` (nil turns it off; production refuses
  to start with it), `POST /groundwork/eval` with `X-Eval-Secret` and Clojure
  code as the body evaluates it in the running app's `user` namespace and
  answers `{"value": …, "out": …}` or `{"error": …}`; 504 when it ran too
  long. `(groundwork.core/current)` is the running app. `?cljs=app` evaluates
  in the browser tabs open on it, when the shadow-cljs build runs in that
  server. Every evaluation is logged; a wrong secret is a plain 404.
- **In the browser** (the build's REPL): `(groundwork.repl/entries)` lists
  every question the store holds, `(groundwork.repl/answer :jobs/list
  {:shop/id id})` one entry with its data.
