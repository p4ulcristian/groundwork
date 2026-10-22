(ns groundwork.routes.api
  "Which address goes where, on both sides, with reitit. The api of
   groundwork.routes; its browser half is api.cljs.

   On the server an address leads to its work (http, sse, ws) or, for a
   page opened fresh, to the page. In the browser the address in the bar
   leads to a page. Pages are one list both sides read:

     (def pages [[\"/orders/:order-id\" {:name :order :title \"Order\" :screen order-screen}]])

     (transport/start-server!
      (routes/make-ring-handler-from-routes
       (into [[\"/ask\"   {:post {:handler ask-handler}}]
              [\"/bells\" {:sse {:on-open … :on-close …}}]]
             (routes/make-server-routes-for-pages pages page-handler)))
      {:ip \"0.0.0.0\" :port 8080})"
  (:require
   [groundwork.routes.server-code.routes :as routes]))

(defn make-ring-handler-from-routes
  "The one Ring handler, from routes of all kinds (:get…, :sse, :ws). No
   route: 404; a method the route doesn't have: 405."
  [routes]
  (routes/make-ring-handler-from-routes routes))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler, so a page opened
   fresh or reloaded works."
  [pages page-handler]
  (routes/make-server-routes-for-pages pages page-handler))
