(ns groundwork.routes.api
  "The browser half of groundwork.routes: the address in the bar leads to a
   page. The server half is api.clj."
  (:require
   [groundwork.routes.browser-code.routes :as routes]))

(defn start-following-address!
  "Follows the address bar: (on-page {:page … :path-params … :query …}) now
   and on every move, nil for an address no page has. The page's :title
   becomes the tab's title."
  [pages on-page]
  (routes/start-following-address! pages on-page))

(defn go-to-page!
  "Moves to the page with this name and path params; Back returns."
  ([page-name] (routes/go-to-page! page-name))
  ([page-name path-params] (routes/go-to-page! page-name path-params)))

(defn make-address-of-page
  "The address of a page, for a link."
  ([page-name] (routes/make-address-of-page page-name))
  ([page-name path-params] (routes/make-address-of-page page-name path-params)))
