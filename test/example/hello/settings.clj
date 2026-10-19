(ns example.hello.settings
  "The example app's settings: its modules and styles, and its development
   settings for running it as a service in groundwork's systemd check
   (docs/groundwork-dev.service)."
  (:require
   [example.hello.routes :as routes]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web]))

(def modules
  "The example's browser modules: the reports screen is a module of its own,
   loaded on arrival."
  {:main    {:init-fn 'example.hello.browser/start}
   :reports {:entries ['example.hello.reports] :depends-on #{:main}}})

(defn styles
  "The example's :styles, its bundles under test/public/<folder>."
  [folder]
  {:sources    ["test/example"]
   :output-dir (str "test/public/" folder)
   :asset-path (str "/" folder)
   :targets    "safari >= 16.4, chrome >= 119"})

(def dev
  (-> app-settings
      (assoc :environment :development)
      (assoc :browser {:build :example-hello-dev :entry 'example.hello.browser/start
                       :output-dir "test/public/example-dev" :asset-path "/example-dev" :watch? true
                       :decimals? false
                       :modules modules :loader? true})
      (assoc :styles (styles "example-dev-css"))
      (assoc-in [:page :stylesheets] [])
      (assoc-in [:server :port] (parse-long (or (System/getenv "EXAMPLE_PORT") "18123")))
      (assoc-in [:sessions :origin] (str "http://127.0.0.1:" (or (System/getenv "EXAMPLE_PORT") "18123")))
      (assoc-in [:web :routes] (into (web/page-routes routes/routes)
                                     [["/ping" {:get (fn [_] {:status 200 :body "pong"}) :access :public}]]))))
