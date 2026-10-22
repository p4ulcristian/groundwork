# groundwork.transport

How the browser and the server talk: the server (http-kit, one Ring
handler, every request on a virtual thread) and three ways over it:

- **http**: a request and its one answer; all data travels this way. The
  work is a vector of steps; the answer a step adds is the response.
- **sse**: a server stream, one answer per tab that stays open, for
  "something changed" messages (bells).
- **ws**: a websocket, both sides talking fast, for features that need it.

transport knows no addresses: [groundwork.routes](../routes/README.md)
makes the one handler from the routes.

Needs http-kit, groundwork.steps and groundwork.wire. API:
`groundwork.transport.api`, a pair: `api.clj` (`start-server!`,
`stop-server!`, `make-ring-handler-from-steps`, `make-ring-handler-for-sse`,
`make-ring-handler-for-ws`, `send-message-on-sse-line!`,
`send-message-on-ws-line!`, `is-line-open?`) and `api.cljs`
(`send-http-request!`, `open-sse-line!`, `close-sse-line!`, `open-ws-line!`,
`send-message-on-ws-line!`, `close-ws-line!`). Decisions:
[`decisions/`](decisions/README.md).

## Tests

```sh
clojure -X:test     # in this folder: transport, steps and wire
```
