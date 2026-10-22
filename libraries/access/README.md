# groundwork.access

Who may see and do what. Built in pieces. Piece 1, who belongs where: the
app's kinds of scope (a shop) with their actions and starting roles, in its
code; the scopes, their roles and the memberships, as rows Groundwork keeps.
A scope's Owner holds every action of its kind. Piece 2, the `:access`
sentences on table maps turned into one SQL condition per action and per
operation, for the check and the gates. Piece 3, the check: every handler
declares its action and shapes, and Groundwork puts the check in front of its
steps, inside one transaction that tells Postgres who is asking. Piece 4,
the gates: Postgres's row rules made from the same conditions, so a query
with no WHERE still returns only the asker's rows.

Needs groundwork.sql and groundwork.schema. API: `groundwork.access.api`
(`access-tables`, `check-scope-kinds`, `add-scope!`, `add-role!`,
`add-membership!`, `remove-membership!`, `list-actions-of-person`,
`make-sql-conditions`, `install-access-functions!`, `put-access-into-handlers`,
`put-gates-on-tables`). Decisions:
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

```clojure
(def orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references :shop/id}] …]
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]})

(access/make-sql-conditions [shops orders] scope-kinds)
;=> {:orders {:actions    {:orders/see "(order__shop_id = ANY ((SELECT groundwork.find_scopes_holding('shop', 'orders/see'))))" …}
;             :operations {:read "…" :insert "…" :update "…" :delete "false"}}}
```

## Tests

```sh
clojure -X:test     # in this folder; the Postgres tests are skipped without a database
```
