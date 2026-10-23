# Glossary

Each word in a manor name means one thing, everywhere. See
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
| log | the one function `(log level event data)` a library is handed to write a line; [logbook](https://github.com/p4ulcristian/logbook) makes it, `with-context` makes one that carries a request's id or a person; a line's context is that, not the steps' context |
| step | `{:name a-keyword :enter (fn [context] context)}`; a handler's steps are its work, run by [mailroom](https://github.com/p4ulcristian/mailroom) |
| background job | one piece of work saved in Postgres to run outside a request, by a worker (never "job" alone: Iron Rainbow's job is a coating job); [backroom](https://github.com/p4ulcristian/backroom) queues and runs them |
| session | who is asking: a row in Postgres, the browser's cookie only random bytes; [doorman](https://github.com/p4ulcristian/doorman) signs in and out and finds it first in every handler |
| scope | a place data belongs to (a shop): the app's own row, known to [doorman](https://github.com/p4ulcristian/doorman) by its kind and id; a person is in it with one role, a bundle of actions |
| gates | Postgres's row rules made from the app's `:access` sentences, by [doorman](https://github.com/p4ulcristian/doorman): a query with no WHERE still gets only the asker's rows |
| answer | what the work returns; once the context has one, no more steps run |
| http | a request from the browser and the server's one answer: all data travels this way; [mailroom](https://github.com/p4ulcristian/mailroom) carries http, sse and ws |
| sse | the server stream (Server-Sent Events): one answer per tab that stays open; carries doorbell's messages, never data |
| ws | a websocket: both sides talk fast, for features that need it |
| sync | keeping what screens show in step with the database: every open screen is told which table changed (never which row), and those showing something read from it ask again, through the gates; [doorbell](https://github.com/p4ulcristian/doorbell) does it (a table map's `:doorbell`, its messages `:doorbell/changed` and `:doorbell/missed`) |
| table map | a table declared as a Clojure map (`{:table :orders :columns [...]}`); [cellar](https://github.com/p4ulcristian/cellar) brings Postgres to what the maps say |
| browser build | the app's ClojureScript compiled for the browser: watched in development, built once by the deploy, with the stylesheet bundle; [showroom](https://github.com/p4ulcristian/showroom) makes it, never in production |
| stylesheet | a plain `.css` file next to the namespace that names it with `(def order-card-styles (styles/use-stylesheet "app/orders/order_card.css"))`, as a query names its `.sql` file; [showroom](https://github.com/p4ulcristian/showroom) bundles them |
| meter | the one object the app makes ([meter](https://github.com/p4ulcristian/meter)) and hands to every library, which declares its metrics on it and counts into them; the app serves its text at `/metrics` |
| metric | one thing counted, declared as data: a counter, a gauge or a histogram, its labels each with a closed set of values (`mailroom_messages_total{name,pipe,outcome}`) |
| visitor | someone not signed in for whom the app keeps something (a cart): a session of their own, doorman's, started by house's visitor route; their rows become the person's at sign-in |
| REPL | a Clojure prompt into the running app, by [study](https://github.com/p4ulcristian/study): open in development, over SSH on stage, off in production but for the emergency switch |
| tool | a function of house's in `study.tools` while a REPL is open: `ask-as-person`, `why-refused`, `explain-setting` |
| asker | who a message is asked as: `{:person-id :visitor-id :session-flags}`, the person or the visitor and the session's flags |
