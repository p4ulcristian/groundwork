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
- [showroom](https://github.com/p4ulcristian/showroom): what the browser downloads and how the page looks, the build and the stylesheets it bundles, a library of its own since [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md); its decisions are there
- [Groundwork's libraries share the GitHub topic groundwork](2026-10-22-groundworks-libraries-share-the-topic-groundwork.md)
- [mailroom](https://github.com/p4ulcristian/mailroom): how the browser and the server talk (both sides): named messages over http, sse and ws, a handler's steps, the routes; a library of its own since [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md); its decisions are there
- [doorman](https://github.com/p4ulcristian/doorman): who is asking and what they may do, sessions and access, a library of its own since [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md); its decisions are there
- [doorbell](https://github.com/p4ulcristian/doorbell): how screens stay current (both sides): a change to a table rings every open screen, which asks again through the gates; a library of its own since [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md); its decisions are there
- [server/main](server/main/README.md): the one start, the page shell, and the health checks

### What Groundwork is

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
- [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md)
- [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md)
- [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [mailroom comes from its own repository](2026-10-22-mailroom-comes-from-its-own-repository.md), changed in part by mailroom's [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md) and [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md), changed in part by [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
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
- [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md), changed in part by [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md) and [main takes every library's new settings](2026-10-22-main-takes-every-librarys-new-settings.md)
- [main takes every library's new settings: the pool's, a migration's limits, the workers', the listener's, the watcher's](2026-10-22-main-takes-every-librarys-new-settings.md)
- [Groundwork ships these parts](2026-10-18-groundwork-ships-these-parts.md)
- [Groundwork ships no team endpoints and no eval bridge](2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md)
- [Groundwork is no longer frozen](2026-10-17-groundwork-is-no-longer-frozen.md)
- [Names are made consistent once](2026-10-18-names-are-made-consistent-once.md)
- [A name is a sentence that starts with a verb](2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md); the words: [GLOSSARY.md](../GLOSSARY.md)
- [Functions are small, one job each, in plain Clojure](2026-10-22-functions-are-small.md)
- [Java 25 or newer](2026-10-12-java-25-or-newer.md)
- [Renovate keeps the libraries current](2026-10-20-renovate-keeps-the-libraries-current.md)

### Settings and wiring

- [Settings are required data](2026-10-12-settings-are-required-data.md), changed in part by [Each library brings its own settings](2026-10-22-each-library-brings-its-own-settings.md)
- [Integrant wires the parts](2026-10-12-integrant-wires-the-parts.md), changed in part by [Groundwork is an opinionated framework](2026-10-12-groundwork-is-an-opinionated-framework.md)
- [The library does not need sops](2026-10-12-the-library-does-not-need-sops.md)

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

### Permissions and the access language

- [Groundwork ships permissions](2026-10-12-groundwork-ships-permissions.md), changed in part by doorman's [doorman ships permissions](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-doorman-ships-permissions.md)
- [Who may see what is the app's; doorman enforces it](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-15-who-may-see-what-is-the-apps.md): moved to doorman, with "A record of two scopes may have a parent and children" and "A record between fixed sides carries them"
- [Access rules are sentences in Groundwork's language](2026-10-17-access-rules-are-sentences-in-groundworks-language.md), changed in part by [A table is one map](2026-10-22-a-table-is-one-map.md) and doorman's [Access rules are sentences in doorman's language](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Every scoped table names its scope](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-12-every-scoped-table-names-its-scope.md): moved to doorman with the library, with "A record may wait for its scope", "A record can belong to a visitor", "Shared rows may belong to nobody" and "One request may name several scopes"
- [Money and personal data live in their own tables](2026-10-17-money-and-personal-data-live-in-their-own-tables.md)

### Endpoints, parquery and live updates

- [What every endpoint declares](2026-10-12-what-every-endpoint-declares.md)
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
- [re-frame and Reagent in the browser](2026-10-12-re-frame-and-reagent-in-the-browser.md), changed in part by [A namespace names its stylesheets](https://github.com/p4ulcristian/showroom/blob/main/decisions/2026-10-17-a-namespace-names-its-stylesheets.md)
- [Groundwork starts the browser and owns the build](2026-10-12-groundwork-starts-the-browser-and-owns-the-build.md), changed in part by [The browser build is Groundwork's](https://github.com/p4ulcristian/showroom/blob/main/decisions/2026-10-22-the-browser-build-is-groundworks.md) and [showroom comes from its own repository](2026-10-22-showroom-comes-from-its-own-repository.md)
- [A build can have several modules](2026-10-12-a-build-can-have-several-modules.md)
- [Nothing asks before the start](2026-10-13-nothing-asks-before-the-start.md), changed in part by [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md)
- [Routing is Groundwork's, and a route is a record](2026-10-14-routing-is-groundworks-and-a-route-is-a-record.md)

### Styles

- [A namespace names its stylesheets](https://github.com/p4ulcristian/showroom/blob/main/decisions/2026-10-17-a-namespace-names-its-stylesheets.md): moved to showroom with the library, with the styles decisions before it

### Jobs and the REPL

- [A job can give up for good](https://github.com/p4ulcristian/backroom/blob/main/decisions/2026-10-12-a-job-can-give-up-for-good.md): moved to backroom with the library
- [REPLs by environment](2026-10-12-repls-by-environment.md), changed in part by [Groundwork ships no team endpoints and no eval bridge](2026-10-18-groundwork-ships-no-team-endpoints-and-no-eval-bridge.md)
