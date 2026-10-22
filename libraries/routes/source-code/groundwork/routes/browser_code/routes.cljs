(ns groundwork.routes.browser-code.routes
  "The browser's routes, with reitit-frontend: the address in the bar leads
   to a page. Back and Forward work (HTML5 history), and the page's :title
   becomes the tab's title on every move."
  (:require
   [groundwork.routes.shared-code.pages :as pages]
   [reitit.frontend :as reitit]
   [reitit.frontend.easy :as easy]))

(defn- show-page! [on-page match]
  (when-let [title (get-in match [:data :title])]
    (set! (.-title js/document) title))
  (on-page (when match {:page        (:data match)
                        :path-params (:path-params match)
                        :query       (:query-params match)})))

(defn start-following-address!
  "Follows the address bar: (on-page {:page … :path-params … :query …}) now
   and on every move, nil for an address no page has."
  [pages on-page]
  (easy/start! (reitit/router (pages/check-pages pages))
               #(show-page! on-page %)
               {:use-fragment false}))

(defn go-to-page!
  "Moves to the page with this name and these path params; Back returns."
  ([page-name] (go-to-page! page-name {}))
  ([page-name path-params] (easy/push-state page-name path-params)))

(defn make-address-of-page
  "The address of a page, for a link: (make-address-of-page :order {:order-id 42}) -> \"/orders/42\"."
  ([page-name] (make-address-of-page page-name {}))
  ([page-name path-params] (easy/href page-name path-params)))
