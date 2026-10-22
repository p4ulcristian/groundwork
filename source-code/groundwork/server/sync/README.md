# groundwork.server.sync

Keeping what screens show in step with the database, the server's side.

- Every table map says `:sync`: `:by-access`, `:everyone` or `[:silent "why"]`;
  a table that says nothing stops the start. A table that tells gets three
  statement triggers calling `groundwork.tell_sync`, which sends the changed
  rows' ids, and the values of the columns its readers name, when the
  transaction commits.
- Each server listens, gathers 50 ms, and tells the open sse lines whose
  person may read one of the rows (by the `:access` sentences):
  `{:sync/changed {:table "orders" :ids ["…"]}}`; after a break,
  `{:sync/missed {}}`.
- Every answer says the tables its steps read (`:groundwork/notes`), so the
  browser asks again only what a change touches.

The browser's side is [groundwork.browser.sync](../../browser/sync/README.md).
Needs groundwork.server.database, access, transport, steps, wire. API:
`groundwork.server.sync.api` (`install-sync-function!`, `check-sync-of-tables`,
`put-sync-on-tables`, `put-sync-into-handlers`, `start-sync!`, `stop-sync!`,
`add-sse-line!`, `remove-sse-line!`, `count-open-lines`). Decisions:
[`decisions/server/sync`](../../../../decisions/server/sync/README.md).
groundwork.server.main puts it together; the end-to-end test is main's.

```clojure
(def orders {:table :orders :columns […] :access [{:allow :orders/see :to [:member :order/shop-id]}] :sync :by-access})
```
