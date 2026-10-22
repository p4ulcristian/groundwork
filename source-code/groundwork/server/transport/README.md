# groundwork.server.transport

How the browser and the server talk: named messages over three pipes, at
three addresses Groundwork owns.

- **http** `/groundwork/http`: a POST of a message, answered at once.
- **sse** `/groundwork/sse`: the server stream, messages to the browser (sync, the version).
- **ws** `/groundwork/ws`: a websocket, messages both ways.

Routes, with reitit: transport's three addresses, the app's pages (a map
by name) answered when opened fresh, and files (the built JS and styles)
from a classpath folder; any other address is a 404. The browser gets the
pages and the addresses from the page groundwork.server.main sends.

A message is a map of names to data, `{:orders/of-shop {:shop/id 7}}`. The
server answers it from one handler table by name, whichever pipe it came on,
each name apart; the browser hands what the server sends to its own handler
table by name. A handler may say `:params-shape` and `:answer-shape`: its
params arrive as real types (UUIDs, decimals, dates) or are answered
`{:errors …}`, and its answer leaves as JSON by its shape; the browser reads
it back with the same shapes. A handler's notes (the tables its answer was
read from, put there by groundwork.server.sync) go beside the answers, under
`:groundwork/notes`.

Needs http-kit, groundwork.server.steps and groundwork.server.wire. API:
`groundwork.server.transport.api` (`default-addresses`, `settings-shape`,
`make-routes-for-messages`, `start-server!`, `stop-server!`,
`send-message-on-sse-line!`, `send-message-on-ws-line!`, `is-line-open?`,
`answer-message`, `make-server-routes-for-pages`, `make-ring-handler-from-routes`).
The browser's side is [groundwork.browser.transport](../../browser/transport/README.md).
Decisions: [`decisions/server/transport`](../../../../decisions/server/transport/README.md).

```clojure
;; server
(def handlers {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                                :steps        [find-orders-of-shop add-orders-answer]}})
(transport/make-routes-for-messages handlers {:on-sse-open remember-line! :on-sse-close forget-line!})
;; => the three routes; with the pages' routes, for make-ring-handler-from-routes
```

## Tests

```sh
clojure -X:test :dirs '["test-code/groundwork/server/transport"]'     # from the repo's root
```
