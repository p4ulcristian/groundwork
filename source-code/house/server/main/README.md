# house.server.main

The server's one start: `start-app!` takes the app as one map (settings,
tables, changes, scope kinds, handlers, pages, background job kinds,
schedules), checks the settings (each library brings its part; every problem
at once), makes the app's one wire from the `:wire` settings and passes it to
mailroom's routes, backroom's workers, the page and the health checks (no
library makes its own; the running app holds it, `:wire`), makes the app's
one logbook from the `:logbook` settings and passes its log to every library
that writes a line (the running app holds both, `:logbook` and `:log`;
[the decision](../../../../decisions/2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md)), migrates in development or refuses an unsafe or old database in
production, and starts the pool, the handlers (doorman's check, house's
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
`:browser-wire`, pages) as
JSON in the page; its viewport, referrer policy, frame options and content
security policy are `:page` settings. Every value that sets behaviour is a
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
unhandled rejections come in as a message, `:house/browser-error`, a handler
of house's own (doorman's session step, then `:error :browser/uncaught`),
limited per asker by `:browser-rate-limit`, and are kept with the server's:
[the decision](../../../../decisions/server/main/2026-10-22-main-serves-the-kept-errors-and-logs-the-browsers.md).

```clojure
:errors {:path "/house/errors" :token "…" :browser-rate-limit {:max 10 :per-ms 60000}}
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

```clojure
:logbook         {:lowest-level :info :format :json                      ; logbook's; development :debug, :readable
                  :errors-kept 1000 :write-to :stdout}
:wire            {:most-shapes-kept 1000                                 ; wire's settings on the JVM
                  :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                                     :max-name-length 50000 :max-document-length nil :max-token-count nil
                                     :max-writing-nesting-depth 1000}}
:browser-wire    {:most-shapes-kept 1000 :most-keys-kept 10000}          ; the browser's, but :exact-decimals
:database        {…}                                                     ; cellar's pool settings, all 20 keys
:migration       {:instant-part {:statement-timeout-ms 30000 :lock-timeout-ms 5000}
                  :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}}
:background-jobs {:workers 4 :poll-ms 1000
                  :retry-first-wait-ms 30000 :retry-wait-factor 2 :retry-max-wait-ms 3600000
                  :stuck-after-time-limit-ms 60000 :forget-done-after-ms 604800000 :stop-wait-ms 30000}
:doorbell        {:store     {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
                  :listening {:batch-window-ms 50 :check-stopped-every-ms 500
                              :reconnect-after-ms 1000 :stop-wait-ms 2000}}
:browser-build   :built                                                  ; or {:watch {…}}: showroom's watch options
                                                                         ; but :entry, :files-folder and :log
```

API: `house.server.main.api` (`settings-shape`, `page-settings-shape`,
`health-settings-shape`, `migration-settings-shape`, `doorbell-settings-shape`,
`my-actions-settings-shape`,
`browser-build-settings-shape`, `browser-wire-settings-shape`,
`check-settings`, `house-tables`, `make-ready-tables`, `migrate-app!`,
`start-app!`, `stop-app!`). Decisions:
[`decisions/server/main`](../../../../decisions/server/main/README.md).

```clojure
(def running (main/start-app! {:settings settings :tables [shops orders] :changes [] :scope-kinds scope-kinds
                               :handlers handlers :pages pages :background-job-kinds [] :schedules []}))
(main/stop-app! running)
```

Its test starts the whole server as production does (`migrate-app!` as the
owner, `start-app!` as a role that owns nothing) and follows a new order
through doorbell to every open sse line, as only its table, and each person's
asking again through the gates to only their own shop's orders; then the health checks: live
and ready without origin or session; ready 503 at its timeout while every
connection is held, 503 while the app's role can't log in, 200 again after
each; 503 while stopping.
