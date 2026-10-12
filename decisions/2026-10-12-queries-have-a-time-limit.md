# Queries have a time limit

**Decision:** every connection gets Postgres's `statement_timeout` from `:statement-timeout-ms` when it opens. A statement past the limit fails with `:groundwork.postgres/query-canceled`. `:none` sets it to 0, so a limit on the database role can't apply behind the config's back. The README recommends 30 s.

**Why:** the pool is small. A query that never finishes holds its connection forever, and a few of them freeze the whole app with nothing pointing at the guilty query. Postgres cancels it itself: no timer on our side, the same for every query, a clear error naming the `.sql` file. 30 s is under the proxies' limits (nginx 60 s, Cloudflare 100 s), so a query never outlives the request waiting for it, and normal work almost never reaches it. Slow queries are found through `:on-query`'s `:ms`, not through this limit.

**The price:** a query that is meant to be long (a report, a backfill) needs a way to ask for more time. Time waiting on a lock counts too.

**Instead of:** no limit unless the app asks (most apps never would, and the failure is the hardest kind to trace), or a limit set only on the database role (invisible in the code, easy to forget in a new environment).
