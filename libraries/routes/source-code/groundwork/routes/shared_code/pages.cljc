(ns groundwork.routes.shared-code.pages
  "The app's pages: a map by name, read by the server and the browser alike.
   A page has an :address, and whatever else the app needs to show it:

     {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}
      :shops/page  {:address \"/shops/:shop-id\"   :title \"Shop\"}}")

(defn- check-page [[page-name {:keys [address]}]]
  (when-not (qualified-keyword? page-name)
    (throw (ex-info (str "A page's name must be namespaced: " page-name)
                    {:type :groundwork.routes/bad-page-name :name page-name})))
  (when-not (string? address)
    (throw (ex-info (str "The page " page-name " has no :address")
                    {:type :groundwork.routes/page-without-address :name page-name}))))

(defn turn-pages-into-reitit-routes
  "The pages as reitit routes: [address {:name page-name …the page}]. A page
   without a namespaced name or an address throws."
  [pages]
  (run! check-page pages)
  (vec (for [[page-name page] pages]
         [(:address page) (assoc page :name page-name)])))
