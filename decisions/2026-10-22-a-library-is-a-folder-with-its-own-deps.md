# A library is a folder with its own deps.edn

> **Replaced** by [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md) (2026-10-22): one deps.edn, one tree, edges checked by a test.

> Replaces [Libraries are folders in one tree, their edges declared](2026-10-22-libraries-are-folders-in-one-tree.md). Changes in part [Every library is groundwork.<name>, and the main one is groundwork.main](2026-10-22-every-library-is-groundwork-something.md) and [Each library keeps its own decisions](2026-10-22-each-library-keeps-its-own-decisions.md).

**Decision:** Groundwork is laid out the way Polylith lays out a workspace, without the Polylith tool. Each library is a folder, `libraries/<name>/`, with its own `deps.edn` listing only what it needs, its code in `source-code/groundwork/<name>/` (the api `groundwork.<name>.api`; parts in `server_code/`, `browser_code/`, `shared_code/`), its tests in `test-code/`, its decisions in `decisions/` and a README. A library that uses another lists it in its own `deps.edn` (`groundwork.main` lists every one). Run in a library's folder, its tests see only that library and what it lists, so a require of anything else fails: that is the check. The repo's `deps.edn` depends on `groundwork.main`, and holds the workbench: `:dev` puts every library on one classpath for one REPL, `:test` runs every library's tests. There is no `projects/` folder: an app takes Groundwork from git, and one library alone is taken with `:deps/root "libraries/<name>"`; a folder for built jars comes when they are published. The folders are called libraries, not components.

**Why:** each library is real: its own list of what it needs, takeable alone, and its edges proven by its own test run, with nothing but the Clojure CLI. Polylith's layout is a known one, and nothing here needs its tool.

**The price:** a library's name appears twice in its paths (`libraries/wire/source-code/groundwork/wire/`). A library that uses another lists it by path in its `deps.edn`. Nothing checks the edges unless each library's tests run in its own folder.

**Instead of:**
- one tree with one `deps.edn` and the edges in `libraries.edn` checked by a test: the cleanest paths, but no library has its own list of what it needs, and none can be taken alone
- Polylith with its tool: components never list each other and the tool checks and builds, but its words (component, base, project, interface) and a tool to learn
- "components" as the folder's name: Polylith's word, but in Groundwork a component already means a Reagent component in the browser and a running part on the server
