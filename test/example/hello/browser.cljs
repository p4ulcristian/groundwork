(ns example.hello.browser
  "A tiny app's browser entry, for groundwork.build-test and
   groundwork.browser-routing-test: it compiles with Groundwork's browser
   half and starts it. Its stylesheet comes after those of the namespaces
   it requires."
  {:css ["base.css"]}
  (:require
   [example.hello.routes :as routes]
   [example.hello.views :as views]
   [groundwork.browser :as gb]))

(defn- root []
  [:main [views/nav] [gb/screen]])

(defn start []
  (gb/start! {:root        root
              :routes      routes/routes
              :shapes      {}
              :unknown     views/not-found
              :placeholder views/loading}))
