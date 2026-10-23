> **Changed in part** by [The composer takes the browser builder's changes](2026-10-23-the-composer-takes-the-browser-builders-changes.md) (2026-10-23, built on tidy-up, not released): the start takes `:on-line-change`, required; `:send-write!` takes the send's options, `{:once-key :keep-alive?}`.

> **Changed in part** by [Each library's plain name](2026-10-23-each-librarys-plain-name.md) (2026-10-23): parlor is groundwork-screens now (`groundwork-screens.api`).

> **Changed in part** by [The browser build's npm packages are real dependencies; doorman owns the shape of "what may I do?"](2026-10-23-the-builds-npm-packages-are-real-dependencies-and-doorman-owns-the-my-actions-shape.md) (2026-10-23): the answer's shape is doorman's only, handed to parlor as `:actions-answer-shape` with the wire.

> **Changed in part** by [The composer takes named refusals](2026-10-23-the-composer-takes-named-refusals.md) (2026-10-23, built on tidy-up, not released): the start takes `:on-signed-out` and passes it with `:signed-out-reason :doorman/signed-out`; every name's shapes carry `:refusals`, doorman's and groundwork's reasons added.

> **Changed in part** by [The routing extras, each where it belongs](2026-10-23-routing-extras-each-where-it-belongs.md) (2026-10-23): the browser start takes `:query-shapes-of-pages` and `:placeholder`, and gives parlor the languages and showroom's `load-module!`.

> **Changed in part** by [Every write runs once, by its one-time key](2026-10-23-every-write-runs-once-by-its-one-time-key.md) (2026-10-23): parlor's start takes `:send-write!` (the key in the `idempotency-key` header) and `:retry-writes` (from the page) in place of `:send-message!`.

# The browser start starts parlor; the server answers "what may I do?"

> Changes in part [doorbell comes from its own repository](2026-10-22-doorbell-comes-from-its-own-repository.md) and [doorman comes from its own repository](2026-10-22-doorman-comes-from-its-own-repository.md): doorman's access tables tell now; the sessions and backroom's tables stay silent. Builds on [re-frame and Reagent in the browser](2026-10-12-re-frame-and-reagent-in-the-browser.md), parlor's [parlor is its own library](https://github.com/p4ulcristian/parlor/blob/main/decisions/2026-10-22-parlor-is-its-own-library.md) and doorman's ["What may I do?" is a handler for the browser](https://github.com/p4ulcristian/doorman/blob/main/decisions/2026-10-23-what-may-i-do-is-a-handler-for-the-browser.md).

**Decision:** house takes [parlor](https://github.com/p4ulcristian/parlor) v0.3.0, the browser's screens over doorbell's question store in re-frame, and puts it together with the rest.

- **The browser start** (`house.browser.main.api/start-app!`) makes the wire, the send function, the question store and the sse line as before, then `(rf/dispatch-sync [:parlor/start {…}])` with the store, the send function, the page's pages, and `[:doorman/my-actions {}]` as the `:actions-question`. parlor follows the address bar; house no longer calls mailroom's `start-following-address!`.
- **Its options**, every key required: `:shapes-by-name`, `:sse-handlers`, `:exact-decimals` as before; `:on-page` is gone, and three of parlor's come in its place: `:questions-of-pages` (every page's questions, by page name), `:on-answer-failed` (an event, or nil) and `:on-move` (an event, or nil). parlor checks them again at its start, closer.
- **The app draws its root**: `(parlor/mount-root! "app" root)`, into the shell's `#app`, after the start, and again after a save in development. house takes no `:root`.
- **The server adds one of doorman's handlers**, `:doorman/my-actions` (`make-my-actions-handler`), after doorman's check and before doorbell's notes; an app's handler of that name is refused (`:house.server.main/handler-name-taken`). Its rate limit is the new settings part `:my-actions`, `{:rate-limit {:max 60 :per-ms 60000}}` or nil, every key required. The browser's shapes for it are house's own, its answer shape parlor's `actions-answer-shape`.
- **doorman's access tables tell** (`:doorbell :tell`): the answer to `:doorman/my-actions` notes `doorman/memberships` and `doorman/role-actions`, so a membership or a role changed rings every line and the screens ask again what their person may do. The sessions and the background jobs stay silent.
- **libraries.edn**: parlor is a library of its own; any browser library may use it, through `parlor.api` only, with `re-frame.core` beside it; no server namespace requires either. react and react-dom (19.3.1) are in `package.json`.

```clojure
(main/start-app! {:shapes-by-name     shapes-by-name
                  :sse-handlers       {}
                  :questions-of-pages {:orders/page (fn [{:keys [path-params]}] [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])}
                  :on-answer-failed   nil
                  :on-move            nil
                  :exact-decimals     wire.big/big})
(parlor/mount-root! "app" root)
```

**Why the app mounts:** drawing is not part of starting (parlor's [the root is drawn by a function](https://github.com/p4ulcristian/parlor/blob/main/decisions/2026-10-22-the-root-is-drawn-by-a-function.md)); a save in development draws again without starting again, and that call is the app's either way. One call, the same at the start and after a save, is less to know than a `:root` option plus a second way to draw again. The element is house's shell's fixed `#app`.

**Why the access tables tell:** "no screen lists it yet" was the reason they were silent; `:doorman/my-actions` is that screen. Silent, a new role or membership would show only after a reload or a broken line.

**The price:** every membership or role change rings every open line, and every open screen asks `:doorman/my-actions` again, its own person's answer: one small read per screen per change. The app writes out `:questions-of-pages` for every page, `(fn [_] [])` included. The answer shape of `:doorman/my-actions` is written twice, doorman's and parlor's, the same.

**Instead of:**
- house taking `:root` and an element id: a second way to draw for the reload, and a setting for an id the shell fixes
- `:doorman/my-actions` through `put-access-into-handlers`: it wants an `:action`, and asking what one may do needs none
- the access tables silent, the answer asked again only after a break: stale until a reload
