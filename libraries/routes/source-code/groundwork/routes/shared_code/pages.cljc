(ns groundwork.routes.shared-code.pages
  "The app's pages: one list, read by the server and the browser alike. A
   page is a reitit route whose data has a :name, and whatever else the app
   needs to show it (:screen, :title, :access):

     [[\"/orders/:order-id\" {:name :order :title \"Order\" :screen order-screen}]
      [\"/shops/:shop-id\"   {:name :shop  :title \"Shop\"  :screen shop-screen}]]")

(defn- page-name [[address data]]
  (or (:name data)
      (throw (ex-info (str "The page " address " has no :name") {:type :groundwork.routes/page-without-name :address address}))))

(defn check-pages
  "Throws when a page has no :name, or two pages share one; returns the pages."
  [pages]
  (let [names (map page-name pages)]
    (when (not= (count names) (count (set names)))
      (throw (ex-info "Two pages share a :name" {:type :groundwork.routes/page-name-twice :names names}))))
  pages)
