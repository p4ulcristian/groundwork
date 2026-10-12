(ns groundwork.server
  "The web server: http-kit, with every request on its own virtual thread.

     :ip    \"0.0.0.0\"
     :port  8080        0 picks a free port (tests)"
  (:require
   [clojure.string :as str]
   [org.httpkit.server :as http])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn- check-settings [{:keys [ip port] :as opts}]
  (when-not (map? opts)
    (throw (ex-info ":server must be a map" {:type ::bad-setting :setting :server :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) [:ip :port]))]
    (throw (ex-info (str ":server needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (when-not (and (string? ip) (not (str/blank? ip)))
    (throw (ex-info ":server :ip must be an address like \"0.0.0.0\""
                    {:type ::bad-setting :setting :ip :value ip})))
  (when-not (and (int? port) (<= 0 port 65535))
    (throw (ex-info ":server :port must be a port number"
                    {:type ::bad-setting :setting :port :value port}))))

(defn start
  "The server part: serves the router's handler. Returns the running server."
  [{:keys [settings router]}]
  (check-settings settings)
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)]
    {::server  (http/run-server router {:ip                   (:ip settings)
                                        :port                 (:port settings)
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
