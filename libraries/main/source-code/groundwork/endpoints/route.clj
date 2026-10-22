(ns groundwork.endpoints.route
  "The endpoints' one address, /parquery: a POST goes to the HTTP part
   (asks and answers), a websocket to the websocket part (the bells). Both
   carry the same endpoints' keywords, under the same session and
   permissions. This is also the transport part itself
   (:groundwork/endpoints-transport): what both halves share on the server."
  (:require
   [groundwork.endpoints.http :as http]
   [groundwork.endpoints.websocket :as websocket])
  (:import
   (java.util.concurrent ExecutorService Executors Semaphore)))

(def path "/parquery")

(defn routes
  "The address's route, for the router."
  [part]
  [[path {:access :public
          ;; each endpoint is limited on its own (groundwork.limits); the
          ;; address all asks share doesn't get a public route's default
          :groundwork.web/limited-inside? true
          :post   (fn [request] (http/handle part request))
          :get    (fn [request] (websocket/handle part request))}]])

(defn start
  "The transport part: the endpoints it carries, the workers asks run on and
   how many may run at once, and the bells."
  [{:keys [endpoints db app-settings sessions environment]}]
  (let [settings (:endpoints app-settings)
        part     {:settings  settings
                  :endpoints endpoints
                  :sessions  sessions
                  :workers   (Executors/newVirtualThreadPerTaskExecutor)
                  :running   (Semaphore. (int (:max-running settings)) true)}
        part     (assoc part :bells (websocket/start (assoc part :db db :db-settings (:db app-settings) :environment environment)))]
    (assoc part :routes (routes part))))

(defn stop
  "Stop the bells and the workers."
  [{:keys [^ExecutorService workers bells]}]
  (websocket/stop bells)
  (.shutdown workers))
