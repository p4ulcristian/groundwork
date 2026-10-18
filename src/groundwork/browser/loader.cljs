(ns groundwork.browser.loader
  "Code loaded on demand (a build with :loader? true).

   It arrives as a <script> tag, never as text that is evaluated: the page's
   policy allows scripts from the app's own address and no eval, so the
   module loader's default, fetch and evaluate, would be refused without a
   word.

   And it brings its stylesheets first: with :styles (groundwork.build.styles)
   the page shell tells the browser each module's bundle, and before the
   loader puts a module's code in the page, the bundles of it and of the
   modules loading with it are linked and loaded. The module's code runs
   only once they are in, so nothing shows unstyled. A bundle that fails to
   load doesn't hold the code back: a screen without its styles beats no
   screen."
  (:require
   [shadow.loader :as loader]))

(defonce ^:private bundles (atom {}))

(defn start!
  "What the page shell said: module → where its bundle is fetched."
  [module-bundles]
  (reset! bundles (or module-bundles {})))

(defn- linked?
  "Whether the page links a stylesheet already, whatever was added to its
   address since (the development build swaps a saved one in under ?r=…)."
  [href]
  (let [path (.-pathname (js/URL. href js/document.baseURI))]
    (boolean (some #(= path (.-pathname (js/URL. (.-href %))))
                   (array-seq (js/document.querySelectorAll "link[rel=\"stylesheet\"]"))))))

(defn- link!
  "A stylesheet in the page: a promise, settled once it has loaded (or
   failed to)."
  [href]
  (if (or (not (exists? js/document)) (linked? href))
    (js/Promise.resolve nil)
    (js/Promise.
     (fn [resolve _]
       (let [link (js/document.createElement "link")]
         (set! (.-rel link) "stylesheet")
         (set! (.-href link) href)
         (set! (.-onload link) #(resolve nil))
         (set! (.-onerror link) #(resolve nil))
         (.appendChild js/document.head link))))))

(defn bundles-in!
  "The bundles of these modules in the page: a promise. Nothing to wait
   for when a module has none, or the page links it already."
  ([modules] (bundles-in! modules link!))
  ([modules link]
   (js/Promise.all (to-array (for [module modules
                                   :let [href (get @bundles (name module))]
                                   :when href]
                               (link href))))))

(defn styled-first
  "A module loader's loadModules that loads the modules' bundles before
   it calls load-modules, the loader's own, on the loader."
  ([module-loader load-modules] (styled-first module-loader load-modules link!))
  ([module-loader load-modules link]
   (fn [ids module-infos options]
     (-> (bundles-in! (array-seq ids) link)
         (.then (fn [_] (.call load-modules module-loader ids module-infos options)))))))

(defonce ^{:doc "The page's module loader, set up once: script tags, bundles first."} module-loader
  (let [module-loader ^js (loader/getModuleLoader)]
    (.setUseScriptTags module-loader true)
    (set! (.-loadModules module-loader) (styled-first module-loader (.-loadModules module-loader)))
    module-loader))
