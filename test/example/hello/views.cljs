(ns example.hello.views
  "The example app's screens: small, with an id on everything a check
   clicks or reads."
  {:css ["views.css"]}
  (:require
   [groundwork.browser :as gb]
   [re-frame.core :as rf]
   [shadow.lazy :as lazy]))

(defn nav []
  [:nav.nav-bar
   [:a#nav-home {:href (gb/href :home)} "Home"] " "
   [:a#nav-orders {:href (gb/href :orders)} "Orders"] " "
   [:a#nav-form {:href (gb/href :form)} "Form"] " "
   [:a#nav-reports {:href (gb/href :reports)} "Reports"]])

(defn home []
  [:p#screen.panel "Hello, " (or @(rf/subscribe [::gb/person]) "visitor") "."])

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

(def ^:private reports-screen
  "The reports screen, in whatever module the build puts example.hello.reports."
  (lazy/loadable example.hello.reports/screen))

(defn load-reports
  "The reports screen's code, loaded on demand, a moment later so the
   placeholder is seen."
  []
  (js/Promise. (fn [resolve reject]
                 (js/setTimeout #(-> (lazy/load reports-screen) (.then resolve reject)) 300))))

(defn not-found []
  [:p#screen "No such page"])
