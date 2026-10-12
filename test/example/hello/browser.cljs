(ns example.hello.browser
  "A tiny app's browser entry, for groundwork.build-test: it compiles with
   Groundwork's browser half and starts it."
  (:require
   [groundwork.browser :as gb]
   [re-frame.core :as rf]))

(defn- home []
  [:p "Hello, " (or (some-> @(rf/subscribe [::gb/person]) str) "visitor") "."])

(defn- root []
  (let [{:keys [data]} @(rf/subscribe [::gb/route])]
    [:main (when-let [view (:view data)] [view])]))

(defn start []
  (gb/start! {:root   root
              :routes [["/" {:name :home :view home}]]
              :shapes {}}))
