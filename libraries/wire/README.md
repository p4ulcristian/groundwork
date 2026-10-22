# groundwork.wire

How a value travels as text and comes back as what it was, the same rules
on the JVM and in the browser. JSON knows only text, numbers, true/false,
lists and maps; a value's shape (a malli schema) turns UUIDs, exact
decimals, dates and moments into text on the way out, and back into what
they are on the way in.

Needs only malli and jsonista. Door: `groundwork.wire.clojure-common.door`
(`schema`, `decode`, `encode`, `fits?`, `explain`, `write-json`,
`read-json`). What differs per language lives beside it:
`groundwork.wire.clojure.*` on the JVM, `groundwork.wire.clojurescript.*`
in the browser (load `groundwork.wire.clojurescript.big` for exact
decimals with big.js). Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.wire.clojure-common.door :as wire])

(def order [:map [:order/id :uuid] [:order/total :decimal] [:order/day :time/local-date]])

(wire/encode order {:order/id #uuid "1d000000-0000-4000-8000-000000000001"
                    :order/total 120.50M
                    :order/day (java.time.LocalDate/parse "2026-11-15")})
;=> #:order{:id "1d000000-0000-4000-8000-000000000001" :total "120.50" :day "2026-11-15"}

(wire/decode order (wire/read-json json))
;=> {:value #:order{:id #uuid "1d000000-…" :total 120.50M :day #object[java.time.LocalDate "2026-11-15"]}}

(wire/decode order {:order/id "nope" …})
;=> {:errors #:order{:id ["should be a uuid"]}}
```

## Tests

```sh
clojure -X:test     # JVM: the round trip and the door
```
