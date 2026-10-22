# The first cut is thirteen libraries

> **Changed in part** by [The background work is called chores](2026-10-22-the-background-work-is-called-chores.md) (2026-10-22): `ground-jobs` is `groundwork.background-chores`.

> **Changed in part** by [The jobs library is named groundwork.background-jobs](2026-10-22-the-jobs-library-is-named-background-jobs.md) (2026-10-22): `ground-jobs` is `groundwork.background-jobs`.

> Answers which parts become which library, left open by [The ground-* libraries live in one repo](2026-10-22-the-ground-libraries-live-in-one-repo.md). A first version: it will change as the libraries are used.

**Decision:** Groundwork is cut into these libraries, one idea each:

Groundwork's own ideas:

- `ground-schema`: table maps brought to the database, change files, the shape of a table's rows
- `ground-access`: scopes, roles, the access sentences, the wall, the check, "can I?", the leak test
- `ground-bells`: the triggers, who hears a change, the websocket line
- `ground-parquery`: asks over HTTP that run apart, and the browser store that keeps answers by question
- `ground-styles`: a namespace names its stylesheets; bundles per module; a module's stylesheets load before its code

The plumbing:

- `ground-sql`: the pool, `.sql` files, names, real types, TLS
- `ground-wire`: typed JSON on both sides
- `ground-sessions`: sessions, visitors, sign-in
- `ground-jobs`: the queue, workers, schedules
- `ground-limits`: rate limits
- `ground-web`: the server, headers and policy, files, the page shell, languages
- `ground-routing`: route records and navigation in the browser
- `ground-build`: the browser build from the settings

`groundwork` keeps the settings, the endpoints, the wiring of every library, the browser's one start, the REPL and the development start.

**Why:** one library per idea is small, tested alone, and can be taken alone by a project that isn't a Groundwork app. The permissions library is named `ground-access` after the access language and the `:access` key.

**The price:** thirteen folders, each with its own `deps.edn`, tests and decisions.

**Instead of:** about nine bigger libraries (sessions, limits and the server inside `ground-web`, wire inside `ground-sql`): fewer pieces, but a project wanting only sessions takes the whole web layer.
