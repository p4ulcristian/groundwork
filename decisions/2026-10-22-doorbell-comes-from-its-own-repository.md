# doorbell comes from its own repository

> **Changed in part** by [The browser start starts parlor; the server answers "what may I do?"](2026-10-23-the-browser-start-starts-parlor.md) (2026-10-23): doorman's access tables tell; the sessions and backroom's stay silent.

> Changes in part [One tree; libraries.edn says, and a test checks, who uses whom](2026-10-22-one-tree-libraries-edn-checks-the-edges.md), [Every namespace says its side](2026-10-22-every-namespace-says-its-side.md), [Nine libraries, by the questions an app asks](2026-10-22-nine-libraries-by-the-questions-an-app-asks.md), [sync keeps screens in step with the database](2026-10-22-sync-keeps-screens-in-step.md), [Nothing asks before the start](2026-10-13-nothing-asks-before-the-start.md), [Groundwork's own tables are table maps, in their own folder](2026-10-22-groundworks-tables-are-maps-in-their-own-folder.md) and [Every value that sets behaviour is a setting, in main too](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): sync is no longer a folder on each side of the tree; it is one library of its own, with its own names.

**Decision:** sync, the server's (`groundwork.server.sync`) and the browser's (`groundwork.browser.sync`), is one library of its own named doorbell: the public repository github.com/p4ulcristian/doorbell, MIT. Groundwork takes it from git by tag and sha in `deps.edn` (`io.github.p4ulcristian/doorbell`, v0.1.0) and no longer names jsonista, which only sync used. Its one public namespace is `doorbell.api`, a `.clj` for the server and a `.cljs` for the browser; its tests and its own decisions (sync v1, the question store v1, and "sync tells by table and trusts the gates", "Postgres rings the bell", "Asking again, and bells that miss nothing", "The browser keeps answers by question") live in its repository. It needs only cellar and mailroom (and wire, which comes with mailroom): since [sync tells by table and trusts the gates](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-sync-tells-by-table-and-trusts-the-gates.md) it uses no access library.

- **It knows nothing of Groundwork:** a table map says `:doorbell :tell` or `[:silent "why"]` (`:sync` before); the trigger calls `doorbell.tell()` in the folder `doorbell` (`groundwork.tell_sync()`), on the channel `doorbell` (`groundwork_sync`); the messages are `{:doorbell/changed {:table "orders"}}` and `{:doorbell/missed {}}` (`:sync/…`); `put-table-notes-into-handlers`, `install-tell-function!`, `put-doorbell-on-tables`, `start-listening!` and `stop-listening!` replace sync's names (doorbell's [doorbell is its own library](https://github.com/p4ulcristian/doorbell/blob/main/decisions/2026-10-22-doorbell-is-its-own-library.md) lists them). main makes doorman's and backroom's tables `[:silent …]` under `:doorbell`; the running app holds the listener at `:doorbell`.
- **No defaults.** The question store's 50 ms gather and 5-minute keep are doorbell's required settings. Groundwork's settings gain the part `:doorbell`, doorbell's `store-settings-shape`, every key required, and the page carries it to the browser, whose start makes the store with it:

  ```clojure
  :doorbell {:gather-changes-ms 50 :keep-unwatched-answers-ms 300000}
  ```

  The part is named for the library, as the table map's key is: what it holds is the store's, and the store is doorbell's. The browser's start adds doorbell's `message-shapes-by-name` to the app's shapes.
- **The app's own role needs no grant for it:** the trigger runs `doorbell.tell()` as whoever writes, and anyone may notify and listen; doorbell's test proves it with a role that owns nothing. `migrate-app!` grants nothing for doorbell.
- `libraries.edn` lists only `:main` on each side; any library may use doorbell, through `doorbell.api` only, which `libraries_test.clj` checks.

**Why:** the two sides are one idea and one protocol, and once sync stopped working out who may see a row, nothing in it needed Groundwork. Its own repository, with a `deps.edn` naming only cellar, mailroom and wire, proves it, and lets an app take it alone. **The name:** a doorbell's ring says something is at the door, not what: a change tells which table, never which row, and the screen opens by asking again, through the gates. It stands beside the doorman; Groundwork's first decisions called these bells.

**The price:** a change to doorbell is a commit and a tag in another repository, then a bump here. An app writes `:doorbell` on its table maps instead of `:sync`, and two numbers in its settings. Groundwork's tests no longer run doorbell's; main's end-to-end test still follows a new order to every open line and each person's asking again through the gates.

**Instead of:**
- staying folders in Groundwork's tree: no app can take it alone, and nothing proves its deps
- two libraries, one per side: one protocol, every change two tags
- the names bellhop (one who carries things: doorbell carries nothing), echo (repeats what was said, not that something happened) and sync (what the user gets, not what the library does)
- the store's numbers as defaults: a value in effect would be invisible in the app's settings
