# Adopting the access language

A note for moving an app's permission description to sentences
([the decision](decisions/2026-10-17-access-rules-are-sentences-in-groundworks-language.md)).
Delete it once the apps have moved.

The description keeps `:scopes` and `:records`; `:links`, `:passes`, `:open`
and `:session-blocks` go, and so do a record's `:in`, `:people`, `:visitors`,
`:only-when` and `:open`, and an endpoint's `:and-scope`. Everything they said
becomes a sentence in one list, `:rules`. Nothing is given implicitly any
more: a member sees a kind of record only when a sentence says so.

## Scopes: each action says what it does at the wall

```clojure
;; before
:shop {:actions #{:orders/see :orders/edit} :roles {…} :parent nil}
;; after
:shop {:actions {:orders/see #{:read} :orders/edit #{:read :update}} :roles {…} :parent nil}
```

The commands are `:read :insert :update :delete`. An action that updates or
deletes reads too. A write endpoint must declare an action that writes. An
action may have no command at all (`#{}`): a tick in the roles grid that a
sentence names as the action held somewhere (`[:member … <action>]`).

New, optional: `:single? true` for a kind with exactly one scope (a
platform's back office), and `:table`/`:id` naming the app's own table of
such scopes, where a state a deny reads lives.

## Records: `:in` becomes `:whose`

```clojure
;; before
:order {:table :orders :id :order/id :in [:shop :order/shop-id]
        :people {:orders/see :order/customer-id}
        :visitors {:carts/mine :order/visitor-id}}
:agreement {:table :agreements :id :agreement/id
            :in [[:shop :agreement/shop-id] [:partner :agreement/partner-id]]}
;; after
:order {:table :orders :id :order/id
        :whose {:order/shop-id :shop :order/customer-id :person :order/visitor-id :visitor}}
:agreement {:table :agreements :id :agreement/id
            :whose {:agreement/shop-id :shop :agreement/partner-id :partner}}
```

`:parent` stays as it is: it is the matching link and the fill, not an
access rule.

## Word by word

| Before | After |
|---|---|
| a role holding an action in the record's scope (implicit for every kind in the scope) | `{:allow :orders/see :on :order :to [:member :order/shop-id]}`, one per kind the action reaches |
| a parent scope's membership counting below | the same sentence: a membership above still counts |
| `:people {:orders/see :order/customer-id}` | `{:allow :orders/see :on :order :to [:me :order/customer-id]}` |
| `:visitors {:carts/mine :order/visitor-id}` | `{:allow :carts/mine :on :order :to [:me :order/visitor-id]}` |
| `:only-when {:invoices/edit [:= :invoice/status "draft"]}` | `:when [:= :invoice/status "draft"]` on the sentences of `:invoices/edit` |
| `:open {:anyone #{:catalogue/see}}` and the kind's `:open #{:catalogue/see}` | `{:allow :catalogue/see :on :package :to :anyone :when [:= :package/published true]}` |
| `:open {:signed-in #{:shops/card}}` | `:to :signed-in` |
| a link `{:table :agreements :from [:partner :agreement/partner-id] :to [:shop :agreement/shop-id] :when [:= :agreement/status "active"] :grants {:orders/see :agreements/see}}` | `{:allow :orders/see :on :order :via {:table :agreements :match {:order/shop-id :agreement/shop-id} :to [:member :agreement/partner-id :agreements/see] :when [:= :agreement/status "active"]}}`, one per kind it reaches (a link reached only the endpoint's own kind) |
| a pass `{:table :handoffs :record [:order :handoff/order-id] :to [:shop :handoff/to-shop-id] :when [:= :handoff/status "open"] :grants {:orders/see :handoffs/work} :under #{:job}}` | `{:allow :orders/see :on :order :via {:table :handoffs :match {:order/id :handoff/order-id} :to [:member :handoff/to-shop-id :handoffs/work] :when [:= :handoff/status "open"]}}`, and for the jobs under it the same with `:on :job :match {:job/order-id :handoff/order-id}` |
| a pass `:to-person :handoff/driver-id` | the via's `:to [:me :handoff/driver-id]` |
| a pass's guard (only the giver creates it) | the handoff's own insert sentence (`:to [:member :handoff/from-shop-id]`); Groundwork's guard on every via table refuses re-aiming a row (another order, another receiver, its condition holding again) by anyone its insert sentences don't allow |
| `:session-blocks {:impersonated #{:money/move}}` | `{:deny :money/move :session :impersonated}`; it now holds at the wall too, for every sentence of the action |
| shared rows owned by the platform's scope, read with `:and-scope [:shop platform]` | universal rows: the scope column empty, `{:allow :catalogue/see :on :package :to :anyone :when [:and [:none :package/shop-id] [:= :package/published true]]}`, kept by `{:allow :catalogue/edit :on :package :to [:member :platform :platform/catalogue] :when [:none :package/shop-id]}`; drop `:and-scope` from the endpoints |
| platform staff as members of a shop-kind scope | a `:single? true` kind, and `[:member :platform <its action>]` |
| (new) a related row open to anyone | `{:allow :replies/write :on :reply :via {:table :tickets :match {:reply/ticket-id :ticket/id} :to :anyone :when [:= :ticket/status "open"]}}`: the action is open for endpoints, which may carry the ticket's id |
| (new) anyone may write, narrowed | `{:allow :inquiries/send :on :inquiry :to :anyone :when [:= :inquiry/status "new"]}`, or `:via` a related row instead of `:to`; an open write with neither refuses the start |
| (new) a suspended shop is read-only | `:shop {… :table :shops :id :shop/id}` and `{:deny :writes :scope :shop :when [:= :shop/status "suspended"]}` |

## What else changes for an app

- **Lists:** the wall knows read, insert, update and delete, not the
  endpoint's action. A list endpoint shows every row a sentence lets the
  person read. A list that must be narrower (only the editable ones) calls
  the function Groundwork makes per action and kind in its query file:
  `WHERE order__id IN (SELECT * FROM groundwork."may orders/edit on order"())`.
  Before, the wall did this narrowing by itself for the endpoint's own
  action and kind.
- **Members see only what sentences give.** The old wall showed a member
  every kind in their scope; write the sentences for each kind the app's
  SQL reads, or its reads come back short.
- **Via tables are record kinds,** and need indexes: on both sides of what
  they match, and on the related row's who column. The start refuses with
  the `CREATE INDEX` to add.
- **Writes:** inserting a row needs a sentence whose action inserts on that
  kind, for the person; an update, one that updates.
- **`:groundwork/grants`** may answer a grant with no scope: every scope.
  The browser's `can?` reads it so.
- **Handlers:** ctx `:every-scope?` is true for a list a fixed scope's
  member makes over every scope (`:scopes` is then empty).
- **Migration 017** runs at the next start: new wall functions and a
  fourth argument to the ring trigger.

## Namespaces that moved

The code is laid out by area since 2026-10-18
([the decision](decisions/2026-10-18-one-folder-per-area.md)); function
names stay. An app changes its requires:

| Before | After |
|--------|-------|
| `groundwork.names` | `groundwork.postgres.names` |
| `groundwork.sql` | `groundwork.postgres.sql` |
| `groundwork.permissions.rules` | `groundwork.permissions.description` (reading, checking) and `groundwork.permissions.sql` (the SQL) |
| `groundwork.leaks` | `groundwork.permissions.leaks` |
| `groundwork.endpoints/bell` | `groundwork.endpoints.bells/bell` |
| `groundwork.parquery-store` (cljs) | `groundwork.endpoints.store` |
| `groundwork.parquery.store` (cljs) | `groundwork.endpoints.store` |
| `groundwork.parquery.route` | `groundwork.endpoints.route` |
| `groundwork.parquery.http` (clj and cljs) | `groundwork.endpoints.http` |
| `groundwork.parquery.websocket` (clj and cljs) | `groundwork.endpoints.websocket` |
| `groundwork.parquery.bells` | `groundwork.endpoints.bells` |
| `groundwork.parquery.wire` (cljc) | `groundwork.endpoints.wire` |
| `groundwork.page` | `groundwork.web.page` |
| `groundwork.server` | `groundwork.web.server` |
| `groundwork.web/sniff-type` | `groundwork.web.files/sniff-type` |
| `groundwork.routing` (cljs) | `groundwork.browser.routing` |
| `groundwork.loader` (cljs) | `groundwork.browser.loader` |
| `groundwork.styles` | `groundwork.build.styles` (its build hook `groundwork.build.styles/hook`) |
| `groundwork.repl/start!` `stop!` `restart!` `serve` `running` `write-mcp-json!` | `groundwork.repl.dev/…` |
| `groundwork.repl` (cljs) | `groundwork.repl.browser` |

`groundwork.permissions` keeps what an app calls (`allowed?`, `check!`,
`list-filter`, `holds?`, `in-wall`, `as-system`, `in-scopes`, the team
writes); the rest is in `groundwork.permissions.check`. Error types stay
`:groundwork.permissions/…`.

The parquery folder went into the endpoints area
([the decision](decisions/2026-10-18-the-endpoints-area-holds-its-transport.md)).
Its error types follow their namespaces: `:groundwork.parquery.bells/tables-not-declared`
is now `:groundwork.endpoints.bells/tables-not-declared`, and the store's
`:groundwork.parquery.store/asked-before-start` is
`:groundwork.endpoints.store/asked-before-start`; so do the store's re-frame
events and subscriptions (`::store/…`). The address `/parquery` stays; the
settings key, the part and the re-frame db key are renamed below.

## Names made consistent

Renamed once, since 2026-10-18
([the decision](decisions/2026-10-18-names-are-made-consistent-once.md)).
An app's settings map is closed, so an old key stops the start; a route's
data isn't, so an old route key is ignored: rename them all in one step.

| Before | After |
|--------|-------|
| `:parquery {:endpoints [...] …}` | `:endpoints {:list [...] …}` |
| an endpoint's `:permission` | `:access`, the same shape as a route's: `:public`, `:signed-in`, or an action on a `:record`, a `:scope` or a `:list` (a route checks `:record` only) |
| an endpoint's `:keeps-for-visitor` | `:keeps-for-visitor?` |
| a route's `:server-to-server` | `:server-to-server?` |
| a route's `:embeddable` | `:embeddable?` (same values: `true`, `:same-origin` or a list of origins) |
| a browser module's `:web-worker` (in `:browser :modules`) | `:web-worker?` |
| a job kind's `:scope` | `:runs-in` |
| `:page :lang`, and a route's `:page :lang` | gone: the page takes the request's language |
| `:web :languages nil` (one language) | `{:supported ["en"] :default "en" :cookie nil}`: `:web :languages` is required, the one place a language is set; `web/language` is never nil |
| the part `:groundwork/parquery` (`(gw/part app …)`) | `:groundwork/endpoints-transport` |
| the re-frame db key `:groundwork/parquery-store` | `:groundwork/endpoints-store` |
| the table `groundwork.run_once`, columns `run_once__…` | `groundwork.run_once_keys`, columns `run_once_key__…` (migration 019) |
| the columns `groundwork.job_schedules.schedule__…` | `job_schedule__…` (migration 019) |

`:page :stylesheets` stays, beside `:styles`, for files an app brings
itself. Migration 019 runs at the next start; an app's development schema
snapshot changes with it.

## The database connection, and production's migrate

Since 2026-10-18 ([TLS](decisions/2026-10-18-the-database-connection-is-encrypted-and-verified.md),
[migrate](decisions/2026-10-18-production-migrates-before-it-starts.md)).

**Every app:**

- Add `:db :tls`: `:verified` for a database elsewhere (and `:tls-root-cert
  "path.pem"` when its certificate comes from a private authority), `:off`
  for one on the same machine. Production refuses `:off` for any host but
  `localhost`, `127.x.x.x` or `::1`. Drop TLS options set elsewhere (an
  `sslmode` in PgJDBC's `driverconfig.properties` on the classpath, a
  `PGSSLMODE`): Groundwork sets them on every connection.
- Migration 020 (`groundwork.installs`) runs at the next start in
  development; the schema snapshot changes with it.

**Production** (development and tests keep one role, and the start migrates):

1. Two roles. The owner owns every table, view, sequence and function of
   `public` and `groundwork`, and the two schemas; it may have BYPASSRLS.
   The app's role (`:db :user`) owns nothing of them, is no member of the
   owner, has no BYPASSRLS and is no superuser. A database where the app's
   role owns objects hands them over once, as a role with the rights of
   both: `REASSIGN OWNED BY <app role> TO <owner>`, and takes back what it
   was given to create: `REVOKE CREATE ON SCHEMA public FROM <app role>`.
2. The deploy runs migrate as the owner before the start:
   `GROUNDWORK_OWNER_USER=… GROUNDWORK_OWNER_PASSWORD=… clojure -X:migrate`
   with `:migrate {:exec-fn groundwork.core/migrate :exec-args {:settings app.settings/production}}`,
   or `(gw/migrate! settings {:user … :password …})` from the app's own
   migrate main. The owner's password goes to that command alone, never
   into the running app's environment.
3. Grants the app made itself for its role are no longer needed: migrate
   grants what the list in the README's Production says, every time.
4. A production start now refuses a database migrate hasn't brought up to
   its settings: run migrate on every deploy, also when only the
   permission description or `:endpoints :bells` changed. Tests that start
   in production migrate first, as the owner.

## Several scopes in one request

Since 2026-10-18 ([the decision](decisions/2026-10-18-one-request-may-name-several-scopes.md)).
Nothing an app has changes; a new `:access` shape is there for a request
about several scopes (one cart paid as an order in each shop):

- `{:action … :scopes [kind param-or-path]}`: a param holding a list of
  ids, or a path to them through the params (`[:shop [:cart/lines
  :shop/id]]`). The action is checked in each scope itself; one the person
  may not act in answers not-found for the whole request. The handler's
  transaction sees and writes exactly those scopes (ctx `:scopes`).
- An endpoint that ran as the system for this reason moves behind the wall
  with it; a write about several scopes is one transaction, so a handler
  that opened one transaction per scope runs in the endpoint's instead.
- The leak test asks such an endpoint with a scope the person holds the
  action in and the foreign one, and expects not-found.
- The browser's `can?` takes a list of scope ids: true when the action is
  given in every one.
