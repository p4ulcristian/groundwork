(ns groundwork.server.transport.server
  "http-kit, serving one Ring handler, every request on its own virtual
   thread."
  (:require
   [org.httpkit.server :as http-kit])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn start-server!
  "Starts http-kit; {:ip \"0.0.0.0\" :port 8080}, port 0 picks a free one.
   Returns {:port the-port …}."
  [ring-handler {:keys [ip port]}]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        server  (http-kit/run-server ring-handler {:ip                   ip
                                                   :port                 port
                                                   :worker-pool          workers
                                                   :legacy-return-value? false})]
    {:port                    (http-kit/server-port server)
     :groundwork.server.transport/http-kit server
     :groundwork.server.transport/workers  workers}))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to 10 s), then stops."
  [server]
  (deref (http-kit/server-stop! (:groundwork.server.transport/http-kit server) {:timeout 10000}) 11000 nil)
  (let [^ExecutorService workers (:groundwork.server.transport/workers server)]
    (.shutdown workers)
    (.awaitTermination workers 1 TimeUnit/SECONDS)))
