# groundwork.browser.transport

How the browser talks to the server: named messages over http, sse and ws,
read back by the same shapes as the server's handlers; and the pages in the
address bar (reitit-frontend), by name. The server's side is
[groundwork.server.transport](../transport-server/README.md); what both sides
agree on (the three addresses, the page list) is in `contract.cljc`, the same
in both.

ClojureScript only: built by shadow-cljs with the app. Needs reitit-frontend
and groundwork.browser.wire. API: `groundwork.browser.transport.api`
(`default-addresses`, `send-message!`, `open-sse-line!`, `close-sse-line!`,
`open-ws-line!`, `send-message-on-ws-line!`, `close-ws-line!`,
`start-following-address!`, `go-to-page!`, `make-address-of-page`).
Decisions: [`decisions/`](decisions/README.md).

```clojure
(-> (transport-browser/send-message! shapes-by-name {:orders/of-shop {:shop/id shop-id}})
    (.then (fn [{:orders/keys [of-shop]}] …)))
(transport-browser/start-following-address! pages (fn [{:keys [name path-params]}] (show! name path-params)))
```

Not tested yet: no browser builds while the libraries are cut.
