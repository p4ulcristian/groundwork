(ns groundwork.browser-routing-test
  "Routing in a real browser: the example app (test/example), compiled,
   served, and driven in headless Chrome over its debugging protocol. What
   the node tests can't see is checked here: the browser's own history,
   links, titles, and unsaved changes holding Back; and the styles: the
   bundles a page loads, a screen loaded on demand bringing its own before
   its code runs, and in development a saved stylesheet showing in the open
   page.

   Needs Chrome: GROUNDWORK_CHROME names it, else the usual places are tried.
   Without one the test says so and passes."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [example.hello.routes :as routes]
   [example.hello.settings :as example]
   [groundwork.build :as build]
   [groundwork.core :as gw]
   [groundwork.repl :as repl]
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
   :watch?     false
   ;; the reports screen in a module of its own, loaded on arrival
   :modules    example/modules
   :loader?    true})

(def ^:private styles (example/styles "example-routing-css"))

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

(defn- connect
  "A line to Chrome. Its answers go to the call waiting for them, its
   events to (on-event message)."
  ([address] (connect address (fn [_])))
  ([address on-event]
   (let [pending  (atom {})
         buffer   (StringBuilder.)
         listener (reify WebSocket$Listener
                    (onText [_ ws data last?]
                      (.append buffer data)
                      (when last?
                        (let [message (json/read-value (str buffer) json/keyword-keys-object-mapper)]
                          (.setLength buffer 0)
                          (if-let [answer (get @pending (:id message))]
                            (deliver answer message)
                            (when (:method message) (on-event message)))))
                      (.request ws 1)
                      nil))]
     {:ws      (.get (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient)) (URI. address) listener)
                     10 TimeUnit/SECONDS)
      :pending pending
      :next-id (atom 0)})))

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
  "A new tab at a URL: a function that evaluates JavaScript in it, its
   session in its metadata."
  [chrome url]
  (let [target  (:targetId (send! chrome "Target.createTarget" {:url url}))
        session (:sessionId (send! chrome "Target.attachToTarget" {:targetId target :flatten true}))]
    (with-meta
      (fn [expression]
        (get-in (send! chrome "Runtime.evaluate" {:expression expression :returnByValue true :awaitPromise true} session)
                [:result :value]))
      {:session session})))

(defn- slow-down!
  "From now on the tab's requests for addresses like the pattern wait
   before they go out: a stylesheet that arrives late."
  [chrome js pattern]
  (send! chrome "Fetch.enable" {:patterns [{:urlPattern pattern :requestStage "Request"}]} (:session (meta js))))

(defn- on-paused
  "What a tab's paused request does: go on after a while."
  [chrome-ref ms]
  (fn [{:keys [method params sessionId]}]
    (when (= "Fetch.requestPaused" method)
      (Thread/startVirtualThread
       (fn []
         (Thread/sleep (long ms))
         ;; the tab may be gone by then
         (try (send! @chrome-ref "Fetch.continueRequest" {:requestId (:requestId params)} sessionId)
              (catch Exception _ nil)))))))

(defn- eventually
  "Whether (check) comes true within a few seconds (or tries tenths of one)."
  ([check] (eventually 60 check))
  ([tries check]
   (loop [tries tries]
     (cond
       (try (check) (catch Exception _ false)) true
       (zero? tries)                           false
       :else                                   (do (Thread/sleep 100) (recur (dec tries)))))))

;; -----------------------------------------------------------------------------

(defn- clean! []
  (doseq [dir [(:output-dir browser) (:output-dir styles)]
          f   (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(def ^:private sheets-js
  "The stylesheets the page links, by address, in order."
  "Array.from(document.querySelectorAll('link[rel=stylesheet]')).map(l => new URL(l.href).pathname).join(' ')")

(deftest routing-in-a-real-browser
  (if-let [path (chrome)]
    (let [;; a release: one file, as production serves it, under the strict policy
          _       (build/release! browser styles)
          app     (gw/start (-> app-settings
                                (assoc :environment :production)
                                (assoc-in [:web :test-sign-in] nil)
                                (assoc :browser browser :styles styles)
                                (assoc-in [:page :stylesheets] [])
                                (assoc-in [:web :hashed-files] "(?i)\\.[0-9a-f]{8,}\\.")
                                (assoc-in [:web :routes] (web/page-routes routes/routes))))
          origin  (str "http://127.0.0.1:" (gw/port app))
          profile (str (Files/createTempDirectory "groundwork-chrome" (make-array FileAttribute 0)))
          {:keys [^Process process address]} (launch path profile)]
      (try
        (is (some? address) "Chrome started")
        (when address
          (let [line     (promise)
                chrome   (connect address (on-paused line 800))
                _        (deliver line chrome)
                js       (open-page chrome (str origin "/orders?stage=curing"))
                text     #(js (str "(document.querySelector('" % "') || {}).textContent || null"))
                click!   #(js (str "document.querySelector('" % "').click()"))
                location #(js "location.pathname + location.search")
                at?      (fn [address screen] (eventually #(and (= address (location)) (= screen (text "#screen")))))]
            (testing "the page loads its module's bundle, fingerprinted, and not the reports module's"
              (is (eventually #(= "flex" (js "(n => n && getComputedStyle(n).display)(document.querySelector('nav'))")))
                  "a class in the code is the class the stylesheet styles")
              (is (re-matches #"/example-routing-css/main\.[0-9A-F]{8}\.css" (js sheets-js)))
              (is (= "none" (js "getComputedStyle(document.querySelector('#nav-home')).textDecorationLine"))
                  "a nested rule, flattened for the example's :targets, styles the page"))
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
              ;; its bundle arrives late, its code doesn't
              (slow-down! chrome js "*/example-routing-css/reports.*.css")
              (click! "#nav-reports")
              (is (at? "/reports" "Loading"))
              (is (eventually #(= "Reports" (text "#screen"))))
              (testing "and brings its module's bundle with it, in before its code ran"
                (is (re-matches #"/example-routing-css/main\.[0-9A-F]{8}\.css /example-routing-css/reports\.[0-9A-F]{8}\.css"
                                (js sheets-js)))
                (is (= "in" (js "document.querySelector('#screen').dataset.bundleAtLoad"))
                    "the module's code saw its bundle the moment it ran")
                (is (= "rgb(1, 2, 3)" (js "getComputedStyle(document.querySelector('#screen')).color")))
                (is (= "20px" (js "getComputedStyle(document.querySelector('#screen')).paddingTop"))
                    "it sets the shared look's variable")))
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

(deftest styles-while-developing
  (if-let [path (chrome)]
    (let [views    (io/file "test/example/hello/views.css")
          stray    (io/file "test/example/hello/stray.css")
          before   (slurp views)
          browser  (assoc browser :build :example-styles-dev :watch? true
                          :output-dir "test/public/example-styles-dev" :asset-path "/example-styles-dev")
          styles   (example/styles "example-styles-dev-css")
          dir      (str (Files/createTempDirectory "groundwork-dev" (make-array FileAttribute 0)))
          ;; the development start: the app, the build's watcher, the stylesheets' watcher
          dev      (repl/start! (-> app-settings
                                    (assoc :environment :development)
                                    (assoc-in [:server :port] 0)
                                    (assoc :browser browser :styles styles)
                                    (assoc-in [:page :stylesheets] [])
                                    (assoc-in [:web :routes] (web/page-routes routes/routes)))
                                {:dir dir :mcp-json? false})
          origin   (str "http://127.0.0.1:" (gw/port (:app dev)))
          profile  (str (Files/createTempDirectory "groundwork-chrome" (make-array FileAttribute 0)))
          {:keys [^Process process address]} (launch path profile)]
      (try
        (is (some? address) "Chrome started")
        (when address
          (let [js      (open-page (connect address) (str origin "/"))
                padding #(js "getComputedStyle(document.querySelector('#screen')).paddingTop")
                log     #(slurp (io/file dir ".groundwork" "dev.log"))]
            (testing "the bundles under their plain names"
              (is (eventually 300 #(= "8px" (padding))))
              (is (= "/example-styles-dev-css/main.css" (js sheets-js))))
            (testing "a saved stylesheet shows in the open page, which is not loaded again"
              (js "window.stillHere = true")
              (spit views (str/replace before "8px" "31px"))
              (is (eventually 300 #(= "31px" (padding))))
              (is (true? (js "window.stillHere"))))
            (testing "a screen loaded on demand brings its module's bundle first"
              (js "document.querySelector('#nav-reports').click()")
              (is (eventually #(= "Reports" (js "(document.querySelector('#screen') || {}).textContent"))))
              (is (= "in" (js "document.querySelector('#screen').dataset.bundleAtLoad")))
              (is (= "rgb(1, 2, 3)" (js "getComputedStyle(document.querySelector('#screen')).color")))
              (is (= "20px" (padding)))
              (is (= "/example-styles-dev-css/main.css /example-styles-dev-css/reports.css" (js sheets-js))))
            (testing "a stylesheet no namespace names stops the build, which says so"
              (spit stray ".stray { color: red; }\n")
              (is (eventually 300 #(str/includes? (log) "test/example/hello/stray.css  is named by no namespace's {:css […]}"))))))
        (finally
          (.destroy process)
          (.waitFor process 5 TimeUnit/SECONDS)
          (spit views before)
          (.delete stray)
          (repl/stop! dev)
          (doseq [folder [profile dir (:output-dir browser) (:output-dir styles)]
                  ^File f (reverse (file-seq (io/file folder)))]
            (.delete f)))))
    (println "groundwork.browser-routing-test: no Chrome found (GROUNDWORK_CHROME), skipped")))
