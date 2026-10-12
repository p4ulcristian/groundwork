(ns groundwork.build-test
  "The browser build, owned by Groundwork: a tiny example app (test/example)
   compiled for release, started, and its page loading the fingerprinted
   file the build made."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.build :as build]
   [groundwork.core :as gw]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def browser
  {:build      :example-hello
   :entry      'example.hello.browser/start
   :output-dir "test/public/example-hello"
   :asset-path "/example-hello"
   :watch?     false})

(defn- clean! []
  (doseq [f (reverse (file-seq (io/file (:output-dir browser))))]
    (io/delete-file f true)))

(use-fixtures :once (fn [t] (clean!) (try (t) (finally (clean!)))))

(defn- get-text [app path]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port app) path)))
                            (.header "Accept" "text/html")
                            (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)
     :cache  (first (.allValues (.headers response) "cache-control"))}))

(deftest the-config-comes-from-the-settings
  (is (= {:build-id   :example-hello
          :target     :browser
          :output-dir "test/public/example-hello"
          :asset-path "/example-hello"
          :modules    {:main {:init-fn 'example.hello.browser/start}}
          :release    {:module-hash-names true}}
         (build/config browser)))
  (is (= "/nothing-built/main.js" (build/script (assoc browser :output-dir "test/public/nothing-built"
                                                       :asset-path "/nothing-built")))
      "before any build"))

(deftest a-release-is-what-the-page-loads
  (build/release! browser)
  (let [manifest (edn/read-string (slurp (io/file (:output-dir browser) "manifest.edn")))
        name     (:output-name (first (filter #(= :main (:module-id %)) manifest)))]
    (testing "the release made a fingerprinted file and its manifest"
      (is (re-matches #"main\.[0-9A-F]+\.js" name))
      (is (.exists (io/file (:output-dir browser) name))))
    (let [app (gw/start (-> app-settings
                            (assoc :browser browser)
                            ;; shadow-cljs writes the fingerprint in capitals
                            (assoc-in [:web :hashed-files] "(?i)\\.[0-9a-f]{8,}\\.")
                            (assoc-in [:web :routes] [["/" {:get web/page :access :public}]])))]
      (try
        (let [{:keys [status body]} (get-text app "/")]
          (is (= 200 status))
          (is (str/includes? body (str "src=\"/example-hello/" name "\" defer")))
          (is (str/includes? body "type=\"application/json\" id=\"groundwork\">{\"person\":null")))
        (testing "the file is served, and cached a year: its name says what's in it"
          (let [{:keys [status body cache]} (get-text app (str "/example-hello/" name))]
            (is (= 200 status))
            (is (str/includes? body "Hello, ") "the app's code, compiled")
            (is (= "public, max-age=31536000, immutable" cache))))
        (finally (gw/stop app))))))
