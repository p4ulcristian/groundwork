# house

An opinionated Clojure framework for web apps on Postgres: the part of
**manor** that puts its libraries together into one running app. manor is the
group: house and its cellar, backroom, showroom, mailroom, doorman, doorbell,
parlor, dictionary, wire, logbook, meter and study. The spec is [`decisions/`](decisions/README.md).

One tree: each library is a folder `source-code/house/<side>/<name>/` with
its README, its tests in `test-code/house/<side>/<name>/`, its decisions in
`decisions/<side>/<name>/`; one `deps.edn` at the root.

| library | namespace | the question it answers |
|---|---|---|
| server/main | `house.server.main` | how does the server start? (and the page shell) |
| browser/main | `house.browser.main` | how does the browser start? |

Every namespace says its side right after `house`; no file is `.cljc`.
Which library may use which is `libraries.edn`, checked by a test.

How data keeps its types (ids, exact decimals, days, moments) is
[wire](https://github.com/p4ulcristian/wire), a library of its own that house
takes from git; its decisions live there. house makes the app's one wire on
each side, from the `:wire` settings on the server and the `:browser-wire`
settings (in the page) with the browser start's `:exact-decimals`, and
passes it to every library; none makes its own. So is talking to Postgres and shaping
its tables: [cellar](https://github.com/p4ulcristian/cellar), every query a
`.sql` file, tables as maps; its pool settings are the `:database` part, and a
migration's time limits the `:migration` part, with no defaults. And what runs
later, background jobs on Postgres:
[backroom](https://github.com/p4ulcristian/backroom), a queue, workers and
schedules; its settings are the `:background-jobs` part. And what the browser downloads and how the page looks:
[showroom](https://github.com/p4ulcristian/showroom), the shadow-cljs build
(watched in development, built once by the deploy) and the stylesheets a
namespace names, bundled; taken only in the build aliases, never in production;
its watch options are the `:browser-build {:watch …}` setting, its release
options the `:browser-release` alias's `:exec-args`.
And how the browser and the server talk, on both sides:
[mailroom](https://github.com/p4ulcristian/mailroom), named messages over http,
sse and ws answered by a handler's steps, and the routes; its addresses and
ping are in the `:server` settings, with no defaults.
And who is asking and what they may do, at one door:
[doorman](https://github.com/p4ulcristian/doorman), sessions in Postgres and
access (scopes, roles, memberships, the check in front of every handler, the
gates, the leak test); its settings are the `:sessions` part, with no defaults.
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
touched, and "can I?" from doorman's `:doorman/my-actions`, a handler house
adds (its rate limit the `:my-actions` part). house's browser start starts it;
the app draws its root with parlor's `mount-root!` into the shell's `#app`.
Every write runs once: parlor sends each with a one-time key (a random
UUID, the same on each retry of that send, in the `idempotency-key`
header), and house claims it in the write's own transaction; a repeat gets
the first answer and saves nothing (the `:run-once` settings, [the
decision](decisions/2026-10-23-every-write-runs-once-by-its-one-time-key.md)).
And what the app says, on the server:
[logbook](https://github.com/p4ulcristian/logbook), one function
`(log level event data)`; house makes the app's one logbook from the
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
catalog per language, read by the server at the start (`:texts`, for
emails and PDFs) and served to the browser, where parlor's
`[:parlor/text key args]` draws them. The routing has queries (read by a
page's shape), scrolling, a question before leaving unsaved changes, and
modules loaded on demand (the app's `:browser-modules`, showroom's, each
with its stylesheets): [the decision](decisions/2026-10-23-routing-extras-each-where-it-belongs.md).
And how much the app does, on the server:
[meter](https://github.com/p4ulcristian/meter); house makes the app's one
meter from the `:meter` settings and hands it to cellar's pool, mailroom's
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
house's tools are in `study.tools`: `ask-as-person`, `why-refused`
(doorman's `explain-refusal`) and `explain-setting`
([the decision](decisions/2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md)).
A visitor (someone not signed in) gets a session of their own, doorman's,
from a POST of house's at the `:visitors` path, which the browser's
`start-visitor-session!` sends the first time the app keeps something for
them ([the decision](decisions/2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md)).
An app's table map says `:access` and `:doorbell`; one that writes its own
`:gates` or `:on-change`, which house puts on it, is refused at the start
([the decision](decisions/2026-10-23-an-apps-table-that-writes-what-house-puts-is-refused.md)).
Every manor repository carries the GitHub topic `manor`:
`user:p4ulcristian topic:manor` lists them all.

Still to come: a rate limit on the visitor route, clean-ups (ended
visitors' rows, ended sessions as a background job).

```sh
clojure -X:test                                                    # every test; Postgres ones need
                                                                   # HOUSE_SQL_TEST_PORT and _USER (Postgres 16+)
clojure -X:test :dirs '["test-code/house/server/main"]'            # one library
clj -A:test                                                        # one REPL, every library
```

The end-to-end test (`test-code/house/end_to_end_test.clj`): a sample app
(`test-code/house_test/sample/`, shops and orders, Anna and Bob, English and
German, a module loaded on demand) release-built by showroom, started by
`start-app!` on a throwaway Postgres and driven by a headless browser over
the DevTools protocol; no window opens
([the decision](decisions/2026-10-23-the-end-to-end-test-drives-a-headless-browser-over-devtools.md)).
It needs node's packages once (`npm install --include=dev`, for the
release build) and a headless browser binary (Playwright's headless shell,
`npx playwright install chromium-headless-shell`, or Chrome):

```sh
initdb -D /tmp/house-pg -A trust -U "$USER"
pg_ctl -D /tmp/house-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/house-pg.log start
HOUSE_SQL_TEST_PORT=5499 HOUSE_SQL_TEST_USER=$USER \
HOUSE_E2E_BROWSER=~/.cache/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell \
clojure -X:test :nses '[house.end-to-end-test]'
pg_ctl -D /tmp/house-pg stop
```

An app takes all of house from git.
