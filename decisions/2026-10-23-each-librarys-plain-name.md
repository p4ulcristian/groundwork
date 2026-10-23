# Each library's plain name

> Follows [A library is named by what it does](2026-10-23-a-library-is-named-by-what-it-does.md). Agreed name by name; nothing is renamed until the rename itself is decided.

**Decision:** the libraries' new names, each with its repository `groundwork-<name>` ([repositories](2026-10-23-a-librarys-repository-is-groundwork-and-its-name.md), [namespaces](2026-10-23-a-librarys-namespaces-start-with-its-repository-name.md), [keys and metrics](2026-10-23-a-librarys-keys-and-metrics-carry-its-full-name.md)):

| today | new name | door |
|---|---|---|
| attic | file-storage | `groundwork-file-storage.api` |
| cellar | postgres | `groundwork-postgres.api` |
| backroom | background-jobs | `groundwork-background-jobs.api` |
| doorman | access | `groundwork-access.api` |
| doorbell | live-updates | `groundwork-live-updates.api` |
| parlor | screens | `groundwork-screens.api` |
| postbox | email | `groundwork-email.api` |
| safe | settings | `groundwork-settings.api` |
| study | live-repl | `groundwork-live-repl.api` |
| logbook | logs | `groundwork-logs.api` |
| meter | metrics | `groundwork-metrics.api` |
| dictionary | languages | `groundwork-languages.api` |
| wire | shaped-json | `groundwork-shaped-json.api` |
| bridge | bridge (kept) | `groundwork-bridge.api` |
| browser-build | browser-build (kept) | `groundwork-browser-build.api` |

**Why:**
- file-storage: says where an app's files go and what the library is for, files kept in a bucket; "uploads" would name only one way in, "files" the things more than the library.
- postgres: the library is Postgres-only on purpose; "database" would hide that, "sql" name only the queries half.
- background-jobs: the glossary's word, never "job" alone (Iron Rainbow's job is a coating job).
- access: who may do what, the same word as the `:access` setting on routes and endpoints; sessions are how it knows who is asking. "auth" mixes signing in (groundwork ships no logins) with permission.
- live-updates: says what a person sees, an open screen that follows the database; the glossary's "sync" names the same thing and is renamed with it.
- screens: what an app writes with it, each screen reading through the question store; "pages" is the server-made page shell's word, "ui" any app's components.
- email: the glossary's word.
- settings: it holds the whole settings map, secrets among its values; groundwork says "settings" everywhere ("config" nowhere), and "secrets" would name only some values.
- live-repl: a REPL into the running app, not a development one; "console" is vaguer to Clojure people.
- logs: the glossary's "log", plain; the function it hands every library is still the log.
- metrics: the glossary's "metric"; the object an app makes and hands every library is still a meter.
- languages: covers both halves, picking a request's language and the texts in each; "translations" names only the texts, "i18n" is a numeronym.
- shaped-json: JSON both ways, its namespaced keys kept and every value brought back as its real type by a shape; "json" would hide the shape check, "shaped-data" (the glossary's term for what comes back) that JSON is what crosses.
- bridge kept: renamed from mailroom on 2026-10-23 for being the one way across, with a half on each side; "messages" would hide that it runs the server and the routes, "web-server" that the app's part is named messages.
- browser-build kept: already says what it does.

**Instead of:** files, uploads (attic); database, sql (cellar); jobs (backroom); sessions, auth (doorman); sync, table-changes (doorbell); ui, pages (parlor); emails, mail (postbox); secrets, config (safe); repl, console (study); logging (logbook); keeping meter; translations, i18n (dictionary); messages, web-server (bridge); json, shaped-data, keeping wire (wire).
