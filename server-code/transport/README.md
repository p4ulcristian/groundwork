# groundwork.server.transport

How the browser and the server talk: named messages over three pipes, at
three addresses Groundwork owns.

- **http** `/groundwork/http`: a POST of a message, answered at once.
- **sse** `/groundwork/sse`: the server stream, messages to the browser (bells).
- **ws** `/groundwork/ws`: a websocket, messages both ways.

Routes, with reitit: transport's three addresses, and the app's pages (a
map by name both sides read) answered when opened fresh; any other address
is a 404. In the browser, the address in the bar leads to a page by name.

A message is a map of names to data, `{:orders/of-shop {:shop/id 7}}`. The
server answers it from one handler table by name, whichever pipe it came on,
each name apart; the browser hands what the server sends to its own handler
table by name. A handler may say `:params-shape` and `:answer-shape`: its
params arrive as real types (UUIDs, decimals, dates) or are answered
`{:errors …}`, and its answer leaves as JSON by its shape; the browser reads
it back with the same shapes. The addresses are a setting (`default-addresses`).

Needs http-kit, groundwork.server.steps and groundwork.server.wire. API:
`groundwork.server.transport.api`, a pair: `api.clj` (`default-addresses`,
`make-routes-for-messages`, `start-server!`, `stop-server!`,
`send-message-on-sse-line!`, `send-message-on-ws-line!`, `is-line-open?`,
`answer-message`, `make-server-routes-for-pages`, `make-ring-handler-from-routes`)
The browser's side is [groundwork.browser.transport](../transport-browser/README.md);
what both sides agree on (the three addresses, the page list) is in
`contract.cljc`, the same in both. Decisions: [`decisions/`](decisions/README.md).

```clojure
;; server
(def handlers {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                                :steps        [find-orders-of-shop add-orders-answer]}})
(transport-server/make-routes-for-messages handlers {:on-sse-open remember-line! :on-sse-close forget-line!})
;; => the three routes, for groundwork.routes to serve

;; browser
(-> (transport-server/send-message! shapes-by-name {:orders/of-shop {:shop/id shop-id}})
    (.then (fn [{:orders/keys [of-shop]}] …)))   ; totals are decimals again
(transport-server/open-sse-line! shapes-by-name {:bells/changed on-bell})
```

## Tests

```sh
clojure -X:test     # in this folder: transport-server, steps and wire
```
