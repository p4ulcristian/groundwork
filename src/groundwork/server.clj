(ns groundwork.server
  "The web server: http-kit, with every request on its own virtual thread.

     :ip    \"0.0.0.0\"
     :port  8080        0 picks a free port (tests)"
  (:require
   [groundwork.settings :as settings]
   [org.httpkit.server :as http])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn start
  "The server part: serves the router's handler, reading no body larger than
   the largest any route accepts. Returns the running server."
  [{:keys [settings router]}]
  (settings/check-part! :server settings)
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)]
    {::server  (http/run-server (:handler router) {:ip                   (:ip settings)
                                                   :port                 (:port settings)
                                                   :max-body             (:max-body-bytes router)
                                                   :worker-pool          workers
                                                   :legacy-return-value? false})
     ::workers workers}))

(defn port
  "The port a running server listens on."
  [server]
  (http/server-port (::server server)))

(defn stop
  "Stop taking requests, let running ones finish (up to 10 s), then stop."
  [{::keys [server workers]}]
  (deref (http/server-stop! server {:timeout 10000}) 11000 nil)
  (.shutdown ^ExecutorService workers)
  (.awaitTermination ^ExecutorService workers 1 TimeUnit/SECONDS))
