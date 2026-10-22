(ns groundwork.server.transport.routes
  "The server's routes, with reitit: transport's three addresses, and each
   page, answered by the app's page handler when it is opened fresh. Any
   other address is a 404."
  (:require
   [groundwork.server.transport.contract :as pages]
   [reitit.ring :as reitit]))

(defn- turn-page-into-server-route [page-handler [address data]]
  [address {:name (:name data) :get {:handler page-handler}}])

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler (the page's HTML and
   the app's JS), so a page opened fresh or reloaded works."
  [pages page-handler]
  (mapv #(turn-page-into-server-route page-handler %) (pages/turn-pages-into-reitit-routes pages)))

(defn make-ring-handler-from-routes
  "The one Ring handler for transport's server, from reitit routes. A path
   that matches no route answers 404, a method the route doesn't have 405."
  [routes]
  (reitit/ring-handler (reitit/router routes) (reitit/create-default-handler)))
