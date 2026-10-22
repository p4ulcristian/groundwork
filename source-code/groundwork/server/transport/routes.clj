(ns groundwork.server.transport.routes
  "The server's routes, with reitit: transport's three addresses, and each
   page, answered by the app's page handler when it is opened fresh; files
   (the browser's code, its styles) from a folder on disk. Any other
   address is a 404.

   The app's pages are a map by name, {:orders/page {:address \"/orders/:order-id\" …}};
   the browser gets the same map from the page (groundwork.server.main puts
   it there), so the two sides can't disagree."
  (:require
   [reitit.ring :as reitit]))

(defn- check-page [[page-name {:keys [address]}]]
  (when-not (qualified-keyword? page-name)
    (throw (ex-info (str "A page's name must be namespaced: " page-name)
                    {:type :groundwork.server.transport/bad-page-name :name page-name})))
  (when-not (string? address)
    (throw (ex-info (str "The page " page-name " has no :address")
                    {:type :groundwork.server.transport/page-without-address :name page-name}))))

(defn make-server-routes-for-pages
  "A GET route for each page, answered by page-handler (the page's HTML and
   the app's JS), so a page opened fresh or reloaded works. A page without a
   namespaced name or an address throws."
  [pages page-handler]
  (run! check-page pages)
  (vec (for [[page-name page] pages]
         [(:address page) {:name page-name :get {:handler page-handler}}])))

(defn- is-name-hashed?
  "main.3F9A0C1D2E….js: a release build's file, whose name changes with its content."
  [uri]
  (boolean (re-find #"\.[0-9A-Fa-f]{8,}\.[a-z]+$" uri)))

(defn- add-cache-header
  "A hashed file never changes, so it is kept for a year; any other file
   is asked again each time (its name stays when it changes)."
  [file-handler]
  (fn [request]
    (when-let [response (file-handler request)]
      (assoc-in response [:headers "cache-control"]
                (if (is-name-hashed? (:uri request)) "public, max-age=31536000, immutable" "no-cache")))))

(defn make-ring-handler-from-routes
  "The one Ring handler for transport's server, from reitit routes. With
   :files-folder, a GET no route matches is answered from that folder on
   disk, when it has the file. Otherwise a path that matches no route
   answers 404, a method the route doesn't have 405."
  [routes {:keys [files-folder]}]
  (reitit/ring-handler (reitit/router routes)
                       (if files-folder
                         (reitit/routes (add-cache-header (reitit/create-file-handler {:path "/" :root files-folder}))
                                        (reitit/create-default-handler))
                         (reitit/create-default-handler))))
