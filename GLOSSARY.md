# Glossary

Each word in a Groundwork name means one thing, everywhere. See
[A name is a sentence that starts with a verb](decisions/2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md).

| Word | Means |
|---|---|
| shape | how data looks, written as malli data: `[:map [:order/id :uuid]]` |
| schema | a shape made into a malli schema object, ready to check with |
| shaped data | data whose every value is what its shape names: a UUID, a BigDecimal, a LocalDate, not their text ([why this name](libraries/wire/decisions/2026-10-22-shaped-data-is-data-that-fits-its-shape.md)) |
| json data | JSON already read into maps and lists: ids, decimals and dates are still text |
| json text | JSON as one string |
| data | maps, lists, text, numbers: no claim about types |
| errors | a map from each key that doesn't fit to readable messages: `{:order/id ["should be a uuid"]}` |
| context | the one map that steps pass along |
| step | `{:name a-keyword :enter (fn [context] context)}` |
| answer | what the work returns; once the context has one, no more steps run |
