# We try queries in the REPL

**Decision:** a query is tried from the REPL, the same way the app calls it:

```clojure
(pg/rows db list-recent {:limit 20})
(pg/statement list-recent {:limit 20})   ;=> ["SELECT … LIMIT $1" [20]]
```

**Why:** this is the Clojure way: a running system you talk to, with real data and the real code path. Named params need the parser anyway, so the REPL is where a query is whole.

**Instead of:** pasting `.sql` files into `psql`. `psql` doesn't know `:post/title`.
