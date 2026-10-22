# groundwork.auth

Who is asking, and what may they do.

- **Sessions:** a session is a row in Postgres; the browser's cookie holds
  only random bytes, the table only their fingerprint. The app's own login
  proves who someone is and calls `sign-in!`; signing out deletes rows.
- **Access:** the app's kinds of scope (a shop) with their actions and
  roles; scopes, roles and memberships as rows; the `:access` sentences on
  table maps turned into one SQL condition per action; the check put in
  front of every handler; the gates (Postgres's row rules) from the same
  conditions; and the leak test.

Needs groundwork.database, steps, wire, and transport (for the leak test).
API: `groundwork.auth.api`. The parts: `server_code/sessions/` and
`server_code/access/`, with their queries in `sql_files/sessions/` and
`sql_files/access/`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(def scope-kinds
  (auth/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}}}))

(auth/install-access-functions! owner-pool)
(database/migrate! owner-pool (into (auth/put-gates-on-tables app-tables scope-kinds)
                                    (conj auth/access-tables auth/sessions-table)) [])

(def ready (auth/put-access-into-handlers handlers {:pool app-pool :tables app-tables :scope-kinds scope-kinds
                                                    :find-session-step (auth/make-find-session-step app-pool settings)}))
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
