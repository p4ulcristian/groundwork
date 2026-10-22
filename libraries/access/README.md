# groundwork.access

What may someone do: the app's kinds of scope (a shop) with their actions
and roles; scopes, roles and memberships as rows; the `:access` sentences on
table maps turned into one SQL condition per action; the check put in front
of every handler; the gates (Postgres's row rules) from the same
conditions; and the leak test. Who is asking comes from
[groundwork.sessions](../sessions/README.md).

Needs groundwork.database, steps, wire, and transport-server (for the leak
test). API: `groundwork.access.api` (`access-tables`, `check-scope-kinds`,
`add-scope!`, `add-role!`, `add-membership!`, `remove-membership!`,
`list-actions-of-person`, `make-sql-conditions`, `install-access-functions!`,
`put-access-into-handlers`, `put-gates-on-tables`, `find-leaks`). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(def scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}}}))

(access/install-access-functions! owner-pool)
(database/migrate! owner-pool (into (access/put-gates-on-tables app-tables scope-kinds)
                                    (conj access/access-tables sessions/sessions-table)) [])

(def ready (access/put-access-into-handlers handlers {:pool app-pool :tables app-tables :scope-kinds scope-kinds
                                                      :find-session-step (sessions/make-find-session-step app-pool settings)}))
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
