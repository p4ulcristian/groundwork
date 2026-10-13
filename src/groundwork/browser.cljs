(ns groundwork.browser
  "Starting the app in the browser. The app's entry (the :browser settings'
   :entry) makes one call:

     (ns app.browser
       (:require [groundwork.browser :as gb] [app.views :as views] [app.shapes :as shapes]))

     (defn start []
       (gb/start! {:root   views/root
                   :routes [[\"/\"                 {:name :home  :view views/home}]
                            [\"/orders/:order-id\" {:name :order :view views/order}]]
                   :shapes shapes/answers}))

   Groundwork then, in order: reads what the page shell put in the page (the
   person signed in, their session's flags, the language); sets up re-frame
   with the store, which opens the /parquery websocket; asks for the person's
   grants, so \"can I?\" answers; starts the browser's routing (reitit), the
   current match under (subscribe [::route]); and mounts :root into
   <div id=\"app\"> with Reagent. A save reloads the code and renders again.

     @(rf/subscribe [::person])    ; the person's id, or nil
     @(rf/subscribe [::flags])     ; #{:impersonated} and such
     @(rf/subscribe [::language])  ; \"en\"
     @(rf/subscribe [::route])     ; reitit's match: :data (:name, :view…), :path-params…
     (gb/navigate! :order {:order-id id})"
  (:require
   [groundwork.parquery-store :as store]
   [re-frame.core :as rf]
   [reagent.dom.client :as rdc]
   [reitit.frontend :as rfr]
   [reitit.frontend.easy :as rfe]))

(defn read-initial
  "The page shell's JSON, as {:person id-or-nil :flags #{…} :language …}.
   The person's id is text, like every id in the browser."
  [text]
  (let [data (when (seq text) (js->clj (js/JSON.parse text)))]
    {:person   (get data "person")
     :flags    (into #{} (map keyword) (get data "flags"))
     :language (get data "language")}))

(rf/reg-event-db ::-init
                 (fn [db [_ initial]]
                   (assoc db :groundwork/session initial)))

(rf/reg-event-db ::-navigated
                 (fn [db [_ match]]
                   (assoc db :groundwork/route match)))

(rf/reg-sub ::session (fn [db _] (:groundwork/session db)))
(rf/reg-sub ::person :<- [::session] (fn [session _] (:person session)))
(rf/reg-sub ::flags :<- [::session] (fn [session _] (:flags session #{})))
(rf/reg-sub ::language :<- [::session] (fn [session _] (:language session)))
(rf/reg-sub ::route (fn [db _] (:groundwork/route db)))

(defn navigate!
  "Go to a named browser route, like a link would."
  ([route-name] (navigate! route-name nil nil))
  ([route-name path-params] (navigate! route-name path-params nil))
  ([route-name path-params query-params]
   (rfe/push-state route-name path-params query-params)))

(defonce ^:private mounted (atom nil))

(defn- render! []
  (when-let [{:keys [react-root root]} @mounted]
    (rdc/render react-root [root])))

(defn start!
  "Start the app in the browser: {:root :routes :shapes}, and :max-asks
   when the server's is lower than 50."
  [{:keys [root routes shapes max-asks]}]
  (let [initial (read-initial (some-> (js/document.getElementById "groundwork") .-textContent))]
    (rf/dispatch-sync [::-init initial])
    (rf/dispatch-sync [::store/start {:shapes shapes :max-asks max-asks}])
    (when (:person initial)
      (rf/dispatch [::store/ensure ::session [{:endpoint :groundwork/grants :params {}}]]))
    (rfe/start! (rfr/router routes) #(rf/dispatch [::-navigated %]) {:use-fragment false})
    (when-not @mounted
      (reset! mounted {:react-root (rdc/create-root (js/document.getElementById "app"))}))
    (swap! mounted assoc :root root)
    (render!)))

(defn ^:dev/after-load reload!
  "After a save in development: forget cached subscriptions, render again."
  []
  (rf/clear-subscription-cache!)
  (render!))
