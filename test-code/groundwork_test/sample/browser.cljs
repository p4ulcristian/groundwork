(ns groundwork-test.sample.browser
  "The sample app's browser half, for groundwork's end-to-end test: one page,
   the orders of a shop, with a form that adds one when the person may
   (groundwork-screens's can-i?), holding the page while a note is unsaved; a link to
   the reports, whose page is drawn by a module loaded on demand; and the
   sse line's status, over every page."
  (:require
   [groundwork.browser.main.api :as main]
   [groundwork-test.sample.shapes :as shapes]
   [groundwork-screens.api :as screens]
   [re-frame.core :as rf]
   [reagent.core :as reagent]
   [groundwork-browser-build.api :as styles]))

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
     :fx [[:dispatch [:groundwork-screens/let-go]]]}))

(rf/reg-sub ::last-add
  (fn [db _] (::last-add db)))

(rf/reg-event-db ::line-changed
  (fn [db [_ {:keys [line]}]] (assoc db ::line line)))

(rf/reg-sub ::line
  (fn [db _] (::line db)))

(defn- make-draft
  "A new draft: its note, and the one-time key its Save sends, kept with
   it, so a Save sent again is the same write."
  []
  {:note "" :once-key (screens/make-once-key)})

(defn- add-form [shop-id]
  (let [draft (reagent/atom (make-draft))]
    (fn [shop-id]
      [:form#add-form {:on-submit (fn [event]
                                    (.preventDefault event)
                                    (let [{:keys [note once-key]} @draft]
                                      (rf/dispatch [:groundwork-screens/send [:orders/add {:order/shop-id shop-id :order/note note}] [::added]
                                                    {:once-key once-key :keep-alive? false}]))
                                    (reset! draft (make-draft)))}
       [:input#note {:value     (:note @draft)
                     :on-change (fn [event]
                                  (swap! draft assoc :note (.. event -target -value))
                                  ;; the guard holds the text's key; the browser's confirm asks it in the language shown then
                                  (rf/dispatch [:groundwork-screens/hold {:ask-with :browser-confirm :text :orders/unsaved :args {}}]))}]
       [:button#add {:type "submit"} (screens/tr :orders/add {})]])))

(defn- orders-page [shop-id]
  (let [{:keys [status data error]} @(rf/subscribe [:groundwork-screens/answer [:orders/of-shop {:shop/id shop-id}]])
        actions               @(rf/subscribe [:groundwork-screens/answer [:groundwork-access/my-actions {}]])
        can-add?              @(rf/subscribe [:groundwork-screens/can-i? :orders/add [:shop shop-id]])
        last-add              @(rf/subscribe [::last-add])]
    [:main.sample {:data-actions (name (:status actions :none))}
     [:h1#title (screens/tr :orders/title {})]
     (case status
       (:ready :refresh-error) (into [:ul#orders] (for [{:order/keys [id note]} data] ^{:key (str id)} [:li.order {:data-id (str id)} note]))
       :error                  [:p#refused (screens/tr (:groundwork-bridge/refused error) (:groundwork-bridge/data error))]   ; another's shop: :groundwork-access/not-found
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
  [:<>
   [:p#line {:data-line (some-> @(rf/subscribe [::line]) name)}]
   [screens/show-page draw-page]])

(defn start! []
  (let [app (main/start-app! {:shapes-by-name        shapes/shapes-by-name
                             :sse-handlers          {}
                             :questions-of-pages    {:orders/page  (fn [{:keys [path-params]}]
                                                                     [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])
                                                     :reports/page (fn [_] [])}
                             :on-answer-failed      nil
                             :on-signed-out         nil
                             :on-move               nil
                             :on-line-change        [::line-changed]
                             :query-shapes-of-pages {:orders/page nil :reports/page nil}
                             :placeholder           (fn [_page status] [:p#module-loading (str status)])
                             :exact-decimals        nil})]
    ;; the end-to-end test's error boundary: an error it caught, reported as :render
    (set! (.-sampleReportRenderError js/window)
          (fn [message] (main/report-browser-error! app {:kind :render :error (js/Error. message)})))
    (screens/mount-root! "app" root)))
