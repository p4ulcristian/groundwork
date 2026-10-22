# groundwork.sql

Postgres through a HikariCP pool, with every query in a `.sql` file.
A param is `:name` in the file; a column named `entity__field` comes back
as `:entity/field`; values come back as real types (UUID, BigDecimal,
java.time).

Needs next.jdbc, PgJDBC, HikariCP and jsonista. API: `groundwork.sql.api`
(`start-pool!`, `stop-pool!`, `make-query-from-sql-file`, `find-rows`,
`find-one-row`, `run-statement!`, `run-in-transaction!`). The parts:
`server-code.params` (`:name` → `?`), `server-code.names` (column → key),
`server-code.rows` (values in, rows out). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.sql.api :as sql])

;; app/orders/find_orders_of_shop.sql:
;;   SELECT order__id, order__total FROM orders WHERE order__shop_id = :order/shop-id
(def find-orders-of-shop (sql/make-query-from-sql-file "app/orders/find_orders_of_shop.sql"))

(def pool (sql/start-pool! {:host "localhost" :port 5432 :database "app" :user "app" :password nil
                            :tls :off :pool-size 10 :statement-timeout-ms 30000 :time-zone "UTC"}))

(sql/find-rows pool find-orders-of-shop {:order/shop-id 7})
;=> [{:order/id 42 :order/total 120.50M}]
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
