# A library says where it runs; sessions and access stay apart

> **Changed in part** by [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md) (2026-10-22): the side is a root folder and a namespace segment (`groundwork.server.transport`), not a `-server` suffix; wire and main are split too.

> Changes in part [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md): transport is two libraries, one per side, and auth is sessions and access again.

**Decision:**

- **A library with a server side and a browser side is two libraries**, named by where they run: `transport-server` and `transport-browser` (and later `sync-server` and `sync-browser`). A library that runs in one place keeps its plain name (`database`, `sessions`, `access`, `background-chores`); `wire` is read by both sides.
- **What a pair's two halves must agree on is a contract file**, the same in both halves but for its namespace (transport's: the three addresses and the page list). A test in groundwork.main fails if the two copies ever differ.
- **sessions and access are two libraries again**: sessions is who is asking (authentication), access is what they may do (authorization). Each name says exactly one thing.

The libraries:

| where it runs | libraries |
|---|---|
| server | steps, database, transport-server, sessions, access, background-chores, main |
| browser | transport-browser (later browser-build, sync-browser) |
| both | wire |

**Why:** every folder says where its code runs, and each side pulls only its own dependencies (the server never sees reitit-frontend, the browser never sees http-kit). It's how two-language stacks are laid out (a backend, a frontend, a contract between), with Clojure's advantage that the contract is code. "auth" read as signing in to most people; sessions and access say each half exactly.

**The price:** more folders (eight now); a change to what a pair agrees on is made in two contract files at once, which the test enforces.

**Instead of:**
- one library per pair with `server_code/` and `browser_code/` inside: fewer folders, but a library pulls both sides' dependencies
- `identity-and-access` as one library: exact, but two ideas in one
