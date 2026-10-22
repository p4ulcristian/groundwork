# groundwork.schema

Tables declared as Clojure maps, and Postgres brought to what they say:
each start compares the maps with the database and plans what differs, in
two parts (instant before the app starts, slow after it, in the
background). A difference that could lose data stops with a message and
runs nothing; a change (a rename, a drop, a backfill) is data the app
passes in, run once.

Needs groundwork.sql. API: `groundwork.schema.api` (`make-plan-from-tables`,
`run-instant-part!`, `run-slow-part!`, `migrate!`,
`does-database-match-tables?`, `turn-table-into-row-shape`). The parts:
`server-code.catalog` (what Postgres has), `server-code.desired` (what the
maps say), `server-code.plan` (the difference), `server-code.names`,
`server-code.types`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.schema.api :as schema])

(def orders
  {:table   :orders
   :columns [[:order/id      :uuid    {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid    {:references :shop/id}]
             [:order/total   :decimal {:default 0}]
             [:order/note    :string  {:optional? true}]]
   :indexes [[:order/shop-id]]})

(schema/migrate! pool [shops orders] [])
;; prints what it ran; (:slow-part result) is the background part
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```

With a throwaway Postgres, as in [groundwork.sql's README](../sql/README.md).
