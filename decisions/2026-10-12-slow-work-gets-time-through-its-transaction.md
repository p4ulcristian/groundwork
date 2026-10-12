# Slow work gets time through its transaction

**Decision:** work that is slow on purpose asks for it as a transaction option: `(pg/with-transaction [tx db {:statement-timeout-ms 600000}] …)`, or `:none`. It runs `set_config('statement_timeout', …, true)` (what `SET LOCAL` does) right after `BEGIN`; the db's limit is back by itself when the transaction ends.

**Why:** only a person knows a query is slow on purpose rather than stuck, so someone has to say it somewhere. Postgres limits a session or a transaction, not a single query, and this is the shape it supports directly: the longer limit can't outlive the work and leak to the next user of a pooled connection. It sits next to the transaction's other options (`:isolation`, `:read-only?`) and covers work of many statements, like a backfill in batches. Forgetting it fails loudly on the first run: `query-canceled`, naming the file.

**The price:** a single slow query reads as a transaction of one statement. The caller has to know the query is slow.

**Instead of:**
- a per-call option on `rows`/`row`/`execute!`: the same knowing-it caller, but it would need a hidden transaction to be safe, and inside an outer one the longer limit would cover what follows
- a `-- timeout:` line in the `.sql` file: the allowance next to the SQL, but a comment that changes behaviour breaks plain SQL
- a second db with a long limit: nothing new in Groundwork and it keeps slow work off the web's connections, but coarse; it stays open to any app as its own choice
