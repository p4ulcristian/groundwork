(ns example.hello.views
  "The example app's screens: small, with an id on everything a check
   clicks or reads."
  (:require
   [groundwork.browser :as gb]
   [re-frame.core :as rf]))

(defn nav []
  [:nav
   [:a#nav-home {:href (gb/href :home)} "Home"] " "
   [:a#nav-orders {:href (gb/href :orders)} "Orders"] " "
   [:a#nav-form {:href (gb/href :form)} "Form"] " "
   [:a#nav-reports {:href (gb/href :reports)} "Reports"]])

(defn home []
  [:p#screen "Hello, " (or @(rf/subscribe [::gb/person]) "visitor") "."])

(defn orders []
  [:div#screen
   [:p#query "stage: " (:stage @(rf/subscribe [::gb/query]) "all")]
   [:button#filter {:on-click #(gb/push-query! {:stage "done"})} "Done"]
   [:a#to-order-5 {:href (gb/href :order {:order-id 5})} "Order 5"]])

(defn order []
  [:p#screen "Order " (get-in @(rf/subscribe [::gb/route]) [:path-params :order-id])])

(defn order-title [route]
  (str "Order " (get-in route [:path-params :order-id])))

(defn form []
  (let [held @(rf/subscribe [::gb/held-move])]
    [:div#screen
     [:button#dirty {:on-click #(gb/unsaved! true)} "Type something"]
     [:button#save {:on-click #(gb/unsaved! false)} "Save"]
     (when held
       [:div#held "Leave for " (name (:name held)) "? "
        [:button#discard {:on-click #(gb/continue-move!)} "Discard"]
        [:button#stay {:on-click #(gb/stay!)} "Stay"]])]))

(defn loading []
  [:p#screen "Loading"])

(defn reports []
  [:p#screen "Reports"])

(defn load-reports
  "Stands in for code that loads on demand: the screen, a moment later."
  []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve reports) 300))))

(defn not-found []
  [:p#screen "No such page"])
