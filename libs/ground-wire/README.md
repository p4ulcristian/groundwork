# ground-wire

What travels between browser and server, the same rules on both sides.
JSON knows only text, numbers, true/false, lists and maps; a value's shape
(a malli schema) turns UUIDs, exact decimals, dates and moments into text
on the way out, and back into what they are on the way in.

Needs only malli and jsonista. One `.cljc` file: the JVM and the browser
read the same shapes. In the browser a day is a `Day`, a moment an
`Instant`, a decimal big.js (with `ground.wire.decimal` loaded) or its
text, an id stays text.

Door: `ground.wire`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[ground.wire :as wire])

(def order [:map [:order/id :uuid] [:order/total :decimal] [:order/day :time/local-date]])

(wire/encode order {:order/id #uuid "1d000000-0000-4000-8000-000000000001"
                    :order/total 120.50M
                    :order/day (java.time.LocalDate/parse "2026-11-15")})
;=> #:order{:id "1d000000-0000-4000-8000-000000000001" :total "120.50" :day "2026-11-15"}

(wire/write-json *1)   ;=> "{\"order/id\":\"1d000000-…\",\"order/total\":\"120.50\",\"order/day\":\"2026-11-15\"}"

(wire/decode order (wire/read-json json))
;=> {:value #:order{:id #uuid "1d000000-…" :total 120.50M :day #object[java.time.LocalDate "2026-11-15"]}}

(wire/decode order {:order/id "nope" …})
;=> {:errors #:order{:id ["should be a uuid"]}}
```

## Tests

```sh
clojure -X:test     # JVM
```

The browser half's tests run with Groundwork's (`npx shadow-cljs compile test` at the repo root).
