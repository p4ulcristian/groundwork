(ns groundwork.web.api
  "The web server, its routes, and a request's work as steps. The api of
   groundwork.web.

   Ring at the edge: the server calls one Ring handler (request map in,
   response map out), so any Ring middleware works around it. Steps inside:
   a route's work is a vector of steps over one context; the context starts
   as {:groundwork.web/request request}, and the answer a step adds is the
   Ring response."
  (:require
   [groundwork.steps.api :as steps]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as reitit])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(def ^:private no-answer
  {:status  500
   :headers {"content-type" "text/plain"}
   :body    "No step answered."})

(defn make-ring-handler-from-steps
  "A Ring handler that runs the steps over {:groundwork.web/request request}
   and responds with the answer a step added (a Ring response map), or 500
   when no step answered."
  [steps]
  (fn [request]
    (let [context (steps/run-steps-until-answered
                   steps
                   {:groundwork.web/request request})]
      (if (steps/does-context-have-answer? context)
        (:groundwork.steps/answer context)
        no-answer))))

(defn make-ring-handler-from-routes
  "A Ring handler from reitit routes:
     [[\"/shops/:shop-id/orders\" {:get {:handler a-ring-handler}}]]
   A path that matches no route answers 404, a method the route doesn't
   have 405."
  [routes]
  (reitit/ring-handler
   (reitit/router routes)
   (reitit/create-default-handler)))

(defn start-server!
  "Starts http-kit serving the Ring handler, every request on its own
   virtual thread. {:ip \"0.0.0.0\" :port 8080}; port 0 picks a free one.
   Returns the running server: {:port the-port …}."
  [ring-handler {:keys [ip port]}]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        server  (http-kit/run-server ring-handler {:ip                   ip
                                                   :port                 port
                                                   :worker-pool          workers
                                                   :legacy-return-value? false})]
    {:port                     (http-kit/server-port server)
     :groundwork.web/http-kit  server
     :groundwork.web/workers   workers}))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to 10 s), then stops."
  [server]
  (deref (http-kit/server-stop! (:groundwork.web/http-kit server) {:timeout 10000}) 11000 nil)
  (let [^ExecutorService workers (:groundwork.web/workers server)]
    (.shutdown workers)
    (.awaitTermination workers 1 TimeUnit/SECONDS)))
