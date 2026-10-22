# A table map says its column types in wire's words

> Adds to [A table is one map](2026-10-22-a-table-is-one-map.md).

**Decision:** a table map lists its columns as a vector of `[key type options]`, and the type is in wire's words (malli): `:uuid`, `:int`, `:decimal`, `:string`, `:boolean`, `[:enum …]`, `:time/instant`, `:time/local-date`. groundwork.schema turns each into its Postgres type (`uuid`, `bigint`, `numeric`, `text`, `boolean`, a text with a check, `timestamptz`, `date`). A column is NOT NULL unless its options say `{:optional? true}`.

```clojure
(def orders
  {:table   :orders
   :columns [[:order/id         :uuid         {:primary-key? true :default :random-uuid}]
             [:order/shop-id    :uuid         {:references :shop/id}]
             [:order/total      :decimal]
             [:order/note       :string       {:optional? true}]
             [:order/created-at :time/instant {:default :now}]]})
```

**Why:** the same map gives the Postgres table and the shape of its rows, which wire checks on the way to the browser: a column's type is said once. A vector keeps the columns in the order the table has them.

**The price:** a Postgres type with no wire word (a `tsvector`, a `point`) needs its own way in later.

**Instead of:**
- Postgres's words (`numeric`, `timestamptz`): exact for the database, but the row shape would be said again in malli
- columns as a map: shorter to look up, but loses the order
