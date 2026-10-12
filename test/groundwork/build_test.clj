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

(def ^:private several
  (assoc browser
         :output-dir "test/public/nothing-built" :asset-path "/nothing-built"
         :modules {:base   {:init-fn 'example.hello.browser/start}
                   :shared {:entries ['example.hello.shared] :depends-on #{:base}}
                   :admin  {:init-fn 'example.hello.admin/start :depends-on #{:base :shared}}
                   :maps   {:entries ['example.hello.maps] :depends-on #{:base}}}
         :loader? true
         :options {:js-options {:js-provider :shadow}
                   :release    {:compiler-options {:output-feature-set :es2020}}}))

(deftest a-build-with-several-modules
  (testing "the app's modules, the loader, and its own options merged over Groundwork's"
    (let [config (build/config several)]
      (is (= (:modules several) (:modules config)))
      (is (true? (:module-loader config)))
      (is (= {:js-provider :shadow} (:js-options config)))
      (is (= {:module-hash-names true :compiler-options {:output-feature-set :es2020}} (:release config)))))
  (testing "a module loads after everything it depends on, each once"
    (is (= ["/nothing-built/base.js"] (build/scripts several :base)))
    (is (= ["/nothing-built/base.js" "/nothing-built/shared.js" "/nothing-built/admin.js"]
           (build/scripts several :admin)))
    (is (= ["/nothing-built/base.js" "/nothing-built/maps.js"] (build/scripts several :maps))))
  (testing "a page that names no module loads the ones that depend on no other"
    (is (= [:base] (build/roots several)))
    (is (= [:main] (build/roots browser))))
  (testing "a module the build doesn't have is refused"
    (is (= ::build/unknown-module
           (:type (ex-data (try (build/scripts several :nope) (catch Exception e e)))))))
  (testing "after a release the names are the manifest's"
    (let [dir (io/file "test/public/several-built")]
      (.mkdirs dir)
      (spit (io/file dir "manifest.edn")
            (pr-str [{:module-id :base :output-name "base.AAAA1111.js"}
                     {:module-id :shared :output-name "shared.BBBB2222.js"}
                     {:module-id :admin :output-name "admin.CCCC3333.js"}]))
      (try
        (is (= ["/several/base.AAAA1111.js" "/several/shared.BBBB2222.js" "/several/admin.CCCC3333.js"]
               (build/scripts (assoc several :output-dir (str dir) :asset-path "/several") :admin)))
        (finally
          (io/delete-file (io/file dir "manifest.edn"))
          (io/delete-file dir))))))

(deftest a-page-loads-its-module-after-what-it-depends-on
  (let [app (gw/start (-> app-settings
                          (assoc :browser several)
                          (assoc-in [:web :routes]
                                    [["/" {:get web/page :access :public}]
                                     ["/admin" {:get web/page :access :public :page {:module :admin}}]])))
        scripts (fn [path]
                  (mapv second (re-seq #"<script[^>]* src=\"([^\"]+)\"" (:body (get-text app path)))))]
    (try
      (is (= ["/nothing-built/base.js"] (scripts "/")) "no module named: the root module")
      (is (= ["/nothing-built/base.js" "/nothing-built/shared.js" "/nothing-built/admin.js"]
             (scripts "/admin")))
      (finally (gw/stop app)))))

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
