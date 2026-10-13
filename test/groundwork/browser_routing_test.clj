(ns groundwork.browser-routing-test
  "Routing in a real browser: the example app (test/example), compiled,
   served, and driven in headless Chrome over its debugging protocol. What
   the node tests can't see is checked here: the browser's own history,
   links, titles, and unsaved changes holding Back.

   Needs Chrome: GROUNDWORK_CHROME names it, else the usual places are tried.
   Without one the test says so and passes."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [example.hello.routes :as routes]
   [groundwork.build :as build]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web]
   [jsonista.core :as json])
  (:import
   (java.io File)
   (java.net URI)
   (java.net.http HttpClient WebSocket WebSocket$Listener)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.concurrent TimeUnit)))

(def ^:private browser
  {:build      :example-routing
   :entry      'example.hello.browser/start
   :output-dir "test/public/example-routing"
   :asset-path "/example-routing"
   :watch?     false})

(defn- chrome
  "The Chrome to drive, or nil."
  []
  (some (fn [path] (when (and path (.canExecute (io/file path))) path))
        [(System/getenv "GROUNDWORK_CHROME")
         "/usr/bin/google-chrome-stable" "/usr/bin/google-chrome" "/opt/google/chrome/chrome"
         "/usr/bin/chromium" "/usr/bin/chromium-browser"]))

;; -----------------------------------------------------------------------------
;; Chrome's debugging protocol, as little of it as the test needs
;; -----------------------------------------------------------------------------

(defn- launch
  "Start headless Chrome; returns the process and its debugging address."
  [path profile]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List
                               [path "--headless=new" "--remote-debugging-port=0" "--no-first-run"
                                "--no-sandbox" "--disable-gpu" (str "--user-data-dir=" profile)
                                "about:blank"])
                          (.redirectErrorStream true)))
        address (promise)]
    (Thread/startVirtualThread
     (fn []
       (with-open [lines (io/reader (.getInputStream process))]
         (doseq [line (line-seq lines)]
           (when-let [[_ url] (re-find #"DevTools listening on (ws://\S+)" line)]
             (deliver address url))))))
    {:process process :address (deref address 20000 nil)}))

(defn- connect [address]
  (let [pending  (atom {})
        buffer   (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data last?]
                     (.append buffer data)
                     (when last?
                       (let [message (json/read-value (str buffer) json/keyword-keys-object-mapper)]
                         (.setLength buffer 0)
                         (when-let [answer (get @pending (:id message))]
                           (deliver answer message))))
                     (.request ws 1)
                     nil))]
    {:ws      (.get (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient)) (URI. address) listener)
                    10 TimeUnit/SECONDS)
     :pending pending
     :next-id (atom 0)}))

(defn- send!
  ([chrome method params] (send! chrome method params nil))
  ([{:keys [^WebSocket ws pending next-id]} method params session]
   (let [id     (swap! next-id inc)
         answer (promise)]
     (swap! pending assoc id answer)
     (.get (.sendText ws (json/write-value-as-string (cond-> {:id id :method method :params params}
                                                       session (assoc :sessionId session)))
                      true)
           5 TimeUnit/SECONDS)
     (let [message (deref answer 15000 nil)]
       (swap! pending dissoc id)
       (when (or (nil? message) (:error message))
         (throw (ex-info (str "Chrome didn't answer " method) {:answer message})))
       (:result message)))))

(defn- open-page
  "A new tab at a URL: a function that evaluates JavaScript in it."
  [chrome url]
  (let [target  (:targetId (send! chrome "Target.createTarget" {:url url}))
        session (:sessionId (send! chrome "Target.attachToTarget" {:targetId target :flatten true}))]
    (fn [expression]
      (get-in (send! chrome "Runtime.evaluate" {:expression expression :returnByValue true :awaitPromise true} session)
              [:result :value]))))

(defn- eventually
  "Whether (check) comes true within a few seconds."
  [check]
  (loop [tries 60]
    (cond
      (try (check) (catch Exception _ false)) true
      (zero? tries)                           false
      :else                                   (do (Thread/sleep 100) (recur (dec tries))))))

;; -----------------------------------------------------------------------------

(defn- clean! []
  (doseq [f (reverse (file-seq (io/file (:output-dir browser))))]
    (io/delete-file f true)))

(deftest routing-in-a-real-browser
  (if-let [path (chrome)]
    (let [;; a release: one file, as production serves it, under the strict policy
          _       (build/release! browser)
          app     (gw/start (-> app-settings
                                (assoc :browser browser)
                                (assoc-in [:web :hashed-files] "(?i)\\.[0-9a-f]{8,}\\.")
                                (assoc-in [:web :routes] (web/page-routes routes/routes))))
          origin  (str "http://127.0.0.1:" (gw/port app))
          profile (str (Files/createTempDirectory "groundwork-chrome" (make-array FileAttribute 0)))
          {:keys [^Process process address]} (launch path profile)]
      (try
        (is (some? address) "Chrome started")
        (when address
          (let [js       (open-page (connect address) (str origin "/orders?stage=curing"))
                text     #(js (str "(document.querySelector('" % "') || {}).textContent || null"))
                click!   #(js (str "document.querySelector('" % "').click()"))
                location #(js "location.pathname + location.search")
                at?      (fn [address screen] (eventually #(and (= address (location)) (= screen (text "#screen")))))]
            (testing "the page's own address is the first route, with its query and title"
              (is (eventually #(= "stage: curing" (text "#query"))))
              (is (= "Orders" (js "document.title")) "the server's title and the route's agree"))
            (testing "a link moves in-app: no page load, the route's own title"
              (js "window.loadedOnce = true")
              (click! "#to-order-5")
              (is (at? "/orders/5" "Order 5"))
              (is (= "Order 5" (js "document.title")))
              (is (true? (js "window.loadedOnce")) "the page wasn't loaded again"))
            (testing "Back returns, with the query it had"
              (js "history.back()")
              (is (eventually #(= "/orders?stage=curing" (location))))
              (is (eventually #(= "stage: curing" (text "#query")))))
            (testing "a filter in the address is a step Back undoes"
              (click! "#filter")
              (is (eventually #(= "/orders?stage=done" (location))))
              (is (eventually #(= "stage: done" (text "#query"))))
              (js "history.back()")
              (is (eventually #(= "stage: curing" (text "#query")))))
            (testing "a screen whose code loads on demand shows its placeholder first"
              (click! "#nav-reports")
              (is (at? "/reports" "Loading"))
              (is (eventually #(= "Reports" (text "#screen")))))
            (testing "unsaved changes hold a link"
              (click! "#nav-form")
              (is (eventually #(= "/form" (location))))
              (is (eventually #(some? (text "#dirty"))))
              (click! "#dirty")
              (click! "#nav-orders")
              (is (eventually #(str/starts-with? (or (text "#held") "") "Leave for orders?")))
              (is (= "/form" (location)) "the address didn't move")
              (click! "#stay")
              (is (eventually #(nil? (text "#held")))))
            (testing "unsaved changes hold Back: the address is put back"
              (js "history.back()")
              (is (eventually #(str/starts-with? (or (text "#held") "") "Leave for reports?")))
              (is (eventually #(= "/form" (location))))
              (testing "discard: the move is made after all"
                (click! "#discard")
                (is (at? "/reports" "Reports"))))
            (testing "saved: moves are free again"
              (click! "#nav-form")
              (is (eventually #(some? (text "#dirty"))))
              (click! "#dirty")
              (click! "#save")
              (click! "#nav-home")
              (is (at? "/" "Hello, visitor.")))
            (testing "an address no route has shows the app's unknown screen"
              (js "location.assign('/nowhere')")
              (is (at? "/nowhere" "No such page")))))
        (finally
          (.destroy process)
          (.waitFor process 5 TimeUnit/SECONDS)
          (doseq [^File f (reverse (file-seq (io/file profile)))] (.delete f))
          (gw/stop app)
          (clean!))))
    (println "groundwork.browser-routing-test: no Chrome found (GROUNDWORK_CHROME), skipped")))
