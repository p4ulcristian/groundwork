(ns groundwork.routes.api
  "Which address goes where, with reitit. The api of groundwork.routes; its
   browser half is api.cljs.

   The only addresses an app writes are its pages', by name; data travels
   as named messages on transport's own three addresses. On the server, a
   page opened fresh is answered with the page; in the browser, the address
   in the bar leads to the page.

     (def pages {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}})

     (transport/start-server!
      (routes/make-ring-handler-from-routes
       (into (transport/make-routes-for-messages handlers {})
             (routes/make-server-routes-for-pages pages page-handler)))
      {:ip \"0.0.0.0\" :port 8080})"
  (:require
   [groundwork.routes.server-code.routes :as routes]))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler, so a page opened
   fresh or reloaded works."
  [pages page-handler]
  (routes/make-server-routes-for-pages pages page-handler))

(defn make-ring-handler-from-routes
  "The one Ring handler, from reitit routes. No route: 404; a method the
   route doesn't have: 405."
  [routes]
  (routes/make-ring-handler-from-routes routes))
