# groundwork.browser.transport

How the browser talks to the server: named messages over http, sse and ws,
read back by the same shapes as the server's handlers; and the pages in the
address bar (reitit-frontend), by name. The server's side is
[groundwork.server.transport](../../server/transport/README.md). The three
addresses and the pages come from the page the server sent
(groundwork.browser.main reads them), so the two sides can't disagree.

ClojureScript only: built by shadow-cljs with the app. Needs reitit-frontend
and wire. API: `groundwork.browser.transport.api`
(`send-message!`, `open-sse-line!`, `close-sse-line!`,
`open-ws-line!`, `send-message-on-ws-line!`, `close-ws-line!`,
`start-following-address!`, `go-to-page!`, `make-address-of-page`).
Decisions: [`decisions/`](decisions/README.md).

```clojure
(-> (transport/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id shop-id}})
    (.then (fn [{:orders/keys [of-shop]}] …)))
(transport/start-following-address! pages (fn [{:keys [name path-params]}] (show! name path-params)))
```

Not tested yet: no browser builds while the libraries are cut.
