# Glossary

Each word in a groundwork name means one thing, everywhere. See
[A name is a sentence that starts with a verb](decisions/2026-10-22-a-name-is-a-sentence-that-starts-with-a-verb.md).

| Word | Means |
|---|---|
| shape | how data looks, written as malli data: `[:map [:order/id :uuid]]` |
| schema | a shape made into a malli schema object, ready to check with |
| shaped data | data whose every value is what its shape names: a UUID, a BigDecimal, a LocalDate, not their text ([why this name](https://github.com/p4ulcristian/groundwork-shaped-json/blob/main/decisions/2026-10-22-shaped-data-is-data-that-fits-its-shape.md)) |
| json data | JSON already read into maps and lists: ids, decimals and dates are still text |
| json text | JSON as one string |
| data | maps, lists, text, numbers: no claim about types |
| errors | a map from each key that doesn't fit to readable messages: `{:order/id ["should be a uuid"]}` |
| context | the one map that steps pass along |
| log | the one function `(log level event data)` a library is handed to write a line; [groundwork-logs](https://github.com/p4ulcristian/groundwork-logs) makes it, `with-context` makes one that carries a request's id or a person; a line's context is that, not the steps' context |
| step | `{:name a-keyword :enter (fn [context] context)}`, [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps)'s shape; a handler's steps are its work, run by [groundwork-bridge](https://github.com/p4ulcristian/groundwork-bridge) |
| background job | one piece of work saved in Postgres to run outside a request, by a worker (never "job" alone: Iron Rainbow's job is a coating job); [groundwork-background-jobs](https://github.com/p4ulcristian/groundwork-background-jobs) queues and runs them |
| session | who is asking: a row in Postgres, the browser's cookie only random bytes; [groundwork-access](https://github.com/p4ulcristian/groundwork-access) signs in and out and finds it first in every handler |
| scope | a place data belongs to (a shop): the app's own row, known to [groundwork-access](https://github.com/p4ulcristian/groundwork-access) by its kind and id; a person is in it with one role, a bundle of actions |
| gates | Postgres's row rules made from the app's `:access` sentences, by [groundwork-access](https://github.com/p4ulcristian/groundwork-access): a query with no WHERE still gets only the asker's rows |
| answer | what the work returns; once the context has one, no more steps run |
| http | a request from the browser and the server's one answer: all data travels this way; [groundwork-bridge](https://github.com/p4ulcristian/groundwork-bridge) carries http, sse and ws |
| sse | the server stream (Server-Sent Events): one answer per tab that stays open; carries groundwork-live-updates's messages, never data |
| ws | a websocket: both sides talk fast, for features that need it |
| live updates | keeping what screens show in step with the database: every open screen is told which table changed (never which row), and those showing something read from it ask again, through the gates; [groundwork-live-updates](https://github.com/p4ulcristian/groundwork-live-updates) does it (a table map's `:live-updates`, its messages `:groundwork-live-updates/changed` and `:groundwork-live-updates/missed`) |
| table map | a table declared as a Clojure map (`{:table :orders :columns [...]}`); [groundwork-postgres](https://github.com/p4ulcristian/groundwork-postgres) brings Postgres to what the maps say |
| browser build | the app's ClojureScript compiled for the browser: watched in development, built once by the deploy, with the stylesheet bundle; [groundwork-browser-build](https://github.com/p4ulcristian/groundwork-browser-build) makes it, never in production |
| stylesheet | a plain `.css` file next to the namespace that names it with `(def order-card-styles (styles/use-stylesheet "app/orders/order_card.css"))`, as a query names its `.sql` file; [groundwork-browser-build](https://github.com/p4ulcristian/groundwork-browser-build) bundles them |
| meter | the one object the app makes ([groundwork-metrics](https://github.com/p4ulcristian/groundwork-metrics)) and hands to every library, which declares its metrics on it and counts into them; the app serves its text at `/metrics` |
| metric | one thing counted, declared as data: a counter, a gauge or a histogram, its labels each with a closed set of values (`groundwork_bridge_messages_total{name,pipe,outcome}`) |
| visitor | someone not signed in for whom the app keeps something (a cart): a session of their own, groundwork-access's, started by groundwork's visitor route; their rows become the person's at sign-in |
| REPL | a Clojure prompt into the running app, by [groundwork-live-repl](https://github.com/p4ulcristian/groundwork-live-repl): open in development, over SSH on stage, off in production but for the emergency switch |
| tool | a function of groundwork's in `groundwork-live-repl.tools` while a REPL is open: `ask-as-person`, `why-refused`, `explain-setting` |
| given | the map the app map's `:make-given` makes once at the start from the running app's parts: every app handler's steps find it at `:groundwork/given`, every background job run at `:given` |
| asker | who a message is asked as: `{:person-id :visitor-id :session-flags}`, the person or the visitor and the session's flags, found by groundwork-access from the request's cookie or token; only the REPL's tools name one |
| token | a person's own proof for a script or an AI, made by groundwork-access for a signed-in person: a call with `Authorization: Bearer <token>` is that person's, with no session flags |
| file | bytes the app keeps in its bucket, by [groundwork-file-storage](https://github.com/p4ulcristian/groundwork-file-storage): named by its content (`orders/photos/<sha256 as a uuid>.png`), never written over, a row of its file storage's files table (`groundwork_file_storage.order_photos`); the app's rows hold its key |
| file storage | one of the app's named places for files (`:order-photos`, `:invoices`), by [groundwork-file-storage](https://github.com/p4ulcristian/groundwork-file-storage): its bucket and a files table of its own, and an upload route of its own or none (the `:file-storage :storages` settings); handed to the app's code by name, `:file-storages` |
| signed link | an address that reads one file of a private bucket until it expires (S3's presigned GET), made by groundwork-file-storage for whom the app lets see the file |
| upload | files sent by the browser to the server, a multipart POST to one file storage's upload route, each checked by its bytes before groundwork-file-storage stores it there |
| email | a written email the app queues in a write's transaction, sent once by [groundwork-email](https://github.com/p4ulcristian/groundwork-email) as a background job; its key is queued once, ever; its tag is one of the app's closed set |
| webhook | a route a provider posts to from outside (Postmark's bounces and inbound email), checked by a token in its address, always answered 200 once the token passes |
