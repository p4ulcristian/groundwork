(ns groundwork.stop-on-sigterm-test
  "A started app, told to stop the way a deploy tells it (SIGTERM), is gone
   within a few seconds of its last answered request."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   (java.io BufferedReader InputStreamReader)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.util.concurrent TimeUnit)))

(def ^:private child
  "What the other JVM runs: the app on any free port, stopped by a shutdown
   hook as an app's own main does, saying its port once it is up."
  (pr-str '(do (require '[groundwork.core :as gw] '[groundwork.test-support :refer [app-settings]])
               (let [app (gw/start (-> app-settings
                                       (assoc-in [:server :port] 0)
                                       (assoc-in [:web :routes]
                                                 [["/ping" {:get (fn [_] {:status 200 :body "pong"}) :access :public}]])))]
                 (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (gw/stop app))))
                 (println "READY" (gw/port app))
                 (flush)
                 @(promise)))))

(defn- ready-port
  "The port the child said it listens on, or nil when it ended first."
  [^Process process]
  (let [reader (BufferedReader. (InputStreamReader. (.getInputStream process)))]
    (loop []
      (when-let [line (.readLine reader)]
        (if (str/starts-with? line "READY ")
          (parse-long (subs line 6))
          (recur))))))

(deftest a-started-app-is-gone-soon-after-sigterm
  (let [java    (str (System/getProperty "java.home") "/bin/java")
        process (-> (ProcessBuilder. ^java.util.List [java "-cp" (System/getProperty "java.class.path")
                                                      "clojure.main" "-e" child])
                    (.redirectErrorStream true)
                    (.start))]
    (try
      (let [port   (ready-port process)
            _      (is (some? port) "the app started")
            client (HttpClient/newHttpClient)
            base   (str "127.0.0.1:" port)
            pong   (.send client (.build (HttpRequest/newBuilder (URI. (str "http://" base "/ping"))))
                          (HttpResponse$BodyHandlers/ofString))
            asked  (System/nanoTime)]
        (is (= 200 (.statusCode pong)))
        (.destroy process)                       ; SIGTERM
        (is (.waitFor process 5 TimeUnit/SECONDS)
            "the app exits within five seconds of SIGTERM")
        (is (< (/ (- (System/nanoTime) asked) 1e9) 5.0)))
      (finally
        (.destroyForcibly process)))))
