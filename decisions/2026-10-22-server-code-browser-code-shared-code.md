# Every library has an api, and its parts are server-code, browser-code or shared-code

> Changes in part [A library is laid out by its languages](2026-10-22-a-library-is-laid-out-by-its-languages.md) and [The api sits at the library's root](2026-10-22-the-api-sits-at-the-librarys-root.md): the folders are named by where their code runs, exactly, not by its language.

**Decision:** every library has an api, `groundwork.<name>.api`, at its root; its ending says where it runs: `api.cljc` when it is the same on the server and in the browser, `api.clj` for a server-only library, `api.cljs` for a browser-only one, or `api.clj` and `api.cljs` together when a library offers different things on each side. Beside the api, a library's parts sit in folders named by where their code runs:

- `server-code/` (on disk `server_code/`): runs on the server: `groundwork.wire.server-code.json`
- `browser-code/` (on disk `browser_code/`): runs in the browser: `groundwork.wire.browser-code.time`
- `shared-code/` (on disk `shared_code/`): runs on both, for shared parts besides the api; it appears only when a library has some

**Why:** each folder says exactly what it holds, in the words of `source-code`: server code, browser code, shared code. Every library offers itself the same way, through its api, so a user requires `groundwork.<name>.api` and never looks inside.

**The price:** the namespaces carry the folder (`groundwork.wire.server-code.json`), and Clojure turns the dash into an underscore on disk. "browser-code" is exact for today's only client; a different kind of client later would need its own folder.

**Instead of:**
- the three as top folders replacing `source-code` (`server-code/groundwork/wire/json.clj` as `groundwork.wire.json`): shorter names, and each side's build sees only its own code, but one library spread across three folders
- language folders (`clojure/`, `clojurescript/`): exact about the language, not about where the code runs
- no side folders, things named once with one file per side (`json.clj` and `json.cljs` as `groundwork.wire.json`): the shortest names, but where code runs is seen only from the file ending
- place words (server / client / shared, backend / frontend), runtimes (jvm / js), made-up words (underground / aboveground / ground)
