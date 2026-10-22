(ns groundwork.browser.styles.api
  "How the page looks: a namespace names the stylesheets it needs, as a
   query names its .sql file. The api of groundwork.browser.styles.

     (ns app.browser.orders.order-card
       (:require [groundwork.browser.styles.api :as styles]))

     (def order-card-styles (styles/use-stylesheet \"app/orders/order_card.css\"))

     [:div {:class \"order-card\"} …]   ; classes are plain text, the CSS plain CSS

   use-stylesheet is a macro: it runs while compiling, so a missing file
   stops the build at that line. groundwork.server.browser-build gathers
   every named stylesheet into one bundle, in the order the namespaces are
   found, which the page links: /css/main.css while watching (saving a
   stylesheet updates the open page), a hashed name when built."
  (:require-macros [groundwork.browser.styles.api]))
