(ns groundwork.build
  "The app's browser build, owned by Groundwork: from the :browser settings
   it makes the shadow-cljs build itself, so an app keeps no build config of
   its own and the page shell always loads the files the build made.

     :browser {:build      :app                 ; the build's id
               :entry      app.browser/start    ; the app's browser entry
               :output-dir \"resources/public/js\"
               :asset-path \"/js\"              ; where the browser fetches them
               :watch?     true}                ; development: watch while running

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

(defn config
  "The shadow-cljs build for the :browser settings."
  [{:keys [build entry output-dir asset-path]}]
  {:build-id   build
   :target     :browser
   :output-dir output-dir
   :asset-path asset-path
   :modules    {:main {:init-fn entry}}
   :release    {:module-hash-names true}})

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

(defn script
  "Where the browser fetches the main module: its fingerprinted name after a
   release, main.js otherwise."
  [{:keys [asset-path] :as browser}]
  (let [main (some #(when (= :main (:module-id %)) %) (manifest browser))]
    (str asset-path "/" (or (:output-name main) "main.js"))))
