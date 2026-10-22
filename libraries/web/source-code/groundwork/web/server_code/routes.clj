(ns groundwork.web.server-code.routes
  "Routes are reitit data. A route says its kind: :get, :post, … for http
   (with a :handler), :sse or :ws (with their options). The :sse and :ws
   ones become GET handlers here, before reitit sees them.

     [[\"/orders/:id\" {:get {:handler …}}]
      [\"/bells\"      {:sse {:on-open … :on-close …}}]
      [\"/cursors\"    {:ws  {:on-open … :on-message … :on-close …}}]]"
  (:require
   [groundwork.web.server-code.sse :as sse]
   [groundwork.web.server-code.ws :as ws]
   [reitit.ring :as reitit]))

(defn- turn-sse-and-ws-into-get [data]
  (cond
    (:sse data) (-> data (dissoc :sse) (assoc :get {:handler (sse/make-ring-handler-for-sse (:sse data))}))
    (:ws data)  (-> data (dissoc :ws) (assoc :get {:handler (ws/make-ring-handler-for-ws (:ws data))}))
    :else       data))

(defn- turn-route [[path & more]]
  (let [[data children] (if (map? (first more)) [(first more) (rest more)] [nil more])]
    (into (if data [path (turn-sse-and-ws-into-get data)] [path])
          (map turn-route children))))

(defn make-ring-handler-from-routes
  "A Ring handler from routes. A path that matches no route answers 404, a
   method the route doesn't have 405."
  [routes]
  (reitit/ring-handler
   (reitit/router (mapv turn-route routes))
   (reitit/create-default-handler)))
