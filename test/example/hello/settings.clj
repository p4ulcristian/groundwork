(ns example.hello.settings
  "The example app's development settings, for running it as a service in
   groundwork's systemd check (docs/groundwork-dev.service)."
  (:require
   [example.hello.routes :as routes]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web]))

(def dev
  (-> app-settings
      (assoc :environment :development)
      (assoc :browser {:build :example-hello-dev :entry 'example.hello.browser/start
                       :output-dir "test/public/example-dev" :asset-path "/example-dev" :watch? true})
      (assoc-in [:server :port] (parse-long (or (System/getenv "EXAMPLE_PORT") "18123")))
      (assoc-in [:sessions :origin] (str "http://127.0.0.1:" (or (System/getenv "EXAMPLE_PORT") "18123")))
      (assoc-in [:web :routes] (into (web/page-routes routes/routes)
                                     [["/ping" {:get (fn [_] {:status 200 :body "pong"}) :access :public}]]))))
