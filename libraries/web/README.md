# groundwork.web

The web server and the three ways the browser and the server talk:

- **http**: a request and its one answer; all data travels this way. A
  route's work is a vector of steps; the answer a step adds is the response.
- **sse**: a server stream, one answer per tab that stays open, for
  "something changed" messages (bells).
- **ws**: a websocket, both sides talking fast, for features that need it.

Needs http-kit, reitit-ring, groundwork.steps and groundwork.wire. API:
`groundwork.web.api`, a pair: `api.clj` (server: `make-ring-handler-from-steps`,
`make-ring-handler-from-routes`, `start-server!`, `stop-server!`,
`send-message-on-sse-line!`, `send-message-on-ws-line!`, `is-line-open?`) and
`api.cljs` (browser: `send-http-request!`, `open-sse-line!`,
`close-sse-line!`, `open-ws-line!`, `send-message-on-ws-line!`,
`close-ws-line!`). The parts: `server_code/{server,routes,http,sse,ws}.clj`,
`browser_code/{http,sse,ws}.cljs`. Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.web.api :as web])

(def server
  (web/start-server!
   (web/make-ring-handler-from-routes
    [["/orders/:order-id" {:get {:handler (web/make-ring-handler-from-steps [find-order add-order-answer])}}]
     ["/bells"            {:sse {:on-open remember-line! :on-close forget-line!}}]])
   {:ip "127.0.0.1" :port 8080}))

;; later, on a line the bells kept:
(web/send-message-on-sse-line! line {:id 17 :data {:table "orders" :ids [42]}})
```

```clojure
;; in the browser
(web/open-sse-line! "/bells" (fn [bell] (re-ask-what-is-on-screen! bell)))
(-> (web/send-http-request! {:method :post :path "/ask" :data {:shop/id 7}})
    (.then (fn [{:keys [status data]}] …)))
```

## Tests

```sh
clojure -X:test     # in this folder: web, steps and wire
```
