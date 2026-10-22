# groundwork.transport

How the browser and the server talk: named messages over three pipes, at
three addresses Groundwork owns.

- **http** `/groundwork/http`: a POST of a message, answered at once.
- **sse** `/groundwork/sse`: the server stream, messages to the browser (bells).
- **ws** `/groundwork/ws`: a websocket, messages both ways.

A message is a map of names to data, `{:orders/of-shop {:shop/id 7}}`. The
server answers it from one handler table by name, whichever pipe it came on,
each name apart; the browser hands what the server sends to its own handler
table by name. The addresses are a setting (`default-addresses`).

Needs http-kit, groundwork.steps and groundwork.wire. API:
`groundwork.transport.api`, a pair: `api.clj` (`default-addresses`,
`make-routes-for-messages`, `start-server!`, `stop-server!`,
`send-message-on-sse-line!`, `send-message-on-ws-line!`, `is-line-open?`)
and `api.cljs` (`default-addresses`, `send-message!`, `open-sse-line!`,
`close-sse-line!`, `open-ws-line!`, `send-message-on-ws-line!`,
`close-ws-line!`). Decisions: [`decisions/`](decisions/README.md).

```clojure
;; server
(def handlers {:orders/of-shop {:steps [find-orders-of-shop add-orders-answer]}})
(transport/make-routes-for-messages handlers {:on-sse-open remember-line! :on-sse-close forget-line!})
;; => the three routes, for groundwork.routes to serve

;; browser
(-> (transport/send-message! {:orders/of-shop {:shop/id 7}})
    (.then (fn [{:orders/keys [of-shop]}] …)))
(transport/open-sse-line! {:bells/changed on-bell})
```

## Tests

```sh
clojure -X:test     # in this folder: transport, steps and wire
```
