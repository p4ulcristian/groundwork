# groundwork.server.sync

Keeping what screens show in step with the database, the server's side.

- Every table map says `:sync`: `:tell` or `[:silent "why"]`; a table that
  says nothing stops the start. A table that tells gets three statement
  triggers calling `groundwork.tell_sync`, which sends only the changed
  table (no ids, no values) when the transaction commits.
- Each server listens, gathers 50 ms, and tells every open sse line
  `{:sync/changed {:table "orders"}}`; after a break, `{:sync/missed {}}`.
- Every answer says the tables its steps read (`:mailroom/notes`), so the
  browser asks again only what a change touches. The gates decide what each
  asker gets: sync decides no one's access.

The browser's side is [groundwork.browser.sync](../../browser/sync/README.md).
Needs cellar and mailroom; it reads
Postgres's change messages itself, with jsonista. API:
`groundwork.server.sync.api` (`install-sync-function!`, `check-sync-of-tables`,
`put-sync-on-tables`, `put-sync-into-handlers`, `start-sync!`, `stop-sync!`,
`add-sse-line!`, `remove-sse-line!`, `count-open-lines`). Decisions:
[`decisions/server/sync`](../../../../decisions/server/sync/README.md).
groundwork.server.main puts it together; the end-to-end test is main's.

```clojure
(def orders {:table :orders :columns […] :access [{:allow :orders/see :to [:member :order/shop-id]}] :sync :tell})
(def logs   {:table :logs :columns […] :sync [:silent "no screen shows logs"]})
```
