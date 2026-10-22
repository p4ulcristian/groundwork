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

(defn- turn-kind-into-get [data kind make-handler]
  (-> data
      (dissoc kind)
      (assoc :get {:handler (make-handler (get data kind))})))

(defn- turn-route-data-into-reitit [data]
  (cond
    (:sse data) (turn-kind-into-get data :sse sse/make-ring-handler-for-sse)
    (:ws data)  (turn-kind-into-get data :ws ws/make-ring-handler-for-ws)
    :else       data))

(defn- turn-route-into-reitit
  "[path data? & children] with :sse and :ws turned into :get, the children too."
  [[path & more]]
  (let [[data children] (if (map? (first more)) [(first more) (rest more)] [nil more])]
    (into (cond-> [path] data (conj (turn-route-data-into-reitit data)))
          (map turn-route-into-reitit children))))

(defn make-ring-handler-from-routes
  "A Ring handler from routes. A path that matches no route answers 404, a
   method the route doesn't have 405."
  [routes]
  (reitit/ring-handler
   (reitit/router (mapv turn-route-into-reitit routes))
   (reitit/create-default-handler)))
