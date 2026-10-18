(ns groundwork.web.route-records-test
  "Route records the server and the browser share: the server's pages come
   from the same records the browser routes with."
  (:require
   [clojure.test :refer [deftest is testing]]
   [example.hello.routes :as routes]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- get-page [app path]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port app) path)))
                            (.header "Accept" "text/html") (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(deftest a-record-becomes-the-servers-page
  (let [view (fn [] nil)]
    (is (= [["/orders" {:name :orders :access :signed-in :get web/page :page {:title "Orders" :module :orders}}]
            ["/orders/:order-id" {:name :order :access :signed-in :get web/page}]]
           (web/page-routes [["/orders" {:name :orders :access :signed-in :title "Orders" :page {:module :orders}
                                         :view view :reads [{:endpoint :orders/list :params {}}]}]
                             ["/orders/:order-id" {:name :order :access :signed-in :title (fn [_] "Order")
                                                   :load (fn [] nil) :placeholder view}]]))
        "what only the browser reads is left out; a title that is text is the page's too"))
  (testing "records in a group keep their group"
    (is (= [["/shop" {:access :signed-in :get web/page}
             ["/jobs" {:name :jobs :get web/page :page {:title "Jobs"}}]]]
           (web/page-routes [["/shop" {:access :signed-in}
                              ["/jobs" {:name :jobs :title "Jobs"}]]])))))

(deftest the-example-apps-pages-are-served-from-its-records
  (let [app (gw/start (-> app-settings
                          (assoc-in [:web :languages] {:supported ["en" "de"] :default "en" :cookie "lang"})
                          (assoc-in [:web :routes] (web/page-routes routes/routes))))]
    (try
      (let [{:keys [status body]} (get-page app "/orders")]
        (is (= 200 status))
        (is (re-find #"<title>Orders</title>" body) "the record's title, on the first load too"))
      (is (re-find #"<title>Test</title>" (:body (get-page app "/orders/5")))
          "a title only the browser computes: the app's own until then")
      (testing "the page says which languages the app speaks, for the browser's routing"
        (is (re-find #"\"language\":\"de\"" (:body (get-page app "/de/orders"))))
        (is (re-find #"\"languages\":\{\"supported\":\[\"en\",\"de\"\],\"default\":\"en\"\}"
                     (:body (get-page app "/de/orders")))))
      (finally (gw/stop app)))))
