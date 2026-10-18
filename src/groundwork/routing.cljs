(ns groundwork.routing
  "Routing in the browser: an address leads to a screen. groundwork.browser
   starts it and is the door an app uses (navigate!, href, the query
   functions, screen); this namespace is the engine behind it.

   A route is a record, reitit's [address data] with:

     :name         its name, for links and moves by name
     :view         its screen, a Reagent component
     :title        the tab's title on arrival: text, or (fn [route] text);
                   without one, the page's own title
     :reads        the asks it needs on arrival: a list, or (fn [route] asks).
                   They are the store's for the owner :groundwork/route: ones
                   the next route shares are kept, the rest let go
     :load         instead of :view, for a screen whose code loads on demand:
                   (fn [] promise-of-the-view); :placeholder shows meanwhile.
                   The module the code arrives in brings its stylesheets
                   before its code runs (groundwork.loader)
     :placeholder  a component, for this route (else the app's)

   and whatever the server reads from the same record (:access, :page…; see
   groundwork.web/page-routes).

   The current route, in re-frame's db under :groundwork/route:

     {:name :order :data {…} :path \"/orders/5\" :path-params {:order-id \"5\"}
      :query {:tab \"notes\"} :fragment nil :language \"de\" :address \"/de/orders/5?tab=notes\"}

   or {:unknown? true …} for an address no route has.

   What every move does: the title, scrolling (the top on a new page, an
   #anchor when the address names one, nothing when only the query changed
   or on Back and Forward, where the browser puts the page back itself),
   the route's reads, and the app's :on-move hook with {:from :to}.

   With languages (the app's :web :languages, from the page shell), an
   address may start with one, /de/orders; the route is the same, its
   :language is that one, and links keep the language the visit is in.

   Unsaved changes: while (unsaved! true) holds, a move by link, by
   navigate! or by Back and Forward doesn't happen; it waits under
   (rf/subscribe [::held]) for (continue!) or (stay!), and closing the tab
   asks the browser's own question."
  (:require
   [clojure.string :as str]
   [groundwork.parquery.store :as store]
   [re-frame.core :as rf]
   [reitit.frontend :as rfr]
   [reitit.impl :as impl]))

;; =============================================================================
;; The browser underneath; tests replace it
;; =============================================================================

(defonce ^:private listeners (atom nil))

(defn- warn-before-unload [^js event]
  (.preventDefault event)
  (set! (.-returnValue event) ""))

(defonce ^{:doc "What routing asks of the browser, as functions; tests replace them."} env
  (atom {:location       (fn [] (str js/location.pathname js/location.search js/location.hash))
         :push!          (fn [address index] (js/history.pushState #js {:groundwork index} "" address))
         :replace!       (fn [address index] (js/history.replaceState #js {:groundwork index} "" address))
         :index          (fn [] (some-> js/history.state (unchecked-get "groundwork")))
         :go!            (fn [n] (js/history.go n))
         :assign!        (fn [address] (js/location.assign address))
         :title          (fn [] js/document.title)
         :title!         (fn [text] (set! js/document.title text))
         :scroll-top!    (fn [] (js/window.scrollTo 0 0))
         :scroll-to!     (fn [id] (some-> (js/document.getElementById id) (.scrollIntoView)))
         :warn-on-close! (fn [on?]
                           (if on?
                             (js/window.addEventListener "beforeunload" warn-before-unload)
                             (js/window.removeEventListener "beforeunload" warn-before-unload)))
         :listen!        (fn [on-pop on-link]
                           (when-let [{:keys [pop click]} @listeners]
                             (js/window.removeEventListener "popstate" pop)
                             (js/document.removeEventListener "click" click))
                           (let [pop   (fn [_] (on-pop))
                                 click (fn [^js event]
                                         (when (and (zero? (.-button event))
                                                    (not (.-defaultPrevented event))
                                                    (not (or (.-metaKey event) (.-ctrlKey event)
                                                             (.-shiftKey event) (.-altKey event))))
                                           (when-let [^js a (some-> (.-target event) (.closest "a[href]"))]
                                             (when (and (= (.-origin a) js/location.origin)
                                                        (str/blank? (.-target a))
                                                        (not (.hasAttribute a "download")))
                                               (when (on-link (str (.-pathname a) (.-search a) (.-hash a)))
                                                 (.preventDefault event))))))]
                             (js/window.addEventListener "popstate" pop)
                             (js/document.addEventListener "click" click)
                             (reset! listeners {:pop pop :click click})))}))

(defn- browser [k & args]
  (apply (get @env k) args))

;; =============================================================================
;; Addresses and routes
;; =============================================================================

(defonce ^:private state
  (atom {:router nil :languages nil :language nil :options {} :route nil :index 0
         :unsaved? false :held nil :reverting? false :loaded {} :default-title nil}))

(defn split-language
  "[language address-without-it] for an address that starts with a
   supported language, /de/orders; [nil address] otherwise."
  [{:keys [supported]} address]
  (if-let [[_ language more] (and (seq supported)
                                  (re-matches (re-pattern (str "^/(" (str/join "|" supported) ")(?=/|\\?|#|$)(.*)$"))
                                              address))]
    [language (if (str/starts-with? more "/") more (str "/" more))]
    [nil address]))

(defn route-at
  "The route an address leads to, or {:unknown? true …}. `now` is
   {:router :languages :language}: the language is the address's own, else
   the one the visit is in."
  [{:keys [router languages language]} address]
  (let [[named without]  (split-language languages address)
        [asked fragment] (str/split without #"#" 2)
        match            (rfr/match-by-path router asked)
        base             {:address  address
                          :fragment (not-empty fragment)
                          :language (or named language (:default languages))}]
    (if match
      (assoc base
             :name        (get-in match [:data :name])
             :data        (:data match)
             :path        (:path match)
             :path-params (:path-params match)
             :query       (or (:query-params match) {}))
      (assoc base :unknown? true :data {} :path (first (str/split asked #"\?" 2)) :path-params {} :query {}))))

(defn address-of
  "The address of a route by name, or of a path as given, with its query and
   in a language: the visit's, unless :language says another or the path
   names its own. The default language has no prefix."
  [{:keys [router languages language]} target path-params query {:keys [fragment] wanted :language}]
  (let [path            (if (keyword? target)
                          (or (:path (rfr/match-by-name router target path-params))
                              (throw (ex-info (str "No route " target " with these values")
                                              {:type ::no-route :route target :path-params path-params})))
                          target)
        [named path]    (if (keyword? target) [nil path] (split-language languages path))
        language        (or wanted named language)
        prefix          (when (and languages language (not= language (:default languages)))
                          (str "/" language))
        query           (into {} (remove (comp nil? val)) query)]
    (str prefix
         (if (and prefix (= "/" path)) "" path)
         (when (seq query) (str (if (str/includes? path "?") "&" "?") (impl/query-string query)))
         (when fragment (str "#" fragment)))))

(defn- now [] (select-keys @state [:router :languages :language]))

;; =============================================================================
;; What a move does
;; =============================================================================

(rf/reg-event-db ::-navigated (fn [db [_ route]] (assoc db :groundwork/route route)))
(rf/reg-event-db ::-held (fn [db [_ route]] (assoc db :groundwork/held-move route)))
(rf/reg-event-db ::-loaded (fn [db [_ route-name]] (update db :groundwork/loaded (fnil conj #{}) route-name)))

(rf/reg-sub ::route (fn [db _] (:groundwork/route db)))
(rf/reg-sub ::query :<- [::route] (fn [route _] (:query route {})))
(rf/reg-sub ::held (fn [db _] (:groundwork/held-move db)))
(rf/reg-sub ::loaded (fn [db _] (:groundwork/loaded db #{})))

(defn loaded-view
  "The screen of a route whose code loaded on demand, once it has."
  [route-name]
  (get-in @state [:loaded route-name]))

(defn option
  "One of the options the app started with (:unknown, :placeholder…)."
  [k]
  (get-in @state [:options k]))

(defn- load-view! [{:keys [data] route-name :name}]
  (when-let [load (:load data)]
    (when-not (contains? (:loaded @state) route-name)
      (-> (load)
          (.then (fn [view]
                   (swap! state assoc-in [:loaded route-name] view)
                   (rf/dispatch [::-loaded route-name])))))))

(defn- reads-of [{:keys [data] :as route}]
  (let [reads (:reads data)]
    (vec (if (fn? reads) (reads route) reads))))

(defn- arrive!
  "Everything arriving on a route does. how: :first (the page's own load),
   :pop (Back or Forward) or nil (a link, navigate!)."
  [to how]
  (let [from  (:route @state)
        title (get-in to [:data :title])]
    (swap! state assoc :route to :language (:language to))
    (if (= :first how)
      (rf/dispatch-sync [::-navigated to])
      (rf/dispatch [::-navigated to]))
    (browser :title! (or (if (fn? title) (title to) title) (:default-title @state)))
    (when (nil? how)
      (cond
        (not= (:path from) (:path to))                              (if (:fragment to)
                                                                      (browser :scroll-to! (:fragment to))
                                                                      (browser :scroll-top!))
        (and (:fragment to) (not= (:fragment from) (:fragment to))) (browser :scroll-to! (:fragment to))))
    (rf/dispatch [::store/own :groundwork/route (if (:unknown? to) [] (reads-of to))])
    (load-view! to)
    (when-let [hook (option :on-move)]
      (hook {:from from :to to}))))

(defn- hold!
  "A move that has to wait for (continue!) or (stay!)."
  [held to]
  (swap! state assoc :held held)
  (rf/dispatch [::-held to]))

(defn move!
  "Go to an address. Returns true when routing took the move: false for an
   address no route has (the caller lets the browser load it). With unsaved
   changes the move waits, unless :force? is set."
  [address {:keys [replace? force?]}]
  (let [to (route-at (now) address)]
    (cond
      (:unknown? to)
      false

      (and (:unsaved? @state) (not force?))
      (do (hold! {:address address :replace? replace?} to) true)

      :else
      (let [index (if replace? (:index @state) (inc (:index @state)))]
        (browser (if replace? :replace! :push!) address index)
        (swap! state assoc :index index)
        (arrive! to nil)
        true))))

(defn- popped!
  "Back or Forward moved the address already. With unsaved changes it is
   moved back, and the move waits."
  []
  (let [address (browser :location)
        index   (or (browser :index) (:index @state))
        delta   (- index (:index @state))]
    (cond
      (:reverting? @state)
      (swap! state assoc :reverting? false)

      (and (:unsaved? @state) (not (zero? delta)))
      (do (swap! state assoc :reverting? true)
          (hold! {:delta delta} (route-at (now) address))
          (browser :go! (- delta)))

      :else
      (do (swap! state assoc :index index)
          (arrive! (route-at (now) address) :pop)))))

(defn unsaved!
  "Say whether the screen holds unsaved changes. While it does, moves wait
   and closing the tab asks first."
  [on?]
  (swap! state assoc :unsaved? (boolean on?))
  (browser :warn-on-close! (boolean on?))
  (when-not on?
    (swap! state assoc :held nil)
    (rf/dispatch [::-held nil])))

(defn stay!
  "Drop the move that is waiting; the changes are still unsaved."
  []
  (swap! state assoc :held nil)
  (rf/dispatch [::-held nil]))

(defn continue!
  "Make the move that is waiting; the unsaved changes are let go."
  []
  (when-let [{:keys [address replace? delta]} (:held @state)]
    (unsaved! false)
    (if delta
      (browser :go! delta)
      (move! address {:replace? replace? :force? true}))))

;; =============================================================================
;; The door
;; =============================================================================

(defn href
  "The address of a route, for a link: (href :order {:order-id id}), with a
   query map, and {:language \"de\" :fragment \"notes\"} when wanted."
  ([target] (href target nil nil nil))
  ([target path-params] (href target path-params nil nil))
  ([target path-params query] (href target path-params query nil))
  ([target path-params query options]
   (address-of (now) target path-params query options)))

(defn navigate!
  "Go to a route by name, or to an address, like a link would:
   (navigate! :order {:order-id id}), (navigate! \"/orders/5?tab=notes\").
   {:replace? true} doesn't add to Back's history. An address no route has
   is loaded from the server."
  ([target] (navigate! target nil nil nil))
  ([target path-params] (navigate! target path-params nil nil))
  ([target path-params query] (navigate! target path-params query nil))
  ([target path-params query options]
   (let [address (address-of (now) target path-params query options)]
     (when-not (move! address options)
       (browser :assign! address)))))

(defn- query! [query options]
  (let [{:keys [path]} (:route @state)]
    (move! (address-of (now) path nil query nil) options)))

(defn push-query!
  "The address's query becomes this map, as a new step Back returns from: a
   filter someone chose. A nil value leaves its key out."
  [query]
  (query! query {}))

(defn replace-query!
  "The address's query becomes this map, without a step for Back. Unsaved
   changes don't hold it: it is the same screen, noting where it is."
  [query]
  (query! query {:replace? true :force? true}))

(defn start!
  "Start routing: {:routes :languages :on-move :unknown :placeholder}. The
   address the page loaded at is the first route."
  [{:keys [routes languages language] :as options}]
  (swap! state assoc
         :router        (rfr/router routes)
         :languages     languages
         :language      (or language (:default languages))
         :options       (select-keys options [:on-move :unknown :placeholder])
         :route         nil
         :unsaved?      false
         :held          nil
         :reverting?    false
         :default-title (or (:default-title @state) (browser :title)))
  (browser :listen! popped! #(move! % {}))
  (let [address (browser :location)
        index   (or (browser :index) 0)]
    (swap! state assoc :index index)
    (browser :replace! address index)
    (arrive! (route-at (now) address) :first)))
