(ns groundwork-test.sample.browser
  "The sample app's browser half, for groundwork's end-to-end test: one page,
   the orders of a shop, with a form that adds one when the person may
   (parlor's can-i?), holding the page while a note is unsaved; and a link
   to the reports, whose page is drawn by a module loaded on demand."
  (:require
   [groundwork.browser.main.api :as main]
   [groundwork-test.sample.shapes :as shapes]
   [parlor.api :as parlor]
   [re-frame.core :as rf]
   [reagent.core :as reagent]
   [browser-build.api :as styles]))

(def ^:private browser-styles (styles/use-stylesheet "groundwork_test/sample/browser.css"))

(defonce ^:private drawers
  ;; each page's component, by page name; a module loaded on demand adds its own
  (reagent/atom {}))

(defn add-drawer!
  "The component that draws a page, by its name."
  [page-name component]
  (swap! drawers assoc page-name component))

(rf/reg-event-fx ::added
  (fn [{:keys [db]} [_ result]]
    {:db (assoc db ::last-add result)
     :fx [[:dispatch [:parlor/let-go]]]}))

(rf/reg-sub ::last-add
  (fn [db _] (::last-add db)))

(defn- add-form [shop-id]
  (let [note (reagent/atom "")]
    (fn [shop-id]
      [:form#add-form {:on-submit (fn [event]
                                    (.preventDefault event)
                                    (rf/dispatch [:parlor/send [:orders/add {:order/shop-id shop-id :order/note @note}] [::added]])
                                    (reset! note ""))}
       [:input#note {:value     @note
                     :on-change (fn [event]
                                  (reset! note (.. event -target -value))
                                  ;; the guard holds the text's key; parlor asks it in the language shown then
                                  (rf/dispatch [:parlor/hold {:text :orders/unsaved :args {}}]))}]
       [:button#add {:type "submit"} (parlor/tr :orders/add {})]])))

(defn- orders-page [shop-id]
  (let [{:keys [status data error]} @(rf/subscribe [:parlor/answer [:orders/of-shop {:shop/id shop-id}]])
        actions               @(rf/subscribe [:parlor/answer [:doorman/my-actions {}]])
        can-add?              @(rf/subscribe [:parlor/can-i? :orders/add [:shop shop-id]])
        last-add              @(rf/subscribe [::last-add])]
    [:main.sample {:data-actions (name (:status actions :none))}
     [:h1#title (parlor/tr :orders/title {})]
     (case status
       (:ready :refresh-error) (into [:ul#orders] (for [{:order/keys [id note]} data] ^{:key (str id)} [:li.order {:data-id (str id)} note]))
       :error                  [:p#refused (parlor/tr (:bridge/refused error) (:bridge/data error))]   ; another's shop: :doorman/not-found
       [:p#loading (name (or status :none))])
     (when can-add? [add-form shop-id])
     [:p#last-add (when last-add (str (name (:status last-add)) " " (some-> last-add :data :order/id str)))]
     [:a#to-reports {:href "/reports"} "Reports"]]))

(add-drawer! :orders/page (fn [{:keys [shop-id]}] [orders-page (parse-uuid shop-id)]))

(defn- draw-page [{:keys [name path-params]}]
  (if-let [drawer (get @drawers name)]
    [drawer path-params]
    [:p#no-page "No such page."]))

(defn- root []
  [parlor/show-page draw-page])

(defn start! []
  (let [app (main/start-app! {:shapes-by-name        shapes/shapes-by-name
                             :sse-handlers          {}
                             :questions-of-pages    {:orders/page  (fn [{:keys [path-params]}]
                                                                     [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])
                                                     :reports/page (fn [_] [])}
                             :on-answer-failed      nil
                             :on-signed-out         nil
                             :on-move               nil
                             :query-shapes-of-pages {:orders/page nil :reports/page nil}
                             :placeholder           (fn [_page status] [:p#module-loading (str status)])
                             :exact-decimals        nil})]
    ;; the end-to-end test's error boundary: an error it caught, reported as :render
    (set! (.-sampleReportRenderError js/window)
          (fn [message] (main/report-browser-error! app {:kind :render :error (js/Error. message)})))
    (parlor/mount-root! "app" root)))
