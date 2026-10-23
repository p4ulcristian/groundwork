(ns groundwork.browser.build-check
  "An entry that uses every browser library, and names a stylesheet through
   browser-build, so the browser build's test compiles all of them, as an app's
   release build would: groundwork's start (which starts parlor), a root drawn
   with parlor, reading an answer, \"can I?\" and the page, and a page
   whose module, groundwork.browser.build-check-admin, loads on demand."
  (:require
   [groundwork.browser.main.api :as main]
   [parlor.api :as parlor]
   [re-frame.core :as rf]
   [browser-build.api :as styles]
   [wire.big]))

(def ^:private build-check-styles (styles/use-stylesheet "groundwork/browser/build_check.css"))

(def ^:private shapes-by-name
  {:orders/all {:params-shape [:map] :answer-shape [:vector :uuid] :refusals {}}})

(defn- draw-page [page]
  (let [{:keys [status data]} @(rf/subscribe [:parlor/answer [:orders/all {}]])
        can-see?              @(rf/subscribe [:parlor/can-i? :orders/see [:shop "7"]])]
    [:p (parlor/tr :orders/title {}) " " (pr-str (:name page)) " " (name status) " " (when can-see? (pr-str data))]))

(defn- root []
  [:div {:class "build-check"}
   [:a {:href "/admin?tab=people"} "Admin"]
   [:button {:on-click #(rf/dispatch [:parlor/hold {:ask-with [:build-check/ask-leave] :text :orders/unsaved :args {}}])} "Edit"]
   [:button {:on-click #(rf/dispatch [:parlor/go-to :admin/page {} {:tab :people} :push])} "People"]
   [parlor/show-page draw-page]])

(defn start! []
  (main/start-app! {:shapes-by-name     shapes-by-name
                    :sse-handlers       {}
                    :questions-of-pages {:orders/page (fn [_] [[:orders/all {}]])
                                         :admin/page  (fn [_] [])}
                    :on-answer-failed   nil
                    :on-signed-out      [:build-check/to-login]
                    :on-move            nil
                    :on-line-change     nil
                    :query-shapes-of-pages {:orders/page nil
                                            :admin/page  [:map [:tab {:optional true} :keyword]]}
                    :placeholder        (fn [_ status] [:p "Loading… " (name status)])
                    :exact-decimals     wire.big/big})
  (parlor/mount-root! "app" root))
