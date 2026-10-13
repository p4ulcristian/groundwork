(ns groundwork.browser
  "Starting the app in the browser, and the door to its routing. The app's
   entry (the :browser settings' :entry) makes one call:

     (ns app.browser
       (:require [groundwork.browser :as gb] [app.routes :as routes] [app.shapes :as shapes]))

     (defn root [] [:main [nav] [gb/screen]])

     (defn start []
       (gb/start! {:root        root
                   :routes      routes/routes      ; route records, see groundwork.routing
                   :shapes      shapes/answers
                   :unknown     views/not-found    ; for an address no route has
                   :placeholder views/loading      ; while a screen's code loads on demand
                   :on-move     (fn [{:keys [from to]}] …)}))   ; after every move

   Groundwork then, in order: reads what the page shell put in the page (the
   person signed in, their session's flags, the language and the app's
   languages); sets up re-frame with the store, which opens the /parquery
   websocket; asks for the person's grants, so \"can I?\" answers; starts
   routing at the page's address; and mounts :root into <div id=\"app\">
   with Reagent. A save reloads the code and renders again.

     @(rf/subscribe [::person])    ; the person's id (text), or nil
     @(rf/subscribe [::flags])     ; #{:impersonated} and such
     @(rf/subscribe [::language])  ; \"en\": the address's, else the visit's
     @(rf/subscribe [::route])     ; :name :data :path-params :query :fragment …
     @(rf/subscribe [::query])     ; the address's query, {:stage \"curing\"}
     [gb/screen]                   ; the current route's screen

   An app moves and makes links only through here, never by building or
   reading addresses by hand:

     (gb/href :order {:order-id id})                  ; for <a href>; plain links move in-app
     (gb/navigate! :order {:order-id id})             ; or an address: (gb/navigate! \"/orders/5\")
     (gb/navigate! :orders nil {:stage \"curing\"} {:replace? true})
     (gb/push-query! {:stage \"curing\"})             ; a filter in the address, Back undoes it
     (gb/replace-query! {:search \"zinc\"})           ; no step for Back
     (gb/href :order {:order-id id} nil {:language \"de\"})

   A route's own title, reads, and code loaded on demand are in its record
   (groundwork.routing). Unsaved changes:

     (gb/unsaved! true)             ; the screen's form is dirty: moves wait, closing the tab asks
     @(rf/subscribe [::held-move])  ; the route someone tried to go to, or nil: show \"save or discard?\"
     (gb/continue-move!)            ; discard and go
     (gb/stay!)                     ; stay
     (gb/unsaved! false)            ; saved"
  (:require
   [groundwork.parquery-store :as store]
   [groundwork.routing :as routing]
   [re-frame.core :as rf]
   [reagent.dom.client :as rdc]))

(defn read-initial
  "The page shell's JSON, as {:person id-or-nil :flags #{…} :language …
   :languages {:supported […] :default …}-or-nil}. The person's id is text,
   like every id in the browser."
  [text]
  (let [data      (when (seq text) (js->clj (js/JSON.parse text)))
        languages (get data "languages")]
    {:person    (get data "person")
     :flags     (into #{} (map keyword) (get data "flags"))
     :language  (get data "language")
     :languages (when languages
                  {:supported (vec (get languages "supported")) :default (get languages "default")})}))

(rf/reg-event-db ::-init
                 (fn [db [_ initial]]
                   (assoc db :groundwork/session initial)))

(rf/reg-sub ::session (fn [db _] (:groundwork/session db)))
(rf/reg-sub ::person :<- [::session] (fn [session _] (:person session)))
(rf/reg-sub ::flags :<- [::session] (fn [session _] (:flags session #{})))
(rf/reg-sub ::route :<- [::routing/route] (fn [route _] route))
(rf/reg-sub ::query :<- [::routing/query] (fn [query _] query))
(rf/reg-sub ::held-move :<- [::routing/held] (fn [held _] held))
(rf/reg-sub ::language
            :<- [::session]
            :<- [::routing/route]
            (fn [[session route] _] (or (:language route) (:language session))))

(def href routing/href)
(def navigate! routing/navigate!)
(def push-query! routing/push-query!)
(def replace-query! routing/replace-query!)
(def unsaved! routing/unsaved!)
(def continue-move! routing/continue!)
(def stay! routing/stay!)

(defn screen
  "The current route's screen: its :view; for code that loads on demand, the
   placeholder until it's there; for an address no route has, the app's
   :unknown screen."
  []
  (let [{:keys [data unknown?] route-name :name} @(rf/subscribe [::routing/route])
        _loaded                                  @(rf/subscribe [::routing/loaded])
        view                                     (or (:view data) (routing/loaded-view route-name))
        placeholder                              (or (:placeholder data) (routing/option :placeholder))]
    (cond
      unknown?    (when-let [unknown (routing/option :unknown)] [unknown])
      view        [view]
      (:load data) (when placeholder [placeholder])
      :else       nil)))

(defonce ^:private mounted (atom nil))

(defn- render! []
  (when-let [{:keys [react-root root]} @mounted]
    (rdc/render react-root [root])))

(defn start!
  "Start the app in the browser: {:root :routes :shapes}, with :unknown,
   :placeholder and :on-move when wanted, and :max-asks when the server's is
   lower than 50."
  [{:keys [root routes shapes max-asks] :as options}]
  (let [initial (read-initial (some-> (js/document.getElementById "groundwork") .-textContent))]
    (rf/dispatch-sync [::-init initial])
    (rf/dispatch-sync [::store/start {:shapes shapes :max-asks max-asks}])
    (when (:person initial)
      (rf/dispatch [::store/ensure ::session [{:endpoint :groundwork/grants :params {}}]]))
    (routing/start! (assoc (select-keys options [:on-move :unknown :placeholder])
                           :routes    routes
                           :languages (:languages initial)
                           :language  (:language initial)))
    (when-not @mounted
      (reset! mounted {:react-root (rdc/create-root (js/document.getElementById "app"))}))
    (swap! mounted assoc :root root)
    (render!)))

(defn ^:dev/after-load reload!
  "After a save in development: forget cached subscriptions, render again."
  []
  (rf/clear-subscription-cache!)
  (render!))
