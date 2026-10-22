# groundwork.server.sessions

Who is asking: a session is a row in Postgres, the browser's cookie holds
only random bytes, and the table only their fingerprint. The app's own login
proves who someone is and calls `sign-in!`; signing out deletes rows, so
access ends at once on every server.

Needs groundwork.server.database and groundwork.server.steps. API: `groundwork.server.sessions.api`
(`sessions-table`, `sign-in!`, `sign-out!`, `sign-out-everywhere!`,
`delete-ended-sessions!`, `make-find-session-step`). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(def settings {:session-days 30 :allowed-origin "https://ironrainbow.com" :secure-cookie? true})

;; at the end of the app's login
(let [{:keys [set-cookie]} (sessions/sign-in! pool person-id settings)]
  {:status 302 :headers {"location" "/" "set-cookie" set-cookie}})

;; first in every handler (groundwork.server.access puts it there)
(sessions/make-find-session-step pool settings)   ; context gets :groundwork.server.sessions/person-id
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
