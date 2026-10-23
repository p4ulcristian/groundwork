# A name is a sentence that starts with a verb

> **Changed in part** by [Shaped data is data that fits its shape](../libraries/wire/decisions/2026-10-22-shaped-data-is-data-that-fits-its-shape.md) (2026-10-22): wire says "shaped data" and "data", not "value" (`find-errors-in-data-for-shape`; arguments `shape`, `shaped-data`, `json-text`).

> Adds to [Names are made consistent once](2026-10-18-names-are-made-consistent-once.md).

**Decision:** a function's name is the sentence of what it does, written out with dashes, without small words (a, the). It starts with one verb from this list:

- `turn-X-into-Y`: the same thing in another form; changes nothing outside (`turn-json-text-into-data`)
- `make-X-from-Y`: builds a new thing from parts (`make-schema-from-shape`)
- `find-X`: looks something up; returns nil when there is nothing (`find-errors-in-value-for-shape`)
- `list-X`: returns several things
- `add-X-to-Y`: returns Y with X added (`add-answer-to-context`)
- `run-X`: runs what it is given (`run-steps-until-answered`)
- `is-`, `does-`, `has-` … `?`: a yes/no question (`does-value-fit-shape?`)
- `save-`, `send-`, `delete-` … `!`: changes the world: the database, an email, a file

The library's alias already says which library (`wire/turn-…`, not `wire/turn-wire-…`). A sentence that needs "and" is two functions. A word in a name means one thing everywhere; the words are in [GLOSSARY.md](../GLOSSARY.md). Arguments are named by the same words (`shape`, `value`, `json-text`).

**Why:** a stranger reads the name and knows what the function does, without its docstring or code. A short list of verbs means a verb always says the same kind of work.

**The price:** names are long at the call site, and unlike clojure.core's short names (`decode`, `fits?`).

**Instead of:**
- short names (`decode`, `encode`, `explain`): each hides part of what it does (`decode` also checks, and answers with errors)
- `input->output` arrows (`json-data->value-or-errors`): shorter, but `->` is a symbol to learn and says no verb
- full sentences with small words (`turn-the-json-data-into-a-value-or-a-list-of-errors`): longer, and the small words add no meaning
