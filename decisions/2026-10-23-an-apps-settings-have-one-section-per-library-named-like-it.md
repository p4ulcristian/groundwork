# An app's settings have one section per library, named like it

> Follows [Each library's plain name](2026-10-23-each-librarys-plain-name.md); made with [the one pass](2026-10-23-the-plain-names-ship-in-one-pass.md).

**Decision:** each library's settings sit in one top section of the app's settings, named by the library's plain name:

| today | becomes |
|---|---|
| `:wire` | `:shaped-json` |
| `:logbook` | `:logs` |
| `:meter` and `:metrics` | `:metrics {:jvm-metrics … :most-series-per-metric … :route {:path … :token …}}` |
| `:study` | `:live-repl` |
| `:database` | `:postgres` |
| `:sessions` | `:access` |
| `:server` | `:bridge` |
| `:attic` | `:file-storage` |
| `:postbox` | `:email` |
| `:doorbell` | `:live-updates` |
| `:languages`, `:background-jobs`, `:browser-build` | kept |

groundwork's own sections (`:errors`, `:visitors`, `:page`, `:health`, `:run-once`, `:migration`, `:my-actions`, `:browser-wire`, …) are not libraries' and keep their names, `:browser-wire` aside: it becomes `:browser-shaped-json` with the library it configures.

**Why:** a reader finds a library's settings under its name, the same word as its repository and namespace. The metrics library had two sections, the meter's and its route's; `:meter` renamed would collide with `:metrics`, so the route moves inside as `:route`.

**The price:** every app's settings file changes its section names once, with the pass; the metrics route's two keys move one level down.

**Instead of:** `:metrics` beside `:metrics-route`: flatter, but one library with two sections; keeping the sections' current words (`:database`, `:sessions`, `:server`), which are plain but differ from the libraries' names.
