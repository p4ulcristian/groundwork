# groundwork.sessions

Who is asking: a session is a row in Postgres, the browser's cookie holds
only random bytes, and the table only their fingerprint. The app's own login
proves who someone is and calls `sign-in!`; signing out deletes rows, so
access ends at once on every server.

Needs groundwork.sql and groundwork.steps. API: `groundwork.sessions.api`
(`sessions-table`, `sign-in!`, `sign-out!`, `sign-out-everywhere!`,
`delete-ended-sessions!`, `make-find-session-step`). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(def settings {:session-days 30 :allowed-origin "https://ironrainbow.com" :secure-cookie? true})

(schema/migrate! pool (conj app-tables sessions/sessions-table) changes)

;; at the end of the app's login
(let [{:keys [set-cookie]} (sessions/sign-in! pool person-id settings)]
  {:status 302 :headers {"location" "/" "set-cookie" set-cookie}})

;; first in every handler
(def find-session (sessions/make-find-session-step pool settings))
{:orders/of-shop {:steps [find-session … ]}}   ; context gets :groundwork.sessions/person-id
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
