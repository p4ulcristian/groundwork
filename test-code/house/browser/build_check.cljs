(ns house.browser.build-check
  "An entry that uses every browser library, and names a stylesheet through
   showroom, so the browser build's test compiles all of them, as an app's
   release build would: house's start (which starts parlor), a root drawn
   with parlor, reading an answer, \"can I?\" and the page."
  (:require
   [house.browser.main.api :as main]
   [parlor.api :as parlor]
   [re-frame.core :as rf]
   [showroom.api :as styles]
   [wire.big]))

(def ^:private build-check-styles (styles/use-stylesheet "house/browser/build_check.css"))

(def ^:private shapes-by-name
  {:orders/all {:params-shape [:map] :answer-shape [:vector :uuid]}})

(defn- root []
  (let [{:keys [status data]} @(rf/subscribe [:parlor/answer [:orders/all {}]])
        can-see?              @(rf/subscribe [:parlor/can-i? :orders/see [:shop "7"]])
        page                  @(rf/subscribe [:parlor/page])]
    [:div {:class "build-check"}
     [:p (pr-str (:name page)) " " (name status) " " (when can-see? (pr-str data))]]))

(defn start! []
  (main/start-app! {:shapes-by-name     shapes-by-name
                    :sse-handlers       {}
                    :questions-of-pages {:orders/page (fn [_] [[:orders/all {}]])}
                    :on-answer-failed   nil
                    :on-move            nil
                    :exact-decimals     wire.big/big})
  (parlor/mount-root! "app" root))
