# Every namespace says its side

> **Changed in part** by [cellar comes from its own repository](2026-10-22-cellar-comes-from-its-own-repository.md) (2026-10-22): the database library is cellar, a library of its own taken from git, one public namespace `cellar.api` with no side.

> **Changed in part** by [wire comes from its own repository](2026-10-22-wire-comes-from-its-own-repository.md) (2026-10-22): wire is a library of its own, taken from git, one namespace `wire` with no side.

> **Changed in part** by [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md) (2026-10-22): no server-code/ or browser-code/ folders and no deps.edn per library; the side is source-code/groundwork/<side>/.

> Changes in part [A library says where it runs](2026-10-22-a-library-says-where-it-runs.md) and [Every library has an api, and its parts are server-code, browser-code or shared-code](2026-10-22-server-code-browser-code-shared-code.md).

**Decision:**

- **Two root folders:** `server-code/` holds every library that runs on the server, `browser-code/` every library that runs in the browser. A library's folder is just its thing: `server-code/transport`, `browser-code/transport`.
- **Every namespace says its side right after `groundwork`:** `groundwork.server.transport.api`, `groundwork.browser.transport.api`, `groundwork.server.access.check`. The server and the browser never share a namespace.
- **No `.cljc` in Groundwork:** a server file is `.clj`, a browser file `.cljs`, and no file has reader conditionals. wire and main are a library on each side (`server-code/wire`, `browser-code/wire`; `server-code/main`, `browser-code/main`). An app may still share its handlers' shapes in a `.cljc` file of its own.
- **Inside a library, no `server_code/`, `browser_code/` or `sql_files/` folders:** a library's parts sit beside its `api.clj`, and a `.sql` file beside the code that uses it. Sub-folders only where a library has real halves (database's `queries/` and `tables/`).
- **Coordinates carry the side too:** `io.github.p4ulcristian/groundwork-server-database`.

**Why:** a namespace says where its code runs, once, so it can't be mistaken; the folder tree reads the same way (`groundwork/server/transport/`). Without `.cljc`, every file runs in exactly one place.

**The price:** each file's path has one more folder (`groundwork/server/…`). wire's glue (its api) is written once per side.

**Instead of:**
- one namespace on both sides, the file's ending picking the side: one `require` for both, but two files with the same name were confusing
- `-server`/`-browser` in every library's name (`server-code/server-wire`): the side said twice on the path
