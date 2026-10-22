# groundwork.web

The web server, its routes, and a request's work as steps. Ring at the
edge (http-kit calls one Ring handler), steps inside (a route's work is a
vector of steps; the answer a step adds is the Ring response).

Needs http-kit, reitit-ring and groundwork.steps. API: `groundwork.web.api`
(`make-ring-handler-from-steps`, `make-ring-handler-from-routes`,
`start-server!`, `stop-server!`). Decisions: [`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.steps.api :as steps]
         '[groundwork.web.api :as web])

(def say-hello-to-shop
  {:name  :say-hello-to-shop
   :enter (fn [context]
            (let [shop-id (get-in context [:groundwork.web/request :path-params :shop-id])]
              (steps/add-answer-to-context context {:status 200 :body (str "hello shop " shop-id)})))})

(def server
  (web/start-server!
   (web/make-ring-handler-from-routes
    [["/shops/:shop-id/hello" {:get {:handler (web/make-ring-handler-from-steps [say-hello-to-shop])}}]])
   {:ip "127.0.0.1" :port 8080}))
;; GET /shops/7/hello => 200 "hello shop 7"

(web/stop-server! server)
```

## Tests

```sh
clojure -X:test     # in this folder: web and steps, nothing else
```
