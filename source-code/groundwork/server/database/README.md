# groundwork.server.database

Talking to Postgres, and shaping its tables.

- **Queries:** a HikariCP pool, every query a `.sql` file (a param is
  `:name`, a column `entity__field` comes back as `:entity/field`), rows with
  real types (UUID, BigDecimal, java.time), transactions.
- **Tables:** declared as Clojure maps; each start compares them with the
  database and plans what differs, in two parts (instant before the app
  starts, slow after it, in the background). A difference that could lose
  data stops with a message and runs nothing; changes (a rename, a drop, a
  backfill) are data, run once. A map's `:gates` become row-level security.

Needs next.jdbc, PgJDBC, HikariCP and jsonista. API: `groundwork.server.database.api`.
The parts: `server_code/queries/` (postgres, params, names, rows) and
`server_code/tables/` (catalog, desired, plan, gates, names, types); the
tables' own queries in `sql_files/`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.server.database.api :as database])

(def pool (database/start-pool! {:host "localhost" :port 5432 :database "app" :user "app" :password nil
                                 :tls :off :pool-size 10 :statement-timeout-ms 30000 :time-zone "UTC"}))

(def orders
  {:table   :orders
   :columns [[:order/id      :uuid    {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid    {:references :shop/id}]
             [:order/total   :decimal {:default 0}]]})

(database/migrate! pool [shops orders] [])

(def find-orders-of-shop (database/make-query-from-sql-file "app/orders/find_orders_of_shop.sql"))
(database/find-rows pool find-orders-of-shop {:order/shop-id 7})
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/gw-pg -A trust -U "$USER"
pg_ctl -D /tmp/gw-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/gw-pg.log start
GROUNDWORK_SQL_TEST_PORT=5499 GROUNDWORK_SQL_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/gw-pg stop
```
