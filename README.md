# groundwork

An opinionated Clojure framework for web apps on Postgres: the repository
that puts the **groundwork** libraries together into one running app.
groundwork names both the family and this composer: groundwork and its cellar,
backroom, browser-build, bridge, doorman, doorbell, parlor, dictionary, wire,
logbook, meter, study, attic, postbox and safe. The spec is [`decisions/`](decisions/README.md).

One tree: each library is a folder `source-code/groundwork/<side>/<name>/` with
its README, its tests in `test-code/groundwork/<side>/<name>/`, its decisions in
`decisions/<side>/<name>/`; one `deps.edn` at the root.

| library | namespace | the question it answers |
|---|---|---|
| server/main | `groundwork.server.main` | how does the server start? (and the page shell) |
| browser/main | `groundwork.browser.main` | how does the browser start? |

Every namespace says its side right after `groundwork`; no file is `.cljc`.
Which library may use which is `libraries.edn`, checked by a test.

How data keeps its types (ids, exact decimals, days, moments) is
[wire](https://github.com/p4ulcristian/wire), a library of its own that groundwork
takes from git; its decisions live there. groundwork makes the app's one wire on
each side, from the `:wire` settings on the server and the `:browser-wire`
settings (in the page) with the browser start's `:exact-decimals`, and
passes it to every library; none makes its own. So is talking to Postgres and shaping
its tables: [cellar](https://github.com/p4ulcristian/cellar), every query a
`.sql` file, tables as maps; its pool settings are the `:database` part, and a
migration's time limits the `:migration` part, with no defaults. And what runs
later, background jobs on Postgres:
[backroom](https://github.com/p4ulcristian/backroom), a queue, workers and
schedules; each job kind names its `:system-actor`, and its one
transaction, the work and its done-mark, is doorman's `run-as-system!` as
that actor, which reaches what its sentences give it; its settings are the
`:background-jobs` part. And what the browser downloads and how the page looks:
[browser-build](https://github.com/p4ulcristian/browser-build), the shadow-cljs build
(watched in development, built once by the deploy) and the stylesheets a
namespace names, bundled; taken only in the build aliases, never in production;
its watch options are the `:browser-build {:watch …}` setting, its release
options the `:browser-release` alias's `:exec-args`.
And how the browser and the server talk, on both sides:
[bridge](https://github.com/p4ulcristian/bridge), named messages over http,
sse and ws answered by a handler's steps, and the routes; its addresses and
ping are in the `:server` settings, with no defaults. A refusal is a named
reason with data, declared on its handler (`:refusals`, `{}` for none),
never English on the wire; every reason, the libraries' own (bridge's,
doorman's, groundwork's run-once ones) too, needs its text in every
catalog, or the start is refused, and the browser draws it with
`(parlor/tr reason data)`. Every answered message is counted on the app's
meter, whichever door it came through (an app route's `answer-as`, the
REPL's `ask-as-person`).
And who is asking and what they may do, at one door:
[doorman](https://github.com/p4ulcristian/doorman), sessions in Postgres, a
person's own tokens for scripts (a call by `Authorization: Bearer` is that
person's) and access (scopes, roles by id, memberships holding a set of
roles, the check in front of every handler, which says where it acts, the
gates, the leak test); its
settings are the `:sessions` part, with no defaults.
And how screens stay current, on both sides:
[doorbell](https://github.com/p4ulcristian/doorbell), a change to a table rings
every open screen with only the table's name, and the screens that read it ask
again through the gates; table maps say `:doorbell :tell` or `[:silent "why"]`,
and the `:doorbell` settings hold the browser's question store's (`:store`) and
the server's listener's (`:listening`), with no defaults.
And what the browser shows:
[parlor](https://github.com/p4ulcristian/parlor), re-frame over doorbell's
question store, drawn with Reagent: a screen's answers with four statuses,
the page the address bar leads to (each page declares its questions, the
browser start's `:questions-of-pages`), writes that ask again what they
touched, and "can I?" from doorman's `:doorman/my-actions`, a handler groundwork
adds (its rate limit the `:my-actions` part). groundwork's browser start starts it;
the app draws its root with parlor's `mount-root!` into the shell's `#app`.
Every write runs once: parlor sends each with a one-time key (a random
UUID, the same on each retry of that send, in the `idempotency-key`
header), and groundwork claims it in the write's own transaction; a repeat gets
the first answer and saves nothing (the `:run-once` settings, [the
decision](decisions/2026-10-23-every-write-runs-once-by-its-one-time-key.md)).
Every handler writes out its daily caps, `:daily-caps` (`[]` for none),
counted by groundwork in Postgres after doorman's check; over one, the call
is refused `:groundwork/daily-cap-reached {:retry-after-ms …}` until
midnight UTC (the `:daily-caps` settings, [the
decision](decisions/2026-10-23-daily-caps-are-counted-by-the-composer-after-the-permission.md)).
And what the app says, on the server:
[logbook](https://github.com/p4ulcristian/logbook), one function
`(log level event data)`; groundwork makes the app's one logbook from the
`:logbook` settings and passes its log to every library, each message's steps
get one with its request id (and the person, once doorman finds them), and a
monitor pulls the kept errors, the browser's among them, from the `:errors`
route.
And which language a request, a page and an address are in, on both sides:
[dictionary](https://github.com/p4ulcristian/dictionary); its settings are
the `:languages` part. Every request gets its language (the address's
prefix, the cookie, Accept-Language, the default) and loses the prefix
before routing; the shell's `<html lang>` and alternate links follow it,
and parlor keeps it on every move. Texts are ICU messages in a JSON
catalog per language, read by the server at the start and served to the
browser, where parlor's `tr`, called only in views, draws them. On
the server every request is handled with its language bound, so
`(dictionary/tr key args)` in a handler's steps, a page's `render-page`
or an app's own route is in the request's language; the running app's
`:texts` is for what runs outside a request: an email, a PDF or a job
binds the language of its row, `(dictionary/with-language texts "de" …)`. The routing has queries (read by a
page's shape), scrolling, a question before leaving unsaved changes, and
modules loaded on demand (the app's `:browser-modules`, browser-build's, each
with its stylesheets): [the decision](decisions/2026-10-23-routing-extras-each-where-it-belongs.md).
And how much the app does, on the server:
[meter](https://github.com/p4ulcristian/meter); groundwork makes the app's one
meter from the `:meter` settings and hands it to cellar's pool, bridge's
routes, doorbell's listener and backroom's workers, each declaring its own
metrics (their histograms' buckets are each library's `:metric-buckets`
setting), adds its own (the stage, the ready checks, the version) and
logbook's counts, and serves the text Prometheus pulls at the `:metrics`
route, beside health and errors, behind its bearer token
([the decision](decisions/2026-10-23-main-makes-one-meter-and-serves-its-text.md)).
And a REPL into the running app, on the server:
[study](https://github.com/p4ulcristian/study), by the `:study` settings
(`:development`, `:ssh-only`, or `:off` with the emergency switch), every
evaluation written to the app's log; it starts last and stops first, and
groundwork's tools are in `study.tools`: `ask-as-person`, `why-refused`
(doorman's `explain-refusal`) and `explain-setting`
([the decision](decisions/2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md)).
And the app's files, on the server:
[attic](https://github.com/p4ulcristian/attic), each named by its content
in an S3 bucket (R2, S3), recorded in `attic.files`, read through signed
links; groundwork makes one attic from the `:attic :storage` settings, held by
the running app as `:attic`, and serves attic's upload route at `:attic
:upload :path`, asking doorman's session step, then the app's
`:may-upload?` `(fn [asker])`, before the body is read. The app deletes a
file with attic's `delete-file!`, after its own rows are kept: a file goes,
row and object, only when no row names it through a reference to
`:file/key`.
And its email, on the server:
[postbox](https://github.com/p4ulcristian/postbox), an email the app wrote
queued in a write's transaction (`queue-email!` on the running app's
`:postbox`) as a background job, sent once by Postmark or to the log
(`:postbox :sending`); the provider's webhooks (`:postbox :webhooks`) hand
bounces to the app's `:on-delivery-event` by the email's tag and inbound
email to its `:on-inbound-email` by mailbox
([the decision](decisions/2026-10-23-main-makes-one-attic-and-one-postbox.md)),
each run as doorman's system actor `:postbox`, which the app's sentences
give what it may do, `{:allow :shop-emails/mark-bounced :only :all-of-the-action :to [:system :postbox]}`,
and nothing else ([the decision](decisions/2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md)).
And its settings and their secrets:
[safe](https://github.com/p4ulcristian/safe), one EDN file per environment,
its `#secret` references read from sops or 1Password, its `#env` from the
environment, checked against groundwork's `settings-shape` with every problem at
once. Every secret in the settings (the database's password, the `:errors`
and `:metrics` tokens, the bucket's keys, Postmark's and the webhooks'
tokens) is safe's: it prints as its name, is revealed only by the library
that uses it, and text written out in its place is refused. An app starts:

```clojure
(main/start-app! {:settings (safe/load {:file  "settings/production.edn"
                                        :shape main/settings-shape
                                        :env   (System/getenv)})
                  …})
```

The sample app's development settings load the same way
(`test-code/groundwork_test/sample/settings/development.edn`, its secrets opened
by a committed, development-only age key)
([the decision](decisions/2026-10-23-secrets-are-safes-wrapped-secrets.md)).
A visitor (someone not signed in) gets a session of their own, doorman's,
from a POST of groundwork's at the `:visitors` path, which the browser's
`start-visitor-session!` sends the first time the app keeps something for
them ([the decision](decisions/2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md)),
at most `:visitors :rate-limit` times per address, 429 over it
([the decision](decisions/2026-10-23-the-visitor-route-is-rate-limited-by-address-with-mailrooms-buckets.md)).
An app's own server routes (a provider's webhook, a login's callback, an
API for machines) are records in the app map's `:routes` (`{}` for none),
each with a kind, served beside groundwork's own through the same steps
([the decision](decisions/2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md),
[the kinds](decisions/2026-10-23-route-kinds-are-person-and-provider.md)):
a `:person` route's handler gets the asker doorman found, by the session's
cookie or the person's token, and its tools, among them `:run-as-asker!`
and `:answer-as`, `(answer-as message request)`, a message answered as
that asker through every check a browser's message gets, each handler's
rate limit counted on a limiter of its own
([the decision](decisions/2026-10-23-a-token-call-arrives-where-a-browsers-does.md));
a `:provider` route reads no session or token and proves its caller
itself (a signature).
An app's table map says `:access` and `:doorbell`; one that writes its own
`:gates` or `:on-change`, which groundwork puts on it, is refused at the start
([the decision](decisions/2026-10-23-an-apps-table-that-writes-what-house-puts-is-refused.md)).
It writes out cellar's `:indexes` (each a map), `:uniques`, `:checks`,
`:references` and `:triggers` (`[]` for none), and each reference what it
does (`{:to :shop/id :on-delete :refuse :on-update :refuse :checked-at
:statement}`); a record under a record says doorman's `:parent {:column …
:carries {…}}`, and doorman adds the link to its parent and the fill of its
carried columns to them. Beside `:tables` the app writes out cellar's
`:extensions`, `:functions` and `:views` (`.sql` files, `[]` for none);
groundwork declares doorman's and doorbell's own functions with them, and
the ones doorman makes from the sentences (`make-access-functions`, cellar's
made functions), and cellar drops a function in the tables' folders that
nothing declared makes.
Not in groundwork: pre-rendering pages for search engines, for now
([the decision](decisions/2026-10-23-no-pre-rendering-of-pages-for-search-engines-for-now.md)),
and an MCP endpoint, which is the app's own code
([the decision](decisions/2026-10-23-mcp-stays-the-apps-own-code.md)).
Every groundwork repository carries the GitHub topic `groundwork`:
`user:p4ulcristian topic:groundwork` lists them all.

Still to come: clean-ups (ended visitors' rows, ended sessions as a
background job).

```sh
clojure -X:test                                                    # every test; Postgres ones need
                                                                   # GROUNDWORK_SQL_TEST_PORT and _USER (Postgres 16+)
clojure -X:test :dirs '["test-code/groundwork/server/main"]'            # one library
clj -A:test                                                        # one REPL, every library
```

The end-to-end test (`test-code/groundwork/end_to_end_test.clj`): a sample app
(`test-code/groundwork_test/sample/`, shops and orders, Anna and Bob, English and
German, a module loaded on demand) release-built by browser-build, started by
`start-app!` on a throwaway Postgres and driven by a headless browser over
the DevTools protocol; no window opens
([the decision](decisions/2026-10-23-the-end-to-end-test-drives-a-headless-browser-over-devtools.md)).
It needs node's packages once (`npm install --include=dev`, for the
release build) and a headless browser binary (Playwright's headless shell,
`npx playwright install chromium-headless-shell`, or Chrome):

```sh
initdb -D /tmp/groundwork-pg -A trust -U "$USER"
pg_ctl -D /tmp/groundwork-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/groundwork-pg.log start
GROUNDWORK_SQL_TEST_PORT=5499 GROUNDWORK_SQL_TEST_USER=$USER \
GROUNDWORK_E2E_BROWSER=~/.cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell \
clojure -X:test :nses '[groundwork.end-to-end-test]'
pg_ctl -D /tmp/groundwork-pg stop
```

The server test stores an upload in a real S3 API when
`GROUNDWORK_S3_TEST_ENDPOINT`, `_ACCESS_KEY` and `_SECRET_KEY` are set (SeaweedFS
in a container, as attic's tests run it); without them it checks only who
may upload.

An app takes all of groundwork from git.

## Releasing a library

`tools/bump cellar` (or several names) tags the next patch version of each
named library, then pins the new tag in every library that uses it, releases
those the same way, in order, and pins everything in groundwork last. No tests
are run. The libraries sit beside groundwork, in ~/Work/apps/groundwork.
