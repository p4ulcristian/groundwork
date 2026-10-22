# groundwork.server.wire

How shaped data travels as text and comes back as what it was, the same
rules on the JVM and in the browser. Shaped data is data whose every value
is what its shape (malli) names. JSON knows only text, numbers, true/false,
lists and maps; the shape turns UUIDs, exact decimals, dates and moments
into text on the way out, and back into what they are on the way in.

Needs only malli and jsonista. API: `groundwork.server.wire.api`
(`make-schema-from-shape`, `turn-json-data-into-shaped-data-or-errors`,
`turn-shaped-data-into-json-data`, `does-data-fit-shape?`,
`find-errors-in-data-for-shape`, `turn-data-into-json-text`,
`turn-json-text-into-data`). What differs per language lives beside it:
`groundwork.server.wire.*` on the JVM, `groundwork.browser.wire.*`
in the browser (load `groundwork.browser.wire.big` for exact
decimals with big.js). Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.server.wire.api :as wire])

(def order [:map [:order/id :uuid] [:order/total :decimal] [:order/day :time/local-date]])

(wire/turn-shaped-data-into-json-data order {:order/id #uuid "1d000000-0000-4000-8000-000000000001"
                                       :order/total 120.50M
                                       :order/day (java.time.LocalDate/parse "2026-11-15")})
;=> #:order{:id "1d000000-0000-4000-8000-000000000001" :total "120.50" :day "2026-11-15"}

(wire/turn-json-data-into-shaped-data-or-errors order (wire/turn-json-text-into-data json-text))
;=> {:shaped-data #:order{:id #uuid "1d000000-…" :total 120.50M :day #object[java.time.LocalDate "2026-11-15"]}}

(wire/turn-json-data-into-shaped-data-or-errors order {:order/id "nope" …})
;=> {:errors #:order{:id ["should be a uuid"]}}
```

## Tests

```sh
clojure -X:test     # in this folder: wire alone, nothing else on the classpath
```
