(ns groundwork.build
  "The app's browser build, owned by Groundwork: from the :browser settings
   it makes the shadow-cljs build itself, so an app keeps no build config of
   its own and the page shell always loads the files the build made.

     :browser {:build      :app                 ; the build's id
               :entry      app.browser/start    ; the app's browser entry
               :output-dir \"resources/public/js\"
               :asset-path \"/js\"              ; where the browser fetches them
               :watch?     true}                ; development: watch while running

   An app with more than one module names them, and a page names the one it
   starts (a route's :page :module); the page shell loads that module after
   the ones it depends on. Modules no page names load on demand with
   :loader? true:

     :modules {:base  {:init-fn app.browser/start}
               :admin {:init-fn app.admin/start :depends-on #{:base}}
               :maps  {:entries [app.maps.view] :depends-on #{:base}}}
     :loader? true
     :options {…}        extra shadow-cljs build options, merged over these

   In development (groundwork.repl/start!), starting the app also starts the
   watcher. A release (release!, before packaging the app) compiles with
   fingerprinted names, like main.3F2A9C.js, and writes manifest.edn next to
   them; the page shell reads it, so a page and the build can't disagree, and
   a fingerprinted file can be cached a year.

   Groundwork's own browser libraries are in its deps.cljs; shadow-cljs
   installs them for the app. The app's package.json holds only its own.

   Needs thheller/shadow-cljs on the classpath (an app's :dev or :build
   alias), and the npm packages installed."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))

(defn modules
  "The build's modules: the app's own, or the one module :main."
  [{:keys [entry modules]}]
  (or modules {:main {:init-fn entry}}))

(defn config
  "The shadow-cljs build for the :browser settings."
  [{:keys [build output-dir asset-path loader? options] :as browser}]
  (deep-merge (cond-> {:build-id   build
                       :target     :browser
                       :output-dir output-dir
                       :asset-path asset-path
                       :modules    (modules browser)
                       :release    {:module-hash-names true}}
                loader? (assoc :module-loader true))
              (or options {})))

(defn- shadow [sym]
  (or (requiring-resolve sym)
      (throw (ex-info "The browser build needs thheller/shadow-cljs on the classpath"
                      {:type ::missing-dependency :needs sym}))))

(defn watch!
  "Start shadow-cljs and watch the app's build: every save recompiles and the
   browser reloads it."
  [browser]
  ((shadow 'shadow.cljs.devtools.server/start!))
  ((shadow 'shadow.cljs.devtools.api/watch*) (config browser) {})
  (:build browser))

(defn stop-watch!
  "Stop the watcher and shadow-cljs."
  [browser]
  ((shadow 'shadow.cljs.devtools.api/stop-worker) (:build browser))
  ((shadow 'shadow.cljs.devtools.server/stop!)))

(defn release!
  "Compile the app's browser code for production: optimized, fingerprinted,
   with the manifest the page shell reads."
  [browser]
  (shadow 'shadow.cljs.devtools.api/release*)
  ;; with-runtime is a macro: shadow's runtime for the time of the build,
  ;; unless a running shadow-cljs has one already
  (eval `(shadow.cljs.devtools.api/with-runtime
           (shadow.cljs.devtools.api/release* '~(config browser) {})))
  (:build browser))

(defn release
  "release! for clojure -X: clojure -X:build groundwork.build/release :settings app.settings/production"
  [{:keys [settings]}]
  (let [settings (if (symbol? settings) @(requiring-resolve settings) settings)]
    (release! (:browser settings))
    (shutdown-agents)))

(defn- manifest
  "The build's manifest: from :output-dir when it's there (development), else
   from the classpath (a packaged app), or nil before the first build."
  [{:keys [output-dir asset-path]}]
  (let [file (io/file output-dir "manifest.edn")]
    (some-> (if (.exists file)
              file
              (io/resource (str "public" asset-path "/manifest.edn")))
            slurp
            edn/read-string)))

(defn- chain
  "A module after everything it depends on, each once, in loading order."
  [all module]
  (when-not (contains? all module)
    (throw (ex-info (str "No module " module " in the browser build") {:type ::unknown-module :module module})))
  (loop [todo [module] seen []]
    (if-let [m (peek todo)]
      (let [missing (remove (set seen) (sort (:depends-on (all m))))]
        (cond
          (some #{m} seen) (recur (pop todo) seen)
          (seq missing)    (recur (into todo missing) seen)
          :else            (recur (pop todo) (conj seen m))))
      seen)))

(defn roots
  "The modules that depend on no other: what a page that names no module loads."
  [browser]
  (vec (sort (for [[k v] (modules browser) :when (empty? (:depends-on v))] k))))

(defn scripts
  "Where the browser fetches a module and, before it, the modules it depends
   on: their fingerprinted names after a release, <module>.js otherwise."
  [{:keys [asset-path] :as browser} module]
  (let [named (into {} (map (juxt :module-id :output-name)) (manifest browser))]
    (mapv #(str asset-path "/" (or (named %) (str (name %) ".js")))
          (chain (modules browser) module))))

(defn script
  "Where the browser fetches the main module: its fingerprinted name after a
   release, main.js otherwise."
  [browser]
  (peek (scripts browser :main)))
