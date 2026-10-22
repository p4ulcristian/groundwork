# groundwork.access

Who may see and do what. Built in pieces; piece 1 is who belongs where:
the app's kinds of scope (a shop) with their actions and starting roles, in
its code; the scopes, their roles and the memberships, as rows Groundwork
keeps. A scope's Owner holds every action of its kind.

Needs groundwork.sql. API: `groundwork.access.api` (`access-tables`,
`check-scope-kinds`, `add-scope!`, `add-role!`, `add-membership!`,
`remove-membership!`, `list-actions-of-person`). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(def scope-kinds
  (access/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}}}))

(access/add-scope! transaction scope-kinds :shop shop-id founder-id)   ; the founder is Owner
(access/add-membership! transaction [:shop shop-id] anna-id "Staff")
(access/list-actions-of-person transaction scope-kinds anna-id)
;=> {[:shop shop-id] #{:orders/see :orders/edit}}
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
