# Live updates listen on a pool of their own

> The user's decision, 2026-11-17, from Iron Rainbow's production log: at every start, 60 s after it, the pool logged `Apparent connection leak detected`. The connection was live updates' LISTEN (groundwork-postgres's `start-listening!`), borrowed from the app's pool and held for as long as the app runs. v8.0.13.

**Decision:** `start-app!` starts a third pool, `:listening-pool`, from the `:postgres` settings with `:pool-size 1` and `:leak-warning-after-ms :none`, on a meter of its own, and hands it to groundwork-live-updates's `start-listening!`. The app's pool no longer lends a connection to LISTEN. `stop-app!` closes it after listening stops.

```clojure
;; start-app!'s running app
{:pool …           ; requests and jobs: a leak warning here is a real one
 :counting-pool …  ; the daily caps
 :listening-pool … ; LISTEN, one connection, held on purpose
 …}
```

- **No setting:** one listener needs one connection, and a connection held on purpose is never a leak; neither value is the place's to choose.
- **Same connection settings:** host, TLS, timeouts and keepalive are the `:postgres` settings, as for the counting pool.

**Why:** a warning that fires at every start trains everyone to read past it, and a real leak then looks the same. The LISTEN connection also took one of the pool's connections from requests.

**The price:** a third pool object at start; Postgres sees the same number of connections as before (the one LISTEN held is now outside the app's pool).

**Instead of:**
- **the listener opening a raw connection in groundwork-postgres:** a second way to connect, with TLS, timeouts, keepalive and metrics written again by hand
- **turning the leak warning off on the app's pool:** a real leak would go unseen too
