(ns groundwork.browser.routing-test
  "Routing in the browser, with the browser itself replaced: addresses and
   routes, what every move does (title, scrolling, the route's reads, the
   hook), the query as state, languages, code loaded on demand, and unsaved
   changes holding links, navigate! and Back."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [groundwork.endpoints.http :as http]
   [groundwork.endpoints.websocket :as websocket]
   [groundwork.endpoints.wire :as wire]
   [groundwork.endpoints.store :as store]
   [groundwork.browser.routing :as routing]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]
   [reitit.frontend :as rfr]))

(defn- orders-view [] [:p "orders"])
(defn- order-view [] [:p "order"])
(defn- lazy-view [] [:p "lazy"])

(def lazy-loads (atom 0))

(def routes
  [["/" {:name :home :view orders-view}]
   ["/orders" {:name  :orders :view orders-view :title "Orders"
               :reads [{:endpoint :orders/list :params {}} {:endpoint :shop/get :params {}}]}]
   ["/orders/:order-id" {:name  :order :view order-view
                         :title (fn [route] (str "Order " (get-in route [:path-params :order-id])))
                         :reads (fn [route] [{:endpoint :orders/get :params {:order/id (get-in route [:path-params :order-id])}}
                                             {:endpoint :shop/get :params {}}])}]
   ["/reports" {:name :reports
                :load (fn [] (swap! lazy-loads inc) (js/Promise.resolve lazy-view))}]])

(def languages {:supported ["en" "de"] :default "en"})
(def now {:router (rfr/router routes) :languages languages :language "en"})

(deftest an-address-names-its-language-or-not
  (is (= ["de" "/orders"] (routing/split-language languages "/de/orders")))
  (is (= ["de" "/"] (routing/split-language languages "/de")))
  (is (= ["de" "/?x=1"] (routing/split-language languages "/de?x=1")))
  (is (= [nil "/dealers"] (routing/split-language languages "/dealers")) "a path that only starts like a language")
  (is (= [nil "/de/orders"] (routing/split-language nil "/de/orders")) "an app with one language"))

(deftest an-address-leads-to-a-route
  (let [route (routing/route-at now "/de/orders/5?tab=notes&page=2#history")]
    (is (= :order (:name route)))
    (is (= {:order-id "5"} (:path-params route)))
    (is (= {:tab "notes" :page "2"} (:query route)))
    (is (= "history" (:fragment route)))
    (is (= "de" (:language route)))
    (is (= "/orders/5" (:path route)) "the path, without language or query"))
  (testing "without a language in the address: the one the visit is in"
    (is (= "de" (:language (routing/route-at (assoc now :language "de") "/orders")))))
  (testing "an address no route has"
    (is (= {:unknown? true :path "/nowhere"} (select-keys (routing/route-at now "/nowhere?x=1") [:unknown? :path])))))

(deftest a-route-has-an-address
  (is (= "/orders/5" (routing/address-of now :order {:order-id 5} nil nil)))
  (is (= "/orders?stage=curing" (routing/address-of now :orders nil {:stage "curing" :search nil} nil)))
  (is (= "/orders#top" (routing/address-of now :orders nil nil {:fragment "top"})))
  (testing "links keep the visit's language; the default has no prefix"
    (is (= "/de/orders/5" (routing/address-of (assoc now :language "de") :order {:order-id 5} nil nil)))
    (is (= "/de" (routing/address-of (assoc now :language "de") :home nil nil nil)))
    (is (= "/orders" (routing/address-of (assoc now :language "de") :orders nil nil {:language "en"}))))
  (testing "an address as given, in the visit's language unless it names its own"
    (is (= "/de/orders?x=1" (routing/address-of (assoc now :language "de") "/orders?x=1" nil nil nil)))
    (is (= "/orders" (routing/address-of now "/orders" nil nil nil)))
    (is (= "/de/orders" (routing/address-of now "/de/orders" nil nil nil))))
  (testing "a name no route has"
    (is (thrown? js/Error (routing/address-of now :nope nil nil nil)))))

;; -----------------------------------------------------------------------------
;; The engine, on a browser made of an atom
;; -----------------------------------------------------------------------------

(def browser (atom nil))
(def moves (atom []))

(defn- fake-env []
  {:location       #(:address (nth (:entries @browser) (:at @browser)))
   :index          #(:index (nth (:entries @browser) (:at @browser)))
   :push!          (fn [address index]
                     (swap! browser (fn [{:keys [entries at] :as b}]
                                      (assoc b :entries (conj (vec (take (inc at) entries)) {:address address :index index})
                                             :at (inc at)))))
   :replace!       (fn [address index]
                     (swap! browser (fn [{:keys [at] :as b}] (assoc-in b [:entries at] {:address address :index index}))))
   :go!            (fn [n] (swap! browser update :at + n) ((:on-pop @browser)))
   :assign!        (fn [address] (swap! browser update :did conj [:assign address]))
   :title          (fn [] "Acme")
   :title!         (fn [text] (swap! browser assoc :title text))
   :scroll-top!    (fn [] (swap! browser update :did conj :top))
   :scroll-to!     (fn [id] (swap! browser update :did conj [:to id]))
   :warn-on-close! (fn [on?] (swap! browser assoc :warns? on?))
   :listen!        (fn [on-pop on-link] (swap! browser assoc :on-pop on-pop :on-link on-link))})

(defn- address [] (:address (nth (:entries @browser) (:at @browser))))
(defn- did [] (:did @browser))
(defn- route [] @(rf/subscribe [::routing/route]))
(defn- owned []
  (into #{} (keep (fn [[k entry]] (when (contains? (:owners entry) :groundwork/route) k)))
        (get-in @rf-db/app-db [:groundwork/endpoints-store :entries])))
(defn- back! [] (swap! browser update :at dec) ((:on-pop @browser)))
(defn- click! [address] ((:on-link @browser) address))

(defn- fresh! [at]
  (reset! browser {:entries [{:address at :index nil}] :at 0 :did [] :title "Acme"})
  (reset! moves [])
  (reset! lazy-loads 0)
  (reset! rf-db/app-db {})
  (reset! routing/env (fake-env))
  (reset! http/transport (fn [body on-text _]
                           (js/setTimeout #(on-text (wire/write-json (mapv (fn [_] {:status "ok" :data nil}) (wire/read-json body)))) 1)))
  (reset! websocket/make-socket (fn [_] #js {:readyState 0 :send (fn [_]) :close (fn [])}))
  (rf/dispatch-sync [::store/start {:shapes {} :line {:url "ws://test/parquery" :min-wait-ms 5}}])
  (routing/start! {:routes routes :languages languages :language "en"
                   :on-move #(swap! moves conj [(get-in % [:from :name]) (get-in % [:to :name])])}))

(defn- in-turn
  "Run the steps one after another, each a tick after the last."
  [done & steps]
  (if-let [[step & more] (seq steps)]
    (js/setTimeout (fn [] (step) (apply in-turn done more)) 15)
    (done)))

(deftest what-every-move-does
  (async done
         (fresh! "/orders?stage=curing")
         (in-turn done
                  (fn []
                    (testing "the page's own address is the first route"
                      (is (= :orders (:name (route))))
                      (is (= {:stage "curing"} @(rf/subscribe [::routing/query])))
                      (is (= "Orders" (:title @browser)))
                      (is (= [] (did)) "no scrolling on the page's own load")
                      (is (= #{[:orders/list {}] [:shop/get {}]} (owned)) "its reads are the store's"))
                    (routing/navigate! :order {:order-id 5}))
                  (fn []
                    (testing "a move by name: the address, the title, the top of the page, the reads"
                      (is (= "/orders/5" (address)))
                      (is (= "Order 5" (:title @browser)))
                      (is (= [:top] (did)))
                      (is (= #{[:orders/get {:order/id "5"}] [:shop/get {}]} (owned))
                          "what the last route shared is kept, the rest let go")
                      (is (some? (get-in @rf-db/app-db [:groundwork/endpoints-store :entries [:orders/list {}] :released-at]))))
                    (routing/push-query! {:tab "notes" :empty nil}))
                  (fn []
                    (testing "a filter in the address: a step for Back, no scrolling"
                      (is (= "/orders/5?tab=notes" (address)))
                      (is (= {:tab "notes"} (:query (route))))
                      (is (= [:top] (did))))
                    (routing/replace-query! {:tab "history"})
                    (is (= 3 (count (:entries @browser))) "replace adds no step")
                    (back!))
                  (fn []
                    (testing "Back: the route before, no scrolling of ours"
                      (is (= "/orders/5" (address)))
                      (is (= {} (:query (route))))
                      (is (= [:top] (did))))
                    (click! "/orders#totals"))
                  (fn []
                    (testing "a link moves in-app; an #anchor is scrolled to"
                      (is (= :orders (:name (route))))
                      (is (= [:top [:to "totals"]] (did))))
                    (testing "an address no route has is left to the browser"
                      (is (false? (click! "/logout")))
                      (routing/navigate! "/logout")
                      (is (= [:assign "/logout"] (last (did)))))
                    (routing/navigate! "/de/orders/7"))
                  (fn []
                    (testing "an address with a language: the same route, in that language, and links keep it"
                      (is (= :order (:name (route))))
                      (is (= "de" (:language (route))))
                      (is (= "/de/orders" (routing/href :orders)))
                      (is (= "/orders" (routing/href :orders nil nil {:language "en"}))))
                    (testing "the hook heard every move"
                      (is (= [[nil :orders] [:orders :order] [:order :order] [:order :order] [:order :order]
                              [:order :orders] [:orders :order]]
                             @moves)))))))

(deftest a-screen-whose-code-loads-on-demand
  (async done
         (fresh! "/")
         (in-turn done
                  (fn []
                    (is (nil? (routing/loaded-view :reports)))
                    (routing/navigate! :reports))
                  (fn []
                    (is (= lazy-view (routing/loaded-view :reports)) "loaded on arrival")
                    (is (contains? @(rf/subscribe [::routing/loaded]) :reports))
                    (routing/navigate! :home))
                  (fn [] (routing/navigate! :reports))
                  (fn [] (is (= 1 @lazy-loads) "once")))))

(deftest unsaved-changes-hold-every-move
  (async done
         (fresh! "/orders")
         (in-turn done
                  (fn [] (routing/navigate! :order {:order-id 5}))
                  (fn []
                    (routing/unsaved! true)
                    (is (true? (:warns? @browser)) "closing the tab asks")
                    (testing "a link waits"
                      (is (true? (click! "/orders")))
                      (is (= "/orders/5" (address)))))
                  (fn []
                    (is (= :orders (:name @(rf/subscribe [::routing/held]))) "the screen can ask: save or discard?")
                    (routing/stay!))
                  (fn []
                    (is (nil? @(rf/subscribe [::routing/held])))
                    (is (= :order (:name (route))))
                    (testing "Back waits too: the address is put back"
                      (back!)
                      (is (= "/orders/5" (address)))))
                  (fn []
                    (is (= :orders (:name @(rf/subscribe [::routing/held]))))
                    (is (= :order (:name (route))))
                    (routing/continue!))
                  (fn []
                    (testing "continue: the changes are let go and the move is made"
                      (is (= "/orders" (address)))
                      (is (= :orders (:name (route))))
                      (is (false? (:warns? @browser))))
                    (routing/unsaved! true)
                    (routing/navigate! :home))
                  (fn []
                    (is (= "/orders" (address)) "navigate! waits as well")
                    (routing/stay!)
                    (routing/replace-query! {:tab "history"}))
                  (fn []
                    (testing "the same screen noting where it is isn't a move away: nothing waits"
                      (is (= "/orders?tab=history" (address)))
                      (is (nil? @(rf/subscribe [::routing/held]))))
                    (routing/push-query! {:tab "open"}))
                  (fn []
                    (is (= "/orders?tab=history" (address)) "a new step for Back waits")
                    (routing/unsaved! false))
                  (fn []
                    (is (nil? @(rf/subscribe [::routing/held])) "saved: nothing waits anymore")
                    (routing/navigate! :home))
                  (fn []
                    (is (= "/" (address)) "saved: moves are free again")))))
