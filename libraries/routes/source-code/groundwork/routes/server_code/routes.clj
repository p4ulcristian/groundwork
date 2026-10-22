(ns groundwork.routes.server-code.routes
  "The server's routes, with reitit: an address leads to its work. A route
   says its kind: :get, :post, … with a :handler (http), :sse or :ws with
   their options; :sse and :ws become GET handlers from groundwork.transport
   before reitit sees them. A page, opened fresh, is a GET answered by the
   app's page handler.

     [[\"/ask\"   {:post {:handler …}}]
      [\"/bells\" {:sse {:on-open … :on-close …}}]
      [\"/live\"  {:ws  {:on-open … :on-message … :on-close …}}]]"
  (:require
   [groundwork.routes.shared-code.pages :as pages]
   [groundwork.transport.api :as transport]
   [reitit.ring :as reitit]))

(defn- turn-kind-into-get [data kind make-handler]
  (-> data
      (dissoc kind)
      (assoc :get {:handler (make-handler (get data kind))})))

(defn- turn-route-data-into-reitit [data]
  (cond
    (:sse data) (turn-kind-into-get data :sse transport/make-ring-handler-for-sse)
    (:ws data)  (turn-kind-into-get data :ws transport/make-ring-handler-for-ws)
    :else       data))

(defn- turn-route-into-reitit
  "[path data? & children] with :sse and :ws turned into :get, the children too."
  [[path & more]]
  (let [[data children] (if (map? (first more)) [(first more) (rest more)] [nil more])]
    (into (cond-> [path] data (conj (turn-route-data-into-reitit data)))
          (map turn-route-into-reitit children))))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler (the page's HTML and
   the app's JS), so a page opened fresh or reloaded works."
  [pages page-handler]
  (vec (for [[address data] (pages/check-pages pages)]
         [address {:name (:name data) :get {:handler page-handler}}])))

(defn make-ring-handler-from-routes
  "The one Ring handler for groundwork.transport's server. A path that
   matches no route answers 404, a method the route doesn't have 405."
  [routes]
  (reitit/ring-handler
   (reitit/router (mapv turn-route-into-reitit routes))
   (reitit/create-default-handler)))
