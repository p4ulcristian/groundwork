(ns groundwork.browser.transport.api
  "How the browser and the server talk, the browser's side: named messages
   over http, sse and ws, and the pages in the address bar. The api of
   groundwork.browser.transport; the server's side is
   groundwork.server.transport.

   The browser uses the same shapes as the server's handlers, by name, from
   a .cljc file both sides read:

     (def shapes-by-name {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                           :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]}})

     (transport/send-message! shapes-by-name {:orders/of-shop {:shop/id id}})   ; a promise of the shaped answer
     (transport/open-sse-line! shapes-by-name {:bells/changed on-bell})"
  (:require
   [groundwork.browser.transport.http :as http]
   [groundwork.browser.transport.contract :as contract]
   [groundwork.browser.transport.routes :as routes]
   [groundwork.browser.transport.sse :as sse]
   [groundwork.browser.transport.ws :as ws]))

(def default-addresses
  "Where the three pipes are, the same as on the server (the contract)."
  contract/default-addresses)

(defn send-message!
  "http: POSTs a named message, its data by :params-shape. A promise of the
   answer keyed by the same names, each shaped by its :answer-shape."
  ([shapes-by-name message] (send-message! (:http default-addresses) shapes-by-name message))
  ([address shapes-by-name message] (http/send-message! address shapes-by-name message)))

(defn open-sse-line!
  "sse: opens the stream; each named message, shaped by its :answer-shape,
   goes to its handler by name. It reopens by itself after a break."
  ([shapes-by-name handlers] (open-sse-line! (:sse default-addresses) shapes-by-name handlers))
  ([address shapes-by-name handlers] (sse/open-sse-line! address shapes-by-name handlers)))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens the websocket; each named message, answers included, shaped by
   its :answer-shape, goes to its handler by name. It reopens a second after
   a break."
  ([shapes-by-name handlers] (open-ws-line! (:ws default-addresses) shapes-by-name handlers))
  ([address shapes-by-name handlers] (ws/open-ws-line! address shapes-by-name handlers)))

(defn send-message-on-ws-line!
  "ws: sends a named message on the line, its data by :params-shape.
   Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))

;; -----------------------------------------------------------------------------
;; Pages: the address in the bar leads to a page, by name

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
