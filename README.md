# house

An opinionated Clojure framework for web apps on Postgres: the part of
**manor** that puts its libraries together into one running app. manor is the
group: house and its cellar, backroom, showroom, mailroom, doorman, doorbell
and wire. The spec is [`decisions/`](decisions/README.md).

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
takes from git; its decisions live there. So is talking to Postgres and shaping
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
Every manor repository carries the GitHub topic `manor`:
`user:p4ulcristian topic:manor` lists them all.

Still to come: the smaller list (visitor sessions, access extras, rate limits, clean-ups).

```sh
clojure -X:test                                                    # every test; Postgres ones need
                                                                   # HOUSE_SQL_TEST_PORT and _USER (Postgres 16+)
clojure -X:test :dirs '["test-code/house/server/main"]'            # one library
clj -A:test                                                        # one REPL, every library
```

An app takes all of house from git.
