# groundwork.server.main

The server's one start: `start-app!` takes the app as one map (settings,
tables, changes, scope kinds, handlers, pages, background job kinds,
schedules), checks the settings (each library brings its part; every problem
at once), makes the app's one shaper from the `:shaped-json` settings and passes it to
bridge's routes, groundwork-background-jobs's workers, the page and the health checks (no
library makes its own; the running app holds it, `:shaped-json`), makes the app's
one logger from the `:logs` settings and passes its log to every library
that writes a line (the running app holds both, `:logs` and `:log`;
[the decision](../../../../decisions/2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md)), migrates in development or refuses an unsafe or old database in
production, and starts the pool, the handlers (groundwork-access's check, groundwork's
`:groundwork-access/my-actions`, then groundwork-live-updates's notes),
groundwork-live-updates's listener (the `:live-updates :listening` settings), groundwork-background-jobs's
background job workers (`:background-jobs`; each job's one transaction is
groundwork-access's `run-as-system!` as its kind's `:system-actor`, the app's kinds
too, so an app writes a system actor, and its sentences on the tables it
reaches, per kind of background work), in development browser-build's
watcher of the browser build (`:browser-build {:watch options}`, checked by
browser-build before anything starts), and bridge's server (its
`:server` settings, bridge's, every key required; askers told apart by
session, else address; sse hooks for groundwork-live-updates, the ws hooks nil; files from
`:page :files`). `migrate-app!` is for the deploy, as the owner; the
`:migration` settings are each part's time limits. The page
shell answers every page address: the app's scripts and styles, and the
browser's settings (version, addresses, sse and ws reopen delays, the
question store's settings, `:live-updates :store`, the browser's groundwork-shaped-json settings,
`:browser-shaped-json`, the write retries, `:run-once :retry-writes`, pages) as
JSON in the page; its viewport, referrer policy, frame options and content
security policy are `:page` settings. What only the app knows is the app
map's `:render-page`, called for every page answer: its title, description,
head tags and the body inside `#app` (what a crawler reads before the app
draws), and the not-found page for a GET no route and no file answers
(page nil, 404); with `:content-security-policy {:policy "script-src
'nonce-{nonce}' …" :nonce? true}` each answer gets a nonce of its own, on
the shell's scripts and handed to `render-page` for the app's
([the decision](../../../../decisions/2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md)).
It answers a page, `:answer :page`, or a redirect, `{:answer :redirect
:status 303 :location "/login?next=%2Fflex"}` (301, 302, 303, 307 or 308),
answered with its status and `Location`, never cached, and no shell: a
signed-out person sent to the login, not a 200 that is a login page
([the decision](../../../../decisions/2026-10-23-render-page-answers-a-page-or-a-redirect.md)):

```clojure
:render-page (fn [request page {:keys [nonce asker run-as-asker!]}]
               (cond
                 (nil? page)
                 {:answer :page :status 404 :title "Not found" :description nil :head-html "" :body-html "<main>No such page.</main>"}
                 (and (:signed-in-only? page) (nil? (:person-id asker)))
                 {:answer :redirect :status 303 :location (str "/login?next=" (java.net.URLEncoder/encode (:uri request) "UTF-8"))}
                 :else
                 {:answer :page :status 200 :title (languages/tr :site.home/title {}) :description (languages/tr :site.home/description {})
                  :head-html (str "<link rel=\"canonical\" href=\"…\">") :body-html (prerender request)}))
``` The shell is in the request's
language: every request first gets it from groundwork-languages (the `:languages`
settings: the address's prefix, taken off before routing, the cookie,
Accept-Language, the default), and the shell's `<html lang>`, its
alternate links (at the `:access` `:allowed-origin`) and its JSON's
`:language` follow it; the JSON also carries the languages and each module's
stylesheet bundle (`:module-stylesheets`), and the shell lists `main`'s
files only: a page's `:module`, one of the app's `:browser-modules`, loads
on demand ([the decision](../../../../decisions/2026-10-23-routing-extras-each-where-it-belongs.md)).

Each language's catalog (`<catalogs-path>/<language>.json` on the
classpath) is read at the start, held by the running app as `:texts` for
the server's own texts outside a request (`(languages/with-language texts
"de" (languages/tr key args))`; inside one the request's language is
bound already), and served where the browser fetches it,
`/<catalogs-path>/<language>.json`
([the decision](../../../../decisions/2026-10-23-house-reads-the-catalogs-at-the-start-and-serves-them.md)).
Every reason a refusal may give has its text in every catalog, or the
start is refused, `:groundwork-languages/missing-texts`, naming each (groundwork-languages's
`check-texts-of-reasons`): groundwork-steps's, which bridge gives (`:groundwork-steps/too-many`,
`:groundwork-steps/failed`, `:groundwork-steps/invalid`, `:groundwork-steps/no-answer`, `:groundwork-steps/unfit`),
groundwork-access's (`:groundwork-access/signed-out`, `:groundwork-access/not-found`,
`:groundwork-access/not-allowed`), groundwork's run-once ones and every handler's
`:refusals`.

```clojure
:languages {:languages [{:code "en" :name "English"} {:code "de" :name "Deutsch"}] :default "en" :cookie-name "lang"   ; groundwork-languages's; :cookie-name nil for none
            :preview-languages [] :fallback-language "en" :catalogs-path "i18n"}   ; [] in production; i18n/<language>.json
``` Every value that sets behaviour is a
setting; what stays fixed, and why:
[the decision](../../../../decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

Two plain GETs answer whoever runs the app (a deploy's proxy, a load
balancer), outside sessions, the Origin check and rate limits, at the
`:health` settings' paths, as JSON with the `:version`: live, 200 while the
process answers, no database; ready, 200 once `start-app!` has started the
server while the database answers `SELECT 1` within `:database-timeout-ms`,
else 503 with a reason. That the database matches the tables is not asked
again (the start refused a database that didn't). `stop-app!` answers ready
with 503 for `:not-ready-before-stop-ms` before the server stops:
[the decision](../../../../decisions/server/main/2026-10-22-main-answers-health-checks.md).

```clojure
:health {:live-path "/health/live" :ready-path "/health/ready"
         :database-timeout-ms 1000 :not-ready-before-stop-ms 0}
```

One more GET, for a monitor, outside them too: the `:error` lines groundwork-logs
keeps, after `?since=N`, as a JSON array, only with the `:errors` settings'
token as a bearer token (else 401). The browser's uncaught errors and
unhandled rejections, and those an app's error boundary reports, come in
as a message, `:groundwork/browser-error` (`{:kind :render-or-:uncaught
:version … :message … :stack … :url …}`), a handler of groundwork's own
(groundwork-access's session step, then `:error :browser/error`, with the kind and
the bundle's version), limited per asker by `:browser-rate-limit`, and are
kept with the server's: one numbered list, which the app's monitor reads;
an app keeps none of its own
([the decision](../../../../decisions/server/main/2026-10-22-main-serves-the-kept-errors-and-logs-the-browsers.md),
[the one list](../../../../decisions/2026-10-23-browser-errors-go-to-the-composers-one-list.md)).

```clojure
:errors {:path "/groundwork/errors" :token #secret :errors/token :browser-rate-limit {:max 10 :per-ms 60000}}
```

One handler of groundwork-access's, `:groundwork-access/my-actions` (`make-my-actions-handler`):
the asker's actions by scope, and the open ones under nil kind and id, which
the browser's start names as groundwork-screens's `:actions-question`; an app's handler
of that name is refused. groundwork-access's access tables tell, so a membership or a
role changed asks it again:
[the decision](../../../../decisions/2026-10-23-the-browser-start-starts-parlor.md).

```clojure
:my-actions {:rate-limit {:max 60 :per-ms 60000}}   ; or nil for none
```

Every write runs once (`groundwork.server.main.run-once`): an app handler whose
action may write (groundwork-access's `does-action-only-read?` says no) has its steps
wrapped in one step, after groundwork-access's check, in its transaction, which
claims the request's one-time key (the `idempotency-key` header, a UUID)
for the message's name in `groundwork.run_once_keys`. A repeat by the same
person gets the first answer and saves nothing; one while the first runs
waits for it; someone else's is refused, `:groundwork/once-key-of-someone-else`,
and a key that isn't a UUID's text `:groundwork/once-key-not-a-uuid`:
groundwork's own reasons, added to every write's `:refusals` (a handler
that declares one itself is refused). Keys older than `:keep-keys-ms`
count as forgotten, and groundwork's background job `:groundwork/forget-old-once-keys`
deletes them on its cron line, run as groundwork's system actor
`:groundwork-once-keys`. A write's answer must fit its shape or
nothing is saved. A write without a key runs as it is:
[the decision](../../../../decisions/2026-10-23-every-write-runs-once-by-its-one-time-key.md).

```clojure
:run-once {:keep-keys-ms 86400000
           :forget-keys  {:cron "*/15 * * * *" :timeout-ms 60000 :attempts 3}
           :retry-writes {:times 2 :wait-ms 1000}}   ; the browser's, in the page; ends before a key is forgotten
```

Every app handler writes out `:daily-caps` beside bridge's `:rate-limit`,
`[]` for none (`groundwork.server.main.daily-caps`): `[{:max 10 :per :asker}
{:max 300 :per :everyone}]`, calls a UTC day, `:asker` the person (a token's
person too), else the visitor, else the request's address. A capped
handler's first step, after groundwork-access's check and inside a write's run-once
step, counts the call in `groundwork.daily_cap_counts`, in a transaction of
its own on a pool of its own (`:daily-caps :pool-size`), so every call let
through counts whether or not its work commits, and only callers the
check lets through spend a shared budget. Over a cap nothing is counted and
the call is refused `:groundwork/daily-cap-reached {:retry-after-ms n}`,
until midnight UTC: groundwork's own reason, added to every capped
handler's `:refusals` (and to every name's in the browser). groundwork's
background job `:groundwork/forget-old-daily-cap-counts`, run as its system
actor `:groundwork-daily-caps`, deletes the days before today:
[the decision](../../../../decisions/2026-10-23-daily-caps-are-counted-by-the-composer-after-the-permission.md).

```clojure
:daily-caps {:pool-size     2                                            ; the counts' own pool
             :forget-counts {:cron "15 0 * * *" :timeout-ms 60000 :attempts 3}}
```

```clojure
:logs         {:lowest-level :info :format :json                      ; groundwork-logs's; development :debug, :readable
                  :errors-kept 1000 :write-to :stdout}
:shaper            {:most-shapes-kept 1000                                 ; groundwork-shaped-json's settings on the JVM
                  :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                     :max-name-length 50000 :max-document-length nil :max-token-count nil
                                     :max-writing-nesting-depth 1000}}
:browser-shaped-json    {:most-shapes-kept 1000 :most-keys-kept 10000}          ; the browser's, but :exact-decimals
:postgres        {…                                                      ; groundwork-postgres's pool settings, all 21 keys
                  :metric-buckets {:query-seconds [0.001 0.005 0.025 0.1 0.5 2.5 10]
                                   :connection-acquire-seconds [0.001 0.01 0.1 1 10]
                                   :connection-usage-seconds [0.001 0.01 0.1 1 10 60]}}
:migration       {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                  :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
:background-jobs {:workers 4 :poll-ms 1000
                  :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                  :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000
                  :forget-done-every-ms 3600000 :stop-wait-ms 30000
                  :metric-buckets {:job-run-seconds [0.01 0.1 1 10 60 600]}}
:server          {…                                                      ; bridge's settings, every key
                  :metric-buckets {:answer-seconds [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}}
:access        {:cookie-name "app_session" :session-days 30 :visitor-session-days 7   ; groundwork-access's
                  :renew-after-minutes 60 :allowed-origin "https://shop.example" :secure-cookie? true
                  :token-lead "shop_" :token-shown-characters 11 :token-longest-days 365}
:live-updates        {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                  :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                              :reconnect-after-ms 1000 :stop-wait-ms 2000}}
:browser-build   :built                                                  ; or {:watch {…}}: browser-build's watch options
                                                                         ; but :entry, :files-folder and :log
```

One meter, made from the `:meter` settings and handed to groundwork-postgres's pool,
bridge's routes, groundwork-live-updates's listener and groundwork-background-jobs's workers; groundwork's
own metrics (`groundwork_stage{stage}`, `groundwork_ready_checks_total{outcome}`,
`groundwork_build_info{version}`) and groundwork-logs's counts read when the text is
written (`groundwork_logs_lines_total{level}`, `groundwork_logs_write_failures_total`,
`groundwork_logs_errors_kept`); the text at the `:metrics` path, outside sessions,
access and rate limits, only with its bearer token (else 401).
`:most-series-per-metric` covers the largest declaration: bridge's
`messages_total`, (handlers+1)×24; its `answer_seconds` and groundwork-postgres's
`query_seconds`, (names+1)×(buckets+3). `migrate-app!`'s owner pool counts
on a meter of its own, never served:
[the decision](../../../../decisions/2026-10-23-main-makes-one-meter-and-serves-its-text.md).

```clojure
:meter   {:jvm-metrics true :most-series-per-metric 5000}
:metrics {:path "/metrics" :token #secret :groundwork-metrics/token}
```

A visitor's session: a POST at the `:visitors` path, from the app's own
Origin only (else 403), groundwork-access's `start-visitor-session!`, answered
`{"visitor-id": "…"}` with its Set-Cookie; the page carries the path to the
browser's `start-visitor-session!`:
[the decision](../../../../decisions/2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md).
One address (bridge's `find-client-address`, the `:server` settings'
`:client-address-header`) may POST it `:rate-limit` times; more are
answered 429 and make nothing (bridge's `make-rate-limited-ring-handler`):
[the decision](../../../../decisions/2026-10-23-the-visitor-route-is-rate-limited-by-address-with-mailrooms-buckets.md).

```clojure
:visitors {:path "/groundwork/visitor" :rate-limit {:max 10 :per-ms 60000}}
```

The REPL, groundwork-live-repl's, by the `:live-repl` settings, started last and stopped
first, groundwork's tools in `groundwork-live-repl.tools`: `(ask-as-person message asker)`,
`(why-refused asker handler-name params)`, `(explain-setting path)`, the
asker `{:person-id :visitor-id :session-flags}`; only the REPL names a
person (`ask-as-person` makes the handlers ready with groundwork-access.repl's step
in its session step's place, and answers by `answer-as`'s own path, with
no request):
[the decision](../../../../decisions/2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md).

```clojure
:live-repl {:mode :development :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file ".nrepl-port"}
;; stage {:mode :ssh-only …}; production {:mode :off :emergency-switch {…}} or {:mode :off :emergency-switch nil}
```

The app's files and email: one file storage (groundwork-file-storage's `make-file-storage`, the `:file-storage
:storage` settings) and one mailer (groundwork-email's `make-mailer`, the
`:email :sending` settings), on the app's log and meter (and groundwork-shaped-json),
held by the running app as `:file-storage` and `:email`; `groundwork_file_storage.files` and
`groundwork_email.emails` migrated with groundwork's tables; groundwork-email's background job
kind on the workers. The upload route (`groundwork.server.main.files`) is
groundwork-file-storage's handler at `:file-storage :upload :path`, asking groundwork-access's session step
(the Origin, or a token), then the app map's `:may-upload?` `(fn [asker])`, before the
body is read; `:max-file-bytes` must be below `:server :max-body-bytes`.
The provider's webhooks are groundwork-email's, at `:email :webhooks`' paths,
outside sessions, handing bounces to the app map's `:on-delivery-event`
`{tag f}` and inbound email to `:on-inbound-email` `{mailbox f}`:
[the decision](../../../../decisions/2026-10-23-main-makes-one-attic-and-one-postbox.md).
Each function runs in a transaction as groundwork-access's system actor `:email`
(`run-as-system!`), allowed what the app's sentences give `[:system
:email]` and nothing else:
[the decision](../../../../decisions/2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md).
A file is deleted by the app, with groundwork-file-storage's `delete-file!` on `(:file-storage
running)` and `(:pool running)`, after its own rows are kept, or, in a
handler's step, with groundwork-file-storage's `delete-file-after-commit!` on those and the
step's `(:groundwork-access/transaction context)`, which runs it once that
transaction commits (groundwork-postgres's `run-after-commit!`, which a step may call
itself for any work that may be lost: a cache, a harmless outside call;
work that must happen is a background job queued in the same transaction:
[groundwork-postgres's decision](https://github.com/p4ulcristian/groundwork-postgres/blob/tidy-up/decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)).
Every transaction groundwork opens for app code carries the log of
whoever asked (groundwork-postgres's `:log`): groundwork-access's check, the message's, with its
request id and person; a job's, with its id and kind; a route's
`run-as-asker!` and `run-as-system!`, the route's; groundwork-email's webhook
functions, the app's. Work kept after the commit that fails is written
there, `:error :groundwork-postgres/after-commit-failed`.

```clojure
:file-storage   {:storage {…}                                                   ; groundwork-file-storage's settings, every key
          :upload  {:path "/groundwork/files" :key-prefix "uploads" :allowed-content-types #{"image/png" "image/jpeg"}
                    :max-file-bytes 10000000 :max-files 10}}
:email {:sending  {:provider {:name :log :level :info}                ; or {:name :postmark …}
                     :tags #{:orders/confirmation} :background-job {:timeout-ms 60000 :attempts 8}}
          :webhooks {:delivery-path "/email/delivery" :inbound-path "/email/inbound"
                     :token #secret :groundwork-email/webhook-token :inbound-domain "in.shop.example"}}
```

An app's table map says `:access` and `:live-updates`, never `:gates` or
`:on-change` (groundwork-access and groundwork-live-updates put them): one that writes either is
refused at the start, named:
[the decision](../../../../decisions/2026-10-23-an-apps-table-that-writes-what-house-puts-is-refused.md).
It writes out groundwork-postgres's `:indexes` (each a map), `:uniques`, `:checks`,
`:references` and `:triggers`, `[]` for none, and each reference what it
does on delete, on update and when it is checked; a record under a record
says groundwork-access's `:parent`, and groundwork-access adds its link and fill to them.
Beside `:tables` the app writes out groundwork-postgres's `:extensions`, `:functions`
and `:views` (`.sql` files, `[]` for none), which migrate declares with
groundwork-access's and groundwork-live-updates's own functions and those groundwork-access makes from the
sentences (`make-access-functions`).

API: `groundwork.server.main.api` (`settings-shape`, `page-settings-shape`,
`health-settings-shape`, `migration-settings-shape`, `live-updates-settings-shape`,
`my-actions-settings-shape`, `run-once-settings-shape`, `daily-caps-settings-shape`, `visitors-settings-shape`,
`browser-build-settings-shape`, `browser-shaped-json-settings-shape`,
`file-storage-settings-shape`, `email-settings-shape`,
`check-settings`, `groundwork-tables`, `make-ready-tables`, `migrate-app!`,
`start-app!`, `stop-app!`, `make-leak-test-answerer`: groundwork-access's leak test's
`:answer-message`, groundwork-bridge's runner plugged in, as over http, on a meter of its
own). Decisions:
[`decisions/server/main`](../../../../decisions/server/main/README.md).

The settings are one EDN file per environment, loaded by
[groundwork-settings](https://github.com/p4ulcristian/groundwork-settings) (`settings/load`): its `#secret`
references read from its sops file, its `#env` from the environment,
checked against `settings-shape`, every problem at once. Every secret in
them (`:postgres :password`, `:errors :token`, `:metrics :token`, the
bucket's keys, Postmark's and the webhooks' tokens) is groundwork-settings's, which prints
as its name; text written out where a secret belongs is refused:
[the decision](../../../../decisions/2026-10-23-secrets-are-safes-wrapped-secrets.md).

```clojure
(def running (main/start-app! {:settings (settings/load {:file  "settings/production.edn"
                                                     :shape main/settings-shape
                                                     :env   (System/getenv)})
                               :tables [shops orders] :extensions [] :functions [] :views []
                               :changes [] :scope-kinds scope-kinds
                               :handlers handlers :pages pages :browser-modules {}
                               :background-job-kinds [] :schedules []
                               :routes {}   ; the app's own server routes, records with a kind
                               :render-page render-page   ; each page's title, description, head and body
                               :may-upload? (fn [{:keys [person-id]}] (some? person-id))
                               :on-delivery-event {} :on-inbound-email {}}))
(main/stop-app! running)

;; in the app's tests: the leak test, each message answered as over http
(is (= [] (access/find-leaks (:handlers running) anna-request {:orders [order-in-another-shop]}
                              {:answer-message (main/make-leak-test-answerer running)
                               :scope-kinds scope-kinds :seed 7 :size 30})))
```

The app's own server routes (`:routes`), for what isn't a message or a
page: a provider's webhook, a login's callback, a machine's API. Each is a
record with a kind, served beside groundwork's routes and behind the same
language step
([the decision](../../../../decisions/2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md)):

```clojure
:routes {:stripe/webhook {:address "/stripe-webhook" :method :post :kind :provider
                          :rate-limit {:max 30 :per-ms 60000}
                          :handler (fn [request {:keys [log run-as-system!]}] …)}   ; the raw body, unread
         :flex/authorize {:address "/flex/authorize" :method :get :kind :person :rate-limit nil
                          :handler (fn [request {:keys [asker run-as-asker! sign-in!]}]
                                     {:status 302 :headers {"location" "/flex"
                                                            "set-cookie" (:set-cookie (sign-in! person-id #{}))}})}}
```

A `:person` route is a person's, from their browser (the session's
cookie) or a script (a token groundwork-access made for them, `Authorization:
Bearer`). It runs groundwork-access's session step first, which finds who asks: by a
cookie a request that isn't a GET must come from the app's own pages, a
request with both a cookie and a token is refused, else 403, its body the
text of `:groundwork-access/not-allowed` in the request's language. The handler
gets what groundwork-access found (`asker`), `run-as-asker!` (a transaction as the
request's asker, held by the gates, carrying the route's log), `answer-as` (a message answered as
the request's asker, through every check a browser's message gets and each
handler's rate limit, counted per token or cookie), `sign-in!` and
`sign-out!`. A `:provider` route reads no session and no token and
proves its caller itself; it has no asker. No code names a person: a token
call goes where a browser's does, the messages' own route or a `:person`
route
([the decision](../../../../decisions/2026-10-23-a-token-call-arrives-where-a-browsers-does.md)):

```clojure
;; MCP's JSON-RPC, turned into a message and answered as the token's person
(fn [request {:keys [answer-as]}]
  {:status 200 :body (json-text (answer-as {:orders/of-shop {"shop/id" "…"}}   ; JSON data, as the browser sends it
                                           request))})
``` An address groundwork serves, or two routes at one address
and method, refuse the start.

Its test starts the whole server as production does (`migrate-app!` as the
owner, `start-app!` as a role that owns nothing) and follows a new order
through groundwork-live-updates to every open sse line, as only its table, and each person's
asking again through the gates to only their own shop's orders; then the health checks: live
and ready without origin or session; ready 503 at its timeout while every
connection is held, 503 while the app's role can't log in, 200 again after
each; 503 while stopping; the metrics route, the visitor route and the
REPL's tools over a real nREPL connection, an upload (who may, and one
stored when a bucket is given), an email queued in a transaction and sent
by the workers, and its bounce handed to the app by its tag. The end-to-end test
(`groundwork.end-to-end-test`) drives a sample app in a headless browser.
