(ns groundwork.server.browser-build.api
  "The browser's code, built by shadow-cljs from a build Groundwork writes
   as data; the app gives only its entry (the function the page runs).
   The api of groundwork.server.browser-build.

     ;; development: compiles, then again on every save, and swaps the new
     ;; code into open tabs (groundwork.server.main starts it with
     ;; :browser-build :watch)
     (def watching (browser-build/start-watching! {:entry 'app.browser.main/start! :files-folder \"target/public\"}))
     (browser-build/stop-watching! watching)

     ;; the deploy, once, before the app starts:
     ;;   clojure -X:browser-release :entry app.browser.main/start! :files-folder '\"target/public\"'
     ;; writes target/public/js/main.<hash>.js, css/main.<hash>.css and a
     ;; manifest for each, which groundwork.server.main's page shell reads

   shadow-cljs is needed only to build: production starts without it, so
   groundwork.server.main loads this library only when it watches."
  (:require
   [groundwork.server.browser-build.styles :as styles]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defn make-build
  "The shadow-cljs build: one module, main, starting at the app's entry;
   its files in <files-folder>/js, served at /js. After every compile the
   stylesheets the namespaces named become <files-folder>/css's bundle;
   while watching, shadow-cljs swaps a changed bundle into open pages."
  [{:keys [entry files-folder]}]
  {:build-id    :groundwork-browser
   :target      :browser
   :output-dir  (str files-folder "/js")
   :asset-path  "/js"
   :modules     {:main {:init-fn (symbol entry)}}
   :build-hooks [(list `styles/bundle-styles {:files-folder files-folder})]
   :devtools    {:watch-dir files-folder}})

(defn start-watching!
  "Starts shadow-cljs in this JVM and watches the build: compiled now,
   again on every save, the new code swapped into open tabs. Returns once
   the first compile is done."
  [build]
  (shadow-server/start!)
  (shadow/watch* (make-build build) {})
  (assoc build :stop-watching-stylesheets (styles/start-watching-stylesheets!)))

(defn stop-watching!
  "Stops watching, and shadow-cljs."
  [{:keys [stop-watching-stylesheets]}]
  (stop-watching-stylesheets)
  (shadow/stop-worker :groundwork-browser)
  (shadow-server/stop!))

(defn build-for-release!
  "Compiles the browser's code once, optimized, each file's name with a
   hash of its content (cached for ever, never stale), and the manifests
   listing them (js/manifest.edn, css/manifest.edn). A stylesheet beside
   the namespaces that none names stops it. For the deploy: clojure -X:browser-release :entry … :files-folder …"
  [build]
  (shadow/with-runtime
    (shadow/release* (assoc (make-build build) :module-hash-names true) {}))
  :built)
