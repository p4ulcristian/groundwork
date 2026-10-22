(ns groundwork.browser.build-check
  "An entry that uses every browser library, so the browser build's test
   compiles all of them, as an app's release build would."
  (:require
   [groundwork.browser.main.api :as main]
   [groundwork.browser.sync.api :as sync]))

(def ^:private shapes-by-name
  {:orders/all {:params-shape [:map] :answer-shape [:vector :uuid]}})

(defn start! []
  (let [app (main/start-app! {:shapes-by-name shapes-by-name
                              :sse-handlers   {}
                              :on-page        #(js/console.log "page" (pr-str (:name %)))})]
    (sync/watch-question! (:store app) [:orders/all {}] #(js/console.log "orders" (pr-str %)))))
