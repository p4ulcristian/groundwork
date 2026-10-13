(ns example.hello.routes
  "The example app's pages, written once: the server reads who may open
   each and its title, the browser its screen."
  #?(:cljs (:require [example.hello.views :as views])))

(def routes
  [["/" {:name :home :access :public
         #?@(:cljs [:view views/home])}]
   ["/orders" {:name :orders :access :public :title "Orders"
               #?@(:cljs [:view views/orders])}]
   ["/orders/:order-id" {:name :order :access :public
                         #?@(:cljs [:view views/order :title views/order-title])}]
   ["/form" {:name :form :access :public :title "Form"
             #?@(:cljs [:view views/form])}]
   ;; its screen is in the build's :reports module when there is one: its code
   ;; and its stylesheets load on arrival
   ["/reports" {:name :reports :access :public :title "Reports" :page {:module :reports}
                #?@(:cljs [:load views/load-reports :placeholder views/loading])}]])
