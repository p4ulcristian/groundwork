# Glossary

Each word in a Groundwork name means one thing, everywhere. See
[A name is a sentence that starts with a verb](decisions/2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md).

| Word | Means |
|---|---|
| shape | how data looks, written as malli data: `[:map [:order/id :uuid]]` |
| schema | a shape made into a malli schema object, ready to check with |
| shaped data | data whose every value is what its shape names: a UUID, a BigDecimal, a LocalDate, not their text ([why this name](https://github.com/p4ulcristian/wire/blob/main/decisions/2026-10-22-shaped-data-is-data-that-fits-its-shape.md)) |
| json data | JSON already read into maps and lists: ids, decimals and dates are still text |
| json text | JSON as one string |
| data | maps, lists, text, numbers: no claim about types |
| errors | a map from each key that doesn't fit to readable messages: `{:order/id ["should be a uuid"]}` |
| context | the one map that steps pass along |
| step | `{:name a-keyword :enter (fn [context] context)}` |
| background job | one piece of work saved in Postgres to run outside a request, by a worker (never "job" alone: Iron Rainbow's job is a coating job) |
| answer | what the work returns; once the context has one, no more steps run |
| http | a request from the browser and the server's one answer: all data travels this way |
| sse | the server stream (Server-Sent Events): one answer per tab that stays open; carries bells, never data |
| ws | a websocket: both sides talk fast, for features that need it |
| sync | keeping what screens show in step with the database: a changed row is told to the screens that may show it, and they ask again (not "bells") |
| table map | a table declared as a Clojure map (`{:table :orders :columns [...]}`); [cellar](https://github.com/p4ulcristian/cellar) brings Postgres to what the maps say |
