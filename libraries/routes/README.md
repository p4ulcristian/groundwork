# groundwork.routes

Which address goes where, on both sides, with reitit. On the server an
address leads to its work (http, sse, ws) or, for a page opened fresh, to
the page; in the browser the address in the bar leads to a page, with Back,
Forward and the page's title. The app's pages are one list both sides read.

Needs reitit and groundwork.transport. API: `groundwork.routes.api`, a pair:
`api.clj` (`make-ring-handler-from-routes`, `make-server-routes-for-pages`)
and `api.cljs` (`start-following-address!`, `go-to-page!`,
`make-address-of-page`). Decisions: [`decisions/`](decisions/README.md).

```clojure
(def pages [["/orders/:order-id" {:name :order :title "Order" :screen order-screen}]])

;; server
(transport/start-server!
 (routes/make-ring-handler-from-routes
  (into [["/ask"   {:post {:handler (transport/make-ring-handler-from-steps [find-orders add-answer])}}]
         ["/bells" {:sse {:on-open remember-line! :on-close forget-line!}}]]
        (routes/make-server-routes-for-pages pages page-handler)))
 {:ip "0.0.0.0" :port 8080})

;; browser
(routes/start-following-address! pages (fn [{:keys [page path-params]}] (show! page path-params)))
(routes/go-to-page! :order {:order-id 42})
```

## Tests

```sh
clojure -X:test     # in this folder: routes and what it uses
```
