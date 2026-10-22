# groundwork.routes

Which address goes where, with reitit. The only addresses an app writes are
its pages', by name; data travels as named messages on
[groundwork.transport](../transport/README.md)'s own addresses. On the
server a page opened fresh is answered with the page, and any other
address is a 404; in the browser the address in the bar leads to the page,
with Back, Forward and the page's title.

Needs reitit. API: `groundwork.routes.api`, a pair: `api.clj`
(`make-server-routes-for-pages`, `make-ring-handler-from-routes`) and
`api.cljs` (`start-following-address!`, `go-to-page!`,
`make-address-of-page`). Decisions: [`decisions/`](decisions/README.md).

```clojure
(def pages {:orders/page {:address "/orders/:order-id" :title "Order"}})

;; server
(transport/start-server!
 (routes/make-ring-handler-from-routes
  (into (transport/make-routes-for-messages handlers {})
        (routes/make-server-routes-for-pages pages page-handler)))
 {:ip "0.0.0.0" :port 8080})

;; browser
(routes/start-following-address! pages (fn [{:keys [name path-params]}] (show! name path-params)))
(routes/go-to-page! :orders/page {:order-id 42})
```

## Tests

```sh
clojure -X:test     # in this folder
```
