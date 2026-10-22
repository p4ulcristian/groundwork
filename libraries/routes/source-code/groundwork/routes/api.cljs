(ns groundwork.routes.api
  "The browser half of groundwork.routes: the address in the bar leads to a
   page, by name. The server half is api.clj.

     (routes/start-following-address! pages (fn [{:keys [name path-params]}] (show! name path-params)))
     (routes/go-to-page! :orders/page {:order-id 42})"
  (:require
   [groundwork.routes.browser-code.routes :as routes]))

(defn start-following-address!
  "Follows the address bar: (on-page {:name … :page … :path-params … :query …})
   now and on every move, nil for an address no page has. The page's :title
   becomes the tab's title."
  [pages on-page]
  (routes/start-following-address! pages on-page))

(defn go-to-page!
  "Moves to the page with this name and path params; Back returns."
  ([page-name] (routes/go-to-page! page-name))
  ([page-name path-params] (routes/go-to-page! page-name path-params)))

(defn make-address-of-page
  "A page's address, for a link."
  ([page-name] (routes/make-address-of-page page-name))
  ([page-name path-params] (routes/make-address-of-page page-name path-params)))
