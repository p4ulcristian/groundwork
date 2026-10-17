(ns groundwork.styles-test
  "A namespace names its stylesheets: what a namespace's file names, the
   check that every stylesheet is named and every named file is there, the
   bundles of each module, and a release's minified, fingerprinted bundles.
   The whole build, with the example app, is in groundwork.build-test and in
   a real browser in groundwork.browser-routing-test."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [example.hello.settings :as example]
   [groundwork.build :as build]
   [groundwork.styles :as styles])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)))

(defn- temp-dir []
  (.getCanonicalFile (.toFile (Files/createTempDirectory "groundwork-styles" (make-array FileAttribute 0)))))

(defn- write [dir path text]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f text)
    (.getCanonicalFile f)))

(defn- delete [dir]
  (doseq [^File f (reverse (file-seq (io/file dir)))] (.delete f)))

(defn- app-tree
  "A small app's sources, as they should be: every stylesheet named."
  [dir]
  {:list   (write dir "app/orders/list.cljs"
                  "(ns app.orders.list\n  {:css [\"orders.css\" \"../shared/card.css\"]}\n  (:require [app.orders.row]))\n")
   :row    (write dir "app/orders/row.cljc"
                  "(ns ^{:css [\"row.css\"]} app.orders.row\n  #?(:cljs (:require [reagent.core :as r])))\n")
   :about  (write dir "app/about.cljs"
                  "(ns app.about\n  \"The about page.\"\n  {:css [\"about.css\"] :author \"someone\"})\n")
   :plain  (write dir "app/plain.cljs" "(ns app.plain\n  (:require [clojure.string :as str]))\n")
   :server (write dir "app/server.clj" "(ns app.server {:css [\"nowhere.css\"]})\n")
   :sheets {:orders (write dir "app/orders/orders.css" ".orders { color: red; }\n")
            :card   (write dir "app/shared/card.css" ".card { padding: 4px; }\n")
            :row    (write dir "app/orders/row.css" ".row { margin: 0; }\n")
            :about  (write dir "app/about.css" ".about { color: blue; }\n")}})

(deftest a-namespace-names-its-stylesheets
  (let [dir  (temp-dir)
        tree (app-tree dir)]
    (try
      (testing "in the map after its name, paths next to its file"
        (let [found (styles/named (:list tree))]
          (is (= 'app.orders.list (:ns found)))
          (is (= [(get-in tree [:sheets :orders]) (get-in tree [:sheets :card])] (:css found)))))
      (testing "as metadata on its name, in a .cljc file read for the browser"
        (is (= [(get-in tree [:sheets :row])] (:css (styles/named (:row tree))))))
      (testing "after a docstring, next to other metadata"
        (is (= [(get-in tree [:sheets :about])] (:css (styles/named (:about tree))))))
      (testing "a namespace that names none"
        (is (nil? (:css (styles/named (:plain tree))))))
      (testing "read from a file: address, as the build has its sources"
        (is (= 'app.orders.list (:ns (styles/named (.toURL (.toURI ^File (:list tree))))))))
      (finally (delete dir)))))

(deftest every-stylesheet-is-named-and-every-named-file-is-there
  (let [dir    (temp-dir)
        _      (app-tree dir)
        styles {:sources [(str dir)] :output-dir (str dir "/public/css") :asset-path "/css"}]
    (try
      (testing "a tree where they agree, the server's own code and the bundles' folder aside"
        (write dir "public/css/main.css" ".bundled { color: red; }\n")
        (is (= [] (styles/problems styles)))
        (is (nil? (styles/check! styles))))
      (testing "a stylesheet no namespace names, a named one that isn't there, :css that isn't paths"
        (write dir "app/orders/old.css" ".old {}\n")
        (write dir "app/orders/list.cljs" "(ns app.orders.list\n  {:css [\"orders.css\" \"../shared/card.css\" \"gone.css\"]})\n")
        (write dir "app/odd.cljs" "(ns app.odd {:css \"odd.css\"})\n")
        (let [found (styles/problems styles)
              at    #(str dir "/" %)]
          (is (= [{:file (at "app/odd.cljs") :message ":css must be a list of paths, like [\"orders.css\"], got \"odd.css\""}
                  {:file (at "app/orders/list.cljs") :message (str "names " (at "app/orders/gone.css") ", which isn't there")}
                  {:file (at "app/orders/old.css") :message "is named by no namespace's {:css […]}"}]
                 found))
          (testing "all at once, as the build stops with them"
            (let [e (try (styles/check! styles) nil (catch clojure.lang.ExceptionInfo e e))]
              (is (= ::styles/problems (:type (ex-data e))))
              (is (str/starts-with? (ex-message e) "The stylesheets have 3 problems:\n"))
              (is (str/includes? (ex-message e) "app/orders/old.css  is named by no namespace's {:css […]}"))))))
      (finally (delete dir)))))

(deftest each-module-gets-one-bundle
  (let [dir (temp-dir)
        [base nav orders shared maps] (for [p ["base.css" "nav.css" "orders.css" "shared.css" "maps.css"]]
                                        (write dir p (str "/* " p " */\n")))
        ns* (fn [& sheets] {:css (vec sheets)})]
    (try
      (let [plan (styles/plan [{:module-id :main :depends-on #{}
                                :namespaces [(ns* nav shared) (ns*) (ns* nav) (ns* base)]}
                               {:module-id :orders :depends-on #{:main}
                                :namespaces [(ns* shared orders) (ns* orders)]}
                               {:module-id :admin :depends-on #{:orders}
                                :namespaces [(ns* nav base)]}
                               {:module-id :maps :depends-on #{:main}
                                :namespaces [(ns* orders maps)]}])]
        (testing "the stylesheets in the order the namespaces load and name them, each once"
          (is (= [nav shared base] (:main plan))))
        (testing "without what a module it depends on carries, through the ones between"
          (is (= [orders] (:orders plan)))
          (is (not (contains? plan :admin)) "a module with nothing of its own has no bundle"))
        (testing "a module beside another carries its own copy"
          (is (= [orders maps] (:maps plan))))
        (testing "development: plain names, the files as written, and the manifest"
          (let [out      (io/file dir "out")
                manifest (styles/write-bundles! {:output-dir (str out)} plan false)]
            (is (= {:modules {:main "main.css" :orders "orders.css" :maps "maps.css"}} manifest))
            (is (= manifest (edn/read-string (slurp (io/file out "manifest.edn")))))
            (is (re-matches #"(?s)/\* .*nav\.css \*/\n/\* nav\.css \*/\n\n/\* .*shared\.css \*/\n.*base\.css \*/\n"
                            (slurp (io/file out "main.css"))))
            (testing "a bundle that didn't change isn't written again"
              (.setLastModified (io/file out "main.css") 1000)
              (styles/write-bundles! {:output-dir (str out)} plan false)
              (is (= 1000 (.lastModified (io/file out "main.css"))))))))
      (finally (delete dir)))))

(defn- sha8 [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (str/upper-case (subs (apply str (map #(format "%02x" %) digest)) 0 8))))

(deftest a-release-minifies-and-fingerprints
  (let [dir  (temp-dir)
        a    (write dir "a.css" "/* the cards */\n.card {\n  color: #ff0000;\n  padding: 4px 4px 4px 4px;\n}\n")
        b    (write dir "b.css" ".card:hover { color: rgb(0, 0, 255); }\n.list { & .row { margin: 0px; } }\n")
        out  (io/file dir "out")]
    (try
      (let [manifest (styles/write-bundles! {:output-dir (str out)} {:main [a b] :maps [b]} true)
            main     (get-in manifest [:modules :main])
            text     (slurp (io/file out main))]
        (testing "minified by lightningcss, nesting kept as written"
          (is (= ".card{color:red;padding:4px}.card:hover{color:#00f}.list{& .row{margin:0}}" (str/trim text))))
        (testing "named by what's in it, and the manifest says so"
          (is (re-matches #"main\.[0-9A-F]{8}\.css" main))
          (is (= (str "main." (sha8 text) ".css") main))
          (is (= manifest (edn/read-string (slurp (io/file out "manifest.edn")))))
          (is (.exists (io/file out (get-in manifest [:modules :maps]))))))
      (finally (delete dir)))))

(deftest a-stylesheet-lightningcss-cant-read-stops-the-release
  (let [dir (temp-dir)
        ok  (write dir "app/ok.css" ".ok { color: red; }\n")
        bad (write dir "app/orders/orders.css" ".orders { color: red; }\n.orders:not( { color: blue; }\n")
        out (io/file dir "out")
        e   (try (styles/write-bundles! {:output-dir (str out)} {:main [ok bad]} true) nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (try
      (is (= ::styles/unreadable (:type (ex-data e))))
      (is (re-find #"^lightningcss can't read \S*app/orders/orders\.css:\d+(:\d+)?: " (ex-message e))
          "the file it can't read, by name, not the bundle")
      (is (not (.exists (io/file out "manifest.edn"))) "nothing written")
      (finally (delete dir)))))

;; -----------------------------------------------------------------------------
;; The build runs them
;; -----------------------------------------------------------------------------

(def ^:private browser
  {:build      :example-styles
   :entry      'example.hello.browser/start
   :output-dir "test/public/example-styles"
   :asset-path "/example-styles"
   :watch?     false
   :modules    example/modules
   :loader?    true})

(deftest the-build-adds-its-hook
  (let [styles (example/styles "example-styles-css")
        config (build/config (assoc browser :options {:build-hooks '[(app.build/hook)]}) styles)]
    (is (= [(list 'groundwork.styles/hook styles) '(app.build/hook)] (:build-hooks config))
        "Groundwork's first, then the app's own")
    (is (= "test/public" (get-in config [:devtools :watch-dir])) "where development swaps a changed bundle in")
    (is (nil? (:build-hooks (build/config browser))) "no :styles, no hook")))

(deftest a-build-stops-on-a-stylesheet-no-namespace-names
  (let [dir    (temp-dir)
        _      (app-tree dir)
        _      (write dir "app/orders/old.css" ".old {}\n")
        styles {:sources [(str dir)] :output-dir "test/public/example-styles-stopped" :asset-path "/example-styles-stopped"}
        e      (try (build/compile! (assoc browser :build :example-styles-stopped) styles) nil
                    (catch Exception e e))]
    (try
      (is (some? e) "the build failed")
      (is (some #(str/includes? (str (ex-message %)) "app/orders/old.css  is named by no namespace's {:css […]}")
                (take-while some? (iterate ex-cause e)))
          "with the list")
      (is (not (.exists (io/file "test/public/example-styles-stopped" "manifest.edn"))) "and no bundles")
      (finally
        (delete dir)
        (delete "test/public/example-styles-stopped")
        (delete "test/public/example-styles")))))
