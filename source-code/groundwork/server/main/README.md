# groundwork.server.main

The server's one start: `start-app!` takes the app as one map (settings,
tables, changes, scope kinds, handlers, pages, background job kinds,
schedules), checks the settings (each library brings its part; every problem
at once), makes the app's one wire from the `:wire` settings and passes it to
mailroom's routes, backroom's workers, the page and the health checks (no
library makes its own; the running app holds it, `:wire`), makes the app's
one logbook from the `:logbook` settings and passes its log to every library
that writes a line (the running app holds both, `:logbook` and `:log`;
[the decision](../../../../decisions/2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md)), migrates in development or refuses an unsafe or old database in
production, and starts the pool, the handlers (doorman's check, groundwork's
`:doorman/my-actions`, then doorbell's notes),
doorbell's listener (the `:doorbell :listening` settings), backroom's
background job workers (`:background-jobs`), in development showroom's
watcher of the browser build (`:browser-build {:watch options}`, checked by
showroom before anything starts), and mailroom's server (its
`:server` settings, mailroom's, every key required; askers told apart by
session, else address; sse hooks for doorbell, the ws hooks nil; files from
`:page :files`). `migrate-app!` is for the deploy, as the owner; the
`:migration` settings are each part's time limits. The page
shell answers every page address: the app's scripts and styles, and the
browser's settings (version, addresses, sse and ws reopen delays, the
question store's settings, `:doorbell :store`, the browser's wire settings,
`:browser-wire`, the write retries, `:run-once :retry-writes`, pages) as
JSON in the page; its viewport, referrer policy, frame options and content
security policy are `:page` settings. The shell is in the request's
language: every request first gets it from dictionary (the `:languages`
settings: the address's prefix, taken off before routing, the cookie,
Accept-Language, the default), and the shell's `<html lang>`, its
alternate links (at the `:sessions` `:allowed-origin`) and its JSON's
`:language` follow it; the JSON also carries the languages and each module's
stylesheet bundle (`:module-stylesheets`), and the shell lists `main`'s
files only: a page's `:module`, one of the app's `:browser-modules`, loads
on demand ([the decision](../../../../decisions/2026-10-23-routing-extras-each-where-it-belongs.md)).

Each language's catalog (`<catalogs-path>/<language>.json` on the
classpath) is read at the start, held by the running app as `:texts` for
the server's own texts (`dictionary/make-text`, the language a value), and
served where the browser fetches it, `/<catalogs-path>/<language>.json`
([the decision](../../../../decisions/2026-10-23-house-reads-the-catalogs-at-the-start-and-serves-them.md)).

```clojure
:languages {:languages ["en" "de"] :default "en" :cookie-name "lang"   ; dictionary's; :cookie-name nil for none
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

One more GET, for a monitor, outside them too: the `:error` lines logbook
keeps, after `?since=N`, as a JSON array, only with the `:errors` settings'
token as a bearer token (else 401). The browser's uncaught errors and
unhandled rejections come in as a message, `:groundwork/browser-error`, a handler
of groundwork's own (doorman's session step, then `:error :browser/uncaught`),
limited per asker by `:browser-rate-limit`, and are kept with the server's:
[the decision](../../../../decisions/server/main/2026-10-22-main-serves-the-kept-errors-and-logs-the-browsers.md).

```clojure
:errors {:path "/groundwork/errors" :token #secret :errors/token :browser-rate-limit {:max 10 :per-ms 60000}}
```

One handler of doorman's, `:doorman/my-actions` (`make-my-actions-handler`):
the asker's actions by scope, and the open ones under nil kind and id, which
the browser's start names as parlor's `:actions-question`; an app's handler
of that name is refused. doorman's access tables tell, so a membership or a
role changed asks it again:
[the decision](../../../../decisions/2026-10-23-the-browser-start-starts-parlor.md).

```clojure
:my-actions {:rate-limit {:max 60 :per-ms 60000}}   ; or nil for none
```

Every write runs once (`groundwork.server.main.run-once`): an app handler whose
action may write (doorman's `does-action-only-read?` says no) has its steps
wrapped in one step, after doorman's check, in its transaction, which
claims the request's one-time key (the `idempotency-key` header, a UUID)
for the message's name in `groundwork.run_once_keys`. A repeat by the same
person gets the first answer and saves nothing; one while the first runs
waits for it; someone else's is refused. Keys older than `:keep-keys-ms`
count as forgotten, and groundwork's background job `:groundwork/forget-old-once-keys`
deletes them on its cron line. A write's answer must fit its shape or
nothing is saved. A write without a key runs as it is:
[the decision](../../../../decisions/2026-10-23-every-write-runs-once-by-its-one-time-key.md).

```clojure
:run-once {:keep-keys-ms 86400000
           :forget-keys  {:cron "*/15 * * * *" :timeout-ms 60000 :attempts 3}
           :retry-writes {:times 2 :wait-ms 1000}}   ; the browser's, in the page; ends before a key is forgotten
```

```clojure
:logbook         {:lowest-level :info :format :json                      ; logbook's; development :debug, :readable
                  :errors-kept 1000 :write-to :stdout}
:wire            {:most-shapes-kept 1000                                 ; wire's settings on the JVM
                  :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                     :max-name-length 50000 :max-document-length nil :max-token-count nil
                                     :max-writing-nesting-depth 1000}}
:browser-wire    {:most-shapes-kept 1000 :most-keys-kept 10000}          ; the browser's, but :exact-decimals
:database        {…                                                      ; cellar's pool settings, all 21 keys
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
:server          {…                                                      ; mailroom's settings, every key
                  :metric-buckets {:answer-seconds [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10]}}
:sessions        {:cookie-name "app_session" :session-days 30 :visitor-session-days 7   ; doorman's
                  :renew-after-minutes 60 :allowed-origin "https://shop.example" :secure-cookie? true}
:doorbell        {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                  :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                              :reconnect-after-ms 1000 :stop-wait-ms 2000}}
:browser-build   :built                                                  ; or {:watch {…}}: showroom's watch options
                                                                         ; but :entry, :files-folder and :log
```

One meter, made from the `:meter` settings and handed to cellar's pool,
mailroom's routes, doorbell's listener and backroom's workers; groundwork's
own metrics (`groundwork_stage{stage}`, `groundwork_ready_checks_total{outcome}`,
`groundwork_build_info{version}`) and logbook's counts read when the text is
written (`logbook_lines_total{level}`, `logbook_write_failures_total`,
`logbook_errors_kept`); the text at the `:metrics` path, outside sessions,
access and rate limits, only with its bearer token (else 401).
`:most-series-per-metric` covers the largest declaration: mailroom's
`messages_total`, (handlers+1)×24; its `answer_seconds` and cellar's
`query_seconds`, (names+1)×(buckets+3). `migrate-app!`'s owner pool counts
on a meter of its own, never served:
[the decision](../../../../decisions/2026-10-23-main-makes-one-meter-and-serves-its-text.md).

```clojure
:meter   {:jvm-metrics true :most-series-per-metric 5000}
:metrics {:path "/metrics" :token #secret :meter/token}
```

A visitor's session: a POST at the `:visitors` path, from the app's own
Origin only (else 403), doorman's `start-visitor-session!`, answered
`{"visitor-id": "…"}` with its Set-Cookie; the page carries the path to the
browser's `start-visitor-session!`:
[the decision](../../../../decisions/2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md).

```clojure
:visitors {:path "/groundwork/visitor"}
```

The REPL, study's, by the `:study` settings, started last and stopped
first, groundwork's tools in `study.tools`: `(ask-as-person message asker)`,
`(why-refused asker handler-name params)`, `(explain-setting path)`, the
asker `{:person-id :visitor-id :session-flags}`:
[the decision](../../../../decisions/2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md).

```clojure
:study {:mode :development :bind-address "127.0.0.1" :port 7888 :middleware [] :port-file ".nrepl-port"}
;; stage {:mode :ssh-only …}; production {:mode :off :emergency-switch {…}} or {:mode :off :emergency-switch nil}
```

The app's files and email: one attic (attic's `make-attic`, the `:attic
:storage` settings) and one postbox (postbox's `make-postbox`, the
`:postbox :sending` settings), on the app's log and meter (and wire),
held by the running app as `:attic` and `:postbox`; `attic.files` and
`postbox.emails` migrated with groundwork's tables; postbox's background job
kind on the workers. The upload route (`groundwork.server.main.files`) is
attic's handler at `:attic :upload :path`, asking doorman's session step
(the Origin), then the app map's `:may-upload?` `(fn [asker])`, before the
body is read; `:max-file-bytes` must be below `:server :max-body-bytes`.
The provider's webhooks are postbox's, at `:postbox :webhooks`' paths,
outside sessions, handing bounces to the app map's `:on-delivery-event`
`{tag f}` and inbound email to `:on-inbound-email` `{mailbox f}`:
[the decision](../../../../decisions/2026-10-23-main-makes-one-attic-and-one-postbox.md).

```clojure
:attic   {:storage {…}                                                   ; attic's settings, every key
          :upload  {:path "/groundwork/files" :key-prefix "uploads" :allowed-content-types #{"image/png" "image/jpeg"}
                    :max-file-bytes 10000000 :max-files 10}}
:postbox {:sending  {:provider {:name :log :level :info}                ; or {:name :postmark …}
                     :tags #{:orders/confirmation} :background-job {:timeout-ms 60000 :attempts 8}}
          :webhooks {:delivery-path "/postbox/delivery" :inbound-path "/postbox/inbound"
                     :token #secret :postbox/webhook-token :inbound-domain "in.shop.example"}}
```

An app's table map says `:access` and `:doorbell`, never `:gates` or
`:on-change` (doorman and doorbell put them): one that writes either is
refused at the start, named:
[the decision](../../../../decisions/2026-10-23-an-apps-table-that-writes-what-house-puts-is-refused.md).

API: `groundwork.server.main.api` (`settings-shape`, `page-settings-shape`,
`health-settings-shape`, `migration-settings-shape`, `doorbell-settings-shape`,
`my-actions-settings-shape`, `run-once-settings-shape`, `visitors-settings-shape`,
`browser-build-settings-shape`, `browser-wire-settings-shape`,
`attic-settings-shape`, `postbox-settings-shape`,
`check-settings`, `groundwork-tables`, `make-ready-tables`, `migrate-app!`,
`start-app!`, `stop-app!`). Decisions:
[`decisions/server/main`](../../../../decisions/server/main/README.md).

The settings are one EDN file per environment, loaded by
[safe](https://github.com/p4ulcristian/safe) (`safe/load`): its `#secret`
references read from sops or 1Password, its `#env` from the environment,
checked against `settings-shape`, every problem at once. Every secret in
them (`:database :password`, `:errors :token`, `:metrics :token`, the
bucket's keys, Postmark's and the webhooks' tokens) is safe's, which prints
as its name; text written out where a secret belongs is refused:
[the decision](../../../../decisions/2026-10-23-secrets-are-safes-wrapped-secrets.md).

```clojure
(def running (main/start-app! {:settings (safe/load {:file  "settings/production.edn"
                                                     :shape main/settings-shape
                                                     :env   (System/getenv)})
                               :tables [shops orders] :changes [] :scope-kinds scope-kinds
                               :handlers handlers :pages pages :browser-modules {}
                               :background-job-kinds [] :schedules []
                               :may-upload? (fn [{:keys [person-id]}] (some? person-id))
                               :on-delivery-event {} :on-inbound-email {}}))
(main/stop-app! running)
```

Its test starts the whole server as production does (`migrate-app!` as the
owner, `start-app!` as a role that owns nothing) and follows a new order
through doorbell to every open sse line, as only its table, and each person's
asking again through the gates to only their own shop's orders; then the health checks: live
and ready without origin or session; ready 503 at its timeout while every
connection is held, 503 while the app's role can't log in, 200 again after
each; 503 while stopping; the metrics route, the visitor route and the
REPL's tools over a real nREPL connection, an upload (who may, and one
stored when a bucket is given), an email queued in a transaction and sent
by the workers, and its bounce handed to the app by its tag. The end-to-end test
(`groundwork.end-to-end-test`) drives a sample app in a headless browser.
