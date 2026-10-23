# Decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision.

Each file says what we decided, why, and what we chose against.
A decision changes by a new file that replaces the old one; the old one says so at the top.

## Top notes

A replaced or partly changed file says so in its first lines, in one of two forms:

- `> **Replaced** by [title](file.md) (YYYY-MM-DD): what changed, in one short clause.`
- `> **Changed in part** by [title](file.md) (YYYY-MM-DD): which part changed, in one sentence.`

Nothing else in an old file is rewritten. A newer file may say at its top what it replaces, changes or adds to.

## Current spec

The decisions in force: every file not replaced. A file changed in part names what changed it.

### The libraries

Each library keeps its own decisions, in decisions/<side>/<library>/, with its own index:

- [wire](https://github.com/p4ulcristian/wire): how data keeps its types (both sides), a library of its own since [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md); its decisions are there
- [cellar](https://github.com/p4ulcristian/cellar): Postgres, queries and tables as maps, a library of its own since [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md); its decisions are there
- [backroom](https://github.com/p4ulcristian/backroom): background jobs on Postgres, what runs later, a library of its own since [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md); its decisions are there
- [browser-build](https://github.com/p4ulcristian/browser-build) (named showroom until [showroom is renamed browser-build](2026-10-23-showroom-is-renamed-browser-build.md)): what the browser downloads and how the page looks, the build and the stylesheets it bundles, a library of its own since [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md); its decisions are there
- [Groundwork's libraries share the GitHub topic groundwork](2026-10-22-groundworks-libraries-share-the-topic-groundwork.md), changed in part by [Groundwork is renamed house, and the group of libraries is manor](2026-10-22-groundwork-is-renamed-house-and-the-group-is-manor.md) and [The family and its composer are groundwork](2026-10-23-the-family-and-its-composer-are-groundwork.md)
- [bridge](https://github.com/p4ulcristian/bridge): how the browser and the server talk (both sides): named messages over http, sse and ws, a handler's steps, the routes; a library of its own since [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md), named bridge since [mailroom is renamed bridge, and its server is Jetty 12](2026-10-23-mailroom-is-renamed-bridge-and-serves-on-jetty.md); its decisions are there
- [doorman](https://github.com/p4ulcristian/doorman): who is asking and what they may do, sessions and access, a library of its own since [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md); its decisions are there
- [logbook](https://github.com/p4ulcristian/logbook): the app's log, one function `(log level event data)` handed to every library, JSON or readable lines, the errors kept for pulling; a library of its own from the start, which groundwork takes since [main makes one logbook and passes its log to every library](2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md); its decisions are there
- [doorbell](https://github.com/p4ulcristian/doorbell): how screens stay current (both sides): a change to a table rings every open screen, which asks again through the gates; a library of its own since [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md); its decisions are there
- [parlor](https://github.com/p4ulcristian/parlor): what the browser shows, re-frame over doorbell's question store, drawn with Reagent; a library of its own from the start, which groundwork takes since [The browser start starts parlor; the server answers "what may I do?"](2026-10-23-the-browser-start-starts-parlor.md); its decisions are there
- [dictionary](https://github.com/p4ulcristian/dictionary): which language a request, a page and an address are in, and the texts in it (both sides); a library of its own from the start, which groundwork takes since [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md); its decisions are there
- [meter](https://github.com/p4ulcristian/meter): the app's metrics, one meter handed to every library, the text Prometheus pulls behind a token; a library of its own from the start, which groundwork takes since [main makes one meter, hands it to every library, and serves its text](2026-10-23-main-makes-one-meter-and-serves-its-text.md); its decisions are there
- [study](https://github.com/p4ulcristian/study): the REPL into the running app, by environment, every evaluation logged; a library of its own from the start, which groundwork takes since [The REPL starts last and stops first; house brings its tools](2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md); its decisions are there
- [attic](https://github.com/p4ulcristian/attic): the app's files, named by their content in an S3 bucket, recorded in `attic.files`, signed links, uploads checked by their bytes; a library of its own from the start, which groundwork takes since [main makes one attic and one postbox](2026-10-23-main-makes-one-attic-and-one-postbox.md); its decisions are there
- [safe](https://github.com/p4ulcristian/safe): the settings and their secrets, one EDN file per environment, `#secret` from sops or 1Password, a secret printing as its name; a library of its own from the start, which groundwork takes since [Secrets are safe's wrapped secrets; an app loads its settings with safe](2026-10-23-secrets-are-safes-wrapped-secrets.md); its decisions are there
- [postbox](https://github.com/p4ulcristian/postbox): the app's email, queued in the caller's transaction as a background job, sent once by Postmark or the log, bounces and inbound email handed to the app by tag; a library of its own from the start, which groundwork takes since [main makes one attic and one postbox](2026-10-23-main-makes-one-attic-and-one-postbox.md); its decisions are there
- [server/main](server/main/README.md): the one start, the page shell, the health checks, the errors a monitor pulls, the metrics, a visitor's session, the REPL's tools

### What groundwork is

- [The family and its composer are groundwork](2026-10-23-the-family-and-its-composer-are-groundwork.md)
- [Groundwork is renamed house, and the group of libraries is manor](2026-10-22-groundwork-is-renamed-house-and-the-group-is-manor.md), changed in part by [The family and its composer are groundwork](2026-10-23-the-family-and-its-composer-are-groundwork.md)
- [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md), changed in part by [Groundwork is the default composition](2026-10-22-groundwork-is-the-default-composition.md)
- [Declared, through one door](2026-10-14-declared-through-one-door.md), changed in part by [Groundwork is the default composition](2026-10-22-groundwork-is-the-default-composition.md)
- [Everything is declared, the Clojure way](2026-10-22-everything-is-declared-the-clojure-way.md), changed in part by [A table is one map](2026-10-22-a-table-is-one-map.md)
- [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md), changed in part by [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md)
- [Groundwork is the default composition](2026-10-22-groundwork-is-the-default-composition.md)
- [Ring at the edge, steps inside](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-ring-at-the-edge-steps-inside.md): moved to mailroom with the library, with "Work is a vector of steps over one map" and "Steps are a list over one context"
- [Libraries pass values](2026-10-22-libraries-pass-values.md), replaced by [Libraries stand in layers](2026-10-22-libraries-stand-in-layers.md)
- [Libraries stand in layers](2026-10-22-libraries-stand-in-layers.md)
- [No Polylith tool](2026-10-22-no-polylith-tool.md)
- [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md), changed in part by [A library says where it runs](2026-10-22-a-library-says-where-it-runs.md), [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md), [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md), [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md) [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md) and [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md) and [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [A library says where it runs; sessions and access stay apart](2026-10-22-a-library-says-where-it-runs.md), changed in part by [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md) and [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md)
- [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md), changed in part by [One tree](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md), [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md), [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md), [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md) [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md) and [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md) and [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), changed in part by [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md), [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md), [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md), [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md), [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md) [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md) and [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md) and [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md)
- [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md)
- [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md)
- [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md), changed in part by [showroom is renamed browser-build](2026-10-23-showroom-is-renamed-browser-build.md)
- [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md), changed in part by [The browser start starts parlor; the server answers "what may I do?"](2026-10-23-the-browser-start-starts-parlor.md)
- [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md), changed in part by [The browser start starts parlor; the server answers "what may I do?"](2026-10-23-the-browser-start-starts-parlor.md)
- [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md), changed in part by mailroom's [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md), [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) and [mailroom is renamed bridge, and its server is Jetty 12](2026-10-23-mailroom-is-renamed-bridge-and-serves-on-jetty.md)
- [mailroom is renamed bridge, and its server is Jetty 12](2026-10-23-mailroom-is-renamed-bridge-and-serves-on-jetty.md)
- [showroom is renamed browser-build](2026-10-23-showroom-is-renamed-browser-build.md)
- [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md), changed in part by [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) and [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md)
- [A library holds one idea and knows nothing of who uses it](2026-10-22-a-library-holds-one-idea-and-knows-nothing-of-who-uses-it.md)
- [A library's name is its door](2026-10-22-a-librarys-name-is-its-door.md), changed in part by [A library's api is its api file](2026-10-22-a-librarys-api-is-its-api-file.md) and [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md)
- [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md), changed in part by [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md) and [A library's api is its api file](2026-10-22-a-librarys-api-is-its-api-file.md)
- [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md), changed in part by [A library's api is its api file](2026-10-22-a-librarys-api-is-its-api-file.md)
- [A library's api is its api file](2026-10-22-a-librarys-api-is-its-api-file.md), replaced by [The api sits at the library's root](2026-10-22-the-api-sits-at-the-librarys-root.md)
- [The api sits at the library's root](2026-10-22-the-api-sits-at-the-librarys-root.md)
- [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md), replaced by [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md)
- [A library is a folder with its own deps.edn](2026-10-22-a-library-is-a-folder-with-its-own-deps.md), replaced by [One tree](2026-10-22-one-tree-libraries-edn-checks-the-edges.md)
- [Every library has an api, and its parts are server-code, browser-code or shared-code](2026-10-22-server-code-browser-code-shared-code.md)
- [Each library keeps its own decisions](2026-10-22-each-library-keeps-its-own-decisions.md), changed in part by [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md)
- [The first cut is thirteen libraries](2026-10-22-the-first-cut-is-thirteen-libraries.md), changed in part by [The background work is called background jobs](2026-10-22-the-background-work-is-called-background-jobs.md) and [Three ways between browser and server](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-three-ways-between-browser-and-server.md)
- [The jobs library is named groundwork.background-jobs](2026-10-22-the-jobs-library-is-named-background-jobs.md), replaced by [The background work is called chores](2026-10-22-the-background-work-is-called-chores.md)
- [The background work is called chores](2026-10-22-the-background-work-is-called-chores.md), replaced by [The background work is called background jobs](2026-10-22-the-background-work-is-called-background-jobs.md)
- [The background work is called background jobs](2026-10-22-the-background-work-is-called-background-jobs.md), changed in part by [backroom comes from its own repository](2026-10-22-backroom-comes-from-its-own-repository.md)
- [Nothing goes in that the owner does not understand](2026-10-22-nothing-goes-in-that-the-owner-does-not-understand.md)
- [Each library brings its own settings](2026-10-22-each-library-brings-its-own-settings.md)
- [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md), changed in part by [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md), [main takes every library's new settings](2026-10-22-main-takes-every-librarys-new-settings.md) and [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md)
- [main takes every library's new settings: the pool's, a migration's limits, the workers', the listener's, the watcher's](2026-10-22-main-takes-every-librarys-new-settings.md), changed in part by [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md)
- [main makes one wire and passes it to every library](2026-10-22-main-makes-one-wire-and-passes-it-to-every-library.md), changed in part by [main makes one logbook and passes its log to every library](2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md)
- [main makes one logbook and passes its log to every library](2026-10-22-main-makes-one-logbook-and-passes-its-log-to-every-library.md)
- [main makes one meter, hands it to every library, and serves its text](2026-10-23-main-makes-one-meter-and-serves-its-text.md)
- [main makes one attic and one postbox, and serves the upload route and the provider's webhooks](2026-10-23-main-makes-one-attic-and-one-postbox.md), changed in part by [postbox's webhook functions run as doorman's system actor :postbox](2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md)
- [An app's table that writes its own :gates or :on-change is refused](2026-10-23-an-apps-table-that-writes-what-house-puts-is-refused.md)
- [The end-to-end test drives a sample app in a headless browser, over the DevTools protocol](2026-10-23-the-end-to-end-test-drives-a-headless-browser-over-devtools.md)
- [/mcp stays the app's own code, not a groundwork library](2026-10-23-mcp-stays-the-apps-own-code.md)
- [Groundwork ships these parts](2026-10-18-groundwork-ships-these-parts.md)
- [Groundwork ships no team endpoints and no eval bridge](2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md)
- [Groundwork is no longer frozen](2026-10-17-groundwork-is-no-longer-frozen.md)
- [Names are made consistent once](2026-10-18-names-are-made-consistent-once.md)
- [A name is a sentence that starts with a verb](2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md); the words: [GLOSSARY.md](../GLOSSARY.md)
- [Functions are small, one job each, in plain Clojure](2026-10-22-functions-are-small.md)
- [Java 25 or newer](2026-10-12-java-25-or-newer.md)
- [Renovate keeps the libraries current](2026-10-20-renovate-keeps-the-libraries-current.md)

### Settings and wiring

- [Settings are required data](2026-10-12-settings-are-required-data.md), changed in part by [Each library brings its own settings](2026-10-22-each-library-brings-its-own-settings.md) and [Secrets are safe's wrapped secrets](2026-10-23-secrets-are-safes-wrapped-secrets.md)
- [Integrant wires the parts](2026-10-12-integrant-wires-the-parts.md), changed in part by [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md)
- [The library does not need sops](2026-10-12-the-library-does-not-need-sops.md)
- [Secrets are safe's wrapped secrets; an app loads its settings with safe](2026-10-23-secrets-are-safes-wrapped-secrets.md)

### Database and SQL

- [Postgres through next.jdbc and PgJDBC](2026-10-12-postgres-through-next-jdbc-and-pgjdbc.md)
- [The database connection is encrypted and verified](2026-10-18-the-database-connection-is-encrypted-and-verified.md)
- [Connections come from a HikariCP pool](2026-10-12-connections-come-from-a-hikaricp-pool.md), changed in part by [Settings are required data](2026-10-12-settings-are-required-data.md)
- [The time zone is a setting](2026-10-12-the-time-zone-is-a-setting.md)
- [Queries have a time limit](2026-10-12-queries-have-a-time-limit.md)
- [Slow work gets time through its transaction](2026-10-12-slow-work-gets-time-through-its-transaction.md)
- [The transaction hook runs only in transactions](2026-10-12-the-transaction-hook-runs-only-in-transactions.md)
- [SQL lives only in files](2026-10-12-sql-lives-only-in-files.md), changed in part by [A table is one map](2026-10-22-a-table-is-one-map.md) and [A query is made by a function from its .sql file](server/sql/2026-10-22-a-query-is-made-by-a-function-from-its-sql-file.md)
- [SQL files are plain SQL](2026-10-12-sql-files-are-plain-sql.md)
- [A param is named by what it is](2026-10-12-a-param-is-named-by-what-it-is.md)
- [A list param takes the type Postgres expects](2026-10-12-a-list-param-takes-the-type-postgres-expects.md)
- [Columns are named entity__field](2026-10-12-columns-are-named-entity__field.md)
- [Every column has a name](2026-10-12-every-column-has-a-name.md)
- [JSON keys convert only when shaped entity__field](2026-10-12-json-keys-convert-only-when-entity__field.md)
- [Keys are made once per query, not per row](2026-10-12-keys-are-made-once-per-query.md)
- [Values come back as real types](2026-10-12-values-come-back-as-real-types.md)
- [row throws on more than one row](2026-10-12-row-throws-on-more-than-one-row.md)
- [We try queries in the REPL](2026-10-12-we-try-queries-in-the-repl.md)

### Migrations

- [Migrations are numbered SQL files](2026-10-12-migrations-are-numbered-sql-files.md), changed in part by [The schema snapshot is written when the start is done](2026-10-13-the-schema-snapshot-is-written-when-the-start-is-done.md) , [Production migrates before it starts](2026-10-18-production-migrates-before-it-starts.md) and [A table is one map](2026-10-22-a-table-is-one-map.md)
- [The schema snapshot is written when the start is done](2026-10-13-the-schema-snapshot-is-written-when-the-start-is-done.md)
- [Production migrates before it starts](2026-10-18-production-migrates-before-it-starts.md)
- [Migrate changes only the objects that differ](2026-10-22-migrate-changes-only-the-objects-that-differ.md)
- [A table is one map](2026-10-22-a-table-is-one-map.md)
- [A table map says its column types in wire's words](2026-10-22-a-table-map-says-column-types-in-wires-words.md)
- [A migration runs in two parts: the instant part before the start, the slow part after](2026-10-22-a-migration-runs-in-two-parts.md)
- [A fresh database takes the maps as they are](2026-10-22-a-fresh-database-takes-the-maps-as-they-are.md)
- [Groundwork's own tables are table maps, in their own folder](2026-10-22-groundworks-tables-are-maps-in-their-own-folder.md), changed in part by [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md) and [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)

### Sessions and logins

- [Groundwork ships no logins](2026-10-12-groundwork-ships-no-logins.md), changed in part by doorman's [doorman ships no logins](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-doorman-ships-no-logins.md)
- [Sessions live in Postgres](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-sessions-live-in-postgres.md): moved to doorman with the library, with "The session is the only truth"
- [Pages for other sites, visitors, and servers](2026-10-12-pages-for-other-sites-visitors-and-servers.md), changed in part by doorman's [Sessions for visitors, and none for servers](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-sessions-for-visitors-and-none-for-servers.md) and [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [A visitor's session starts on a POST of house's, never on a page load](2026-10-23-a-visitors-session-starts-on-a-post-of-houses.md), changed in part by [The visitor route is rate-limited by the asker's address, with mailroom's buckets](2026-10-23-the-visitor-route-is-rate-limited-by-address-with-mailrooms-buckets.md)
- [The visitor route is rate-limited by the asker's address, with mailroom's buckets](2026-10-23-the-visitor-route-is-rate-limited-by-address-with-mailrooms-buckets.md)

### Permissions and the access language

- [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md), changed in part by doorman's [doorman ships permissions](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-doorman-ships-permissions.md)
- [Who may see what is the app's; doorman enforces it](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-15-who-may-see-what-is-the-apps.md): moved to doorman, with "A record of two scopes may have a parent and children" and "A record between fixed sides carries them"
- [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md), changed in part by [A table is one map](2026-10-22-a-table-is-one-map.md) and doorman's [Access rules are sentences in doorman's language](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Every scoped table names its scope](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-every-scoped-table-names-its-scope.md): moved to doorman with the library, with "A record may wait for its scope", "A record can belong to a visitor", "Shared rows may belong to nobody" and "One request may name several scopes"
- [Money and personal data live in their own tables](2026-10-17-money-and-personal-data-live-in-their-own-tables.md)
- [postbox's webhook functions run as doorman's system actor :postbox](2026-10-23-postboxs-webhook-functions-run-as-doormans-system-actor-postbox.md)

### Endpoints, parquery and live updates

- [What every endpoint declares](2026-10-12-what-every-endpoint-declares.md), its run-once carried over by [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md)
- [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md)
- [Every endpoint declares its answer](2026-10-12-every-endpoint-declares-its-answer.md)
- [Reads and writes share one path](2026-10-12-reads-and-writes-share-one-path.md)
- [parquery is the transport](2026-10-12-parquery-is-the-transport.md), changed in part by [Groundwork ships these parts](2026-10-18-groundwork-ships-these-parts.md)
- [parquery is one address](2026-10-12-parquery-is-one-address.md)
- [Asks travel together and run apart](2026-10-12-asks-travel-together-and-run-apart.md)
- [Each ask fails alone](2026-10-12-each-ask-fails-alone.md)
- [Three ways between browser and server, each with one job](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-three-ways-between-browser-and-server.md): moved to mailroom with the library, with "The three ways are named http, sse and ws", "web becomes transport and routes" and "Named messages over three addresses"
- [sync keeps screens in step with the database; rate limits are transport's](2026-10-22-sync-keeps-screens-in-step.md), changed in part by [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md), [sync tells by table and trusts the gates](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) and [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [Postgres rings the bell](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-12-postgres-rings-the-bell.md): moved to doorbell with the library, with "Asking again, and bells that miss nothing", "sync tells by table and trusts the gates" and "The browser keeps answers by question"
- [Who is rung comes from who may see](2026-10-13-who-is-rung-comes-from-who-may-see.md), changed in part by [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md) and [sync tells by table and trusts the gates](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-sync-tells-by-table-and-trusts-the-gates.md)

### Server, browser, routing and build

- [http-kit serves, reitit routes](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-12-http-kit-and-reitit.md): moved to mailroom with the library
- [Groundwork ships the page shell](2026-10-12-groundwork-ships-the-page-shell.md)
- [re-frame and Reagent in the browser](2026-10-12-re-frame-and-reagent-in-the-browser.md), changed in part by [A namespace names its stylesheets](https://github.com/p4ulcristian/browser-build/blob/main/decisions/2026-10-17-a-namespace-names-its-stylesheets.md)
- [Groundwork starts the browser and owns the build](2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md), changed in part by [The browser build is Groundwork's](https://github.com/p4ulcristian/browser-build/blob/main/decisions/2026-10-22-the-browser-build-is-groundworks.md) and [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md)
- [A build can have several modules](2026-10-12-a-build-can-have-several-modules.md), carried over by [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md)
- [Nothing asks before the start](2026-10-13-nothing-asks-before-the-start.md), changed in part by [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [Routing is Groundwork's, and a route is a record](2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md), its extras carried over by [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md)
- [The browser start starts parlor; the server answers "what may I do?"](2026-10-23-the-browser-start-starts-parlor.md), changed in part by [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md), [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md) and [The browser build's npm packages are real dependencies; doorman owns the shape](2026-10-23-the-builds-npm-packages-are-real-dependencies-and-doorman-owns-the-my-actions-shape.md)
- [The routing extras, each where it belongs: queries, scrolling, the unsaved guard, modules on demand, languages](2026-10-23-routing-extras-each-where-it-belongs.md), added to by [house reads the catalogs at the start](2026-10-23-house-reads-the-catalogs-at-the-start-and-serves-them.md)
- [house reads the catalogs at the start, serves them to the browser, and holds the server's texts](2026-10-23-house-reads-the-catalogs-at-the-start-and-serves-them.md)
- [The browser build's npm packages are real dependencies; doorman owns the shape of "what may I do?"](2026-10-23-the-builds-npm-packages-are-real-dependencies-and-doorman-owns-the-my-actions-shape.md)
- [No pre-rendering of pages for search engines, for now](2026-10-23-no-pre-rendering-of-pages-for-search-engines-for-now.md)

### Styles

- [A namespace names its stylesheets](https://github.com/p4ulcristian/browser-build/blob/main/decisions/2026-10-17-a-namespace-names-its-stylesheets.md): moved to showroom with the library, with the styles decisions before it

### Jobs and the REPL

- [A job can give up for good](https://github.com/p4ulcristian/backroom/blob/main/decisions/2026-10-12-a-job-can-give-up-for-good.md): moved to backroom with the library
- [REPLs by environment](2026-10-12-repls-by-environment.md), changed in part by [Groundwork ships no team endpoints and no eval bridge](2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md) and [The REPL starts last and stops first; house brings its tools](2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md)
- [The REPL starts last and stops first; house brings its tools](2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md)
- [Every request is handled with its language bound, for dictionary's tr](2026-10-23-every-request-is-handled-in-its-language.md)
- [An app's own server routes are records with a kind; the composer does their checks](2026-10-23-an-apps-own-server-routes-are-records-with-a-kind.md)
- [A token call is answered as its person, from the app's own route](2026-10-23-a-token-call-is-answered-as-its-person-from-the-apps-route.md)
- [A page says its own head and body through the app's render-page; the composer keeps the shell and makes the nonce](2026-10-23-a-page-says-its-own-head-and-body-through-the-apps-render-page.md)
