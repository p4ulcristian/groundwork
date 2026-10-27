# The REPL may listen on a Unix socket

> Decided by the agent releasing the composer, 2026-10-27, from the user's brief (the switch's settled S1: make a unix-socket address possible as a setting, beside the TCP one), on groundwork-live-repl v0.1.4's [A REPL may listen on a Unix socket](https://github.com/p4ulcristian/groundwork-live-repl/blob/main/decisions/2026-10-27-a-repl-may-listen-on-a-unix-socket.md). Since v8.0.0. Changes in part [The REPL starts last and stops first; house brings its tools](2026-10-23-the-repl-starts-last-and-house-brings-its-tools.md): the `:live-repl` settings' shape.

**Decision:** groundwork pins groundwork-live-repl v0.1.4; the `:live-repl` settings are its new ones, where a REPL listens in one key, `:address`, by `:kind`:

```clojure
:live-repl {:mode :development :address {:kind :tcp :bind-address "127.0.0.1" :port 7888 :port-file ".nrepl-port"} :middleware []}
:live-repl {:mode :ssh-only :address {:kind :unix-socket :path "/run/app/repl/live-repl.sock"} :middleware []}
```

The socket's folder must be the app's user's with no permission for its group or others; groundwork-live-repl checks it when the REPL opens, replaces a socket a process that ended left, refuses one a process answers on, and deletes the socket when the REPL closes. groundwork's tools are the same on either. The composer's server test opens its REPL on a socket and talks to it there.

**Why:** Iron Rainbow's stage runs in a container, whose loopback is its own: a socket in a folder mounted from the host is reached by the host's user alone, with no port.

**The price:** every app's `:live-repl` settings move `:bind-address :port :port-file` into `:address`.

**Instead of:**
- **a socket setting of the composer's, beside groundwork-live-repl's:** where a REPL listens is the REPL library's
