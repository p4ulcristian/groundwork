(ns groundwork.styles
  "How an app's stylesheets reach the page. A namespace names the
   stylesheets it needs in its own metadata, with paths next to its file:

     (ns app.orders.list
       {:css [\"orders.css\"]}             ; src/app/orders/orders.css
       (:require …))

   Classes are plain text and stylesheets plain CSS. An app that has styles
   says where they are in its settings (without :styles it brings its own
   stylesheets through :page :stylesheets, and nothing here runs):

     :styles {:sources    [\"src\"]                  ; where the browser code and its stylesheets are
              :output-dir \"resources/public/css\"   ; where the bundles go
              :asset-path \"/css\"}                  ; where the browser fetches them

   The browser build (groundwork.build) does the rest, through a build hook
   of its own:

   - every build first checks that every .css under :sources is named by
     some namespace and every named file exists; otherwise it stops with the
     list
   - each browser module gets one bundle: the stylesheets its namespaces
     name, in the order the build loads them (a namespace's after those of
     the namespaces it requires), each file once; a file a module it depends
     on carries already is left out
   - development writes the bundles under plain names (main.css); a saved
     stylesheet makes the build run again, and the open page swaps the
     changed bundle in
   - a release minifies every bundle with lightningcss (the npm package
     lightningcss, in the app's package.json), stops on a stylesheet it
     can't parse, naming the file, and writes fingerprinted names
     (main.3F2A91C0.css)
   - either way it writes manifest.edn next to the bundles; the page shell
     reads it (links, module-links) and links the bundles of the modules a
     page starts with. A module loaded on demand brings its own bundle
     before its code runs (groundwork.loader)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.build :as build]
   [jsonista.core :as json])
  (:import
   (java.io File PushbackReader)
   (java.net URL)
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.security MessageDigest)))

;; =============================================================================
;; What a namespace names
;; =============================================================================

(defn- ns-form
  "The ns form at the top of a file (a File or a URL), read for the browser,
   or nil."
  [source]
  (try
    (with-open [reader (PushbackReader. (io/reader source))]
      (binding [*read-eval* false, *default-data-reader-fn* tagged-literal]
        (let [form (read {:read-cond :allow :features #{:cljs} :eof nil} reader)]
          (when (and (seq? form) (= 'ns (first form)))
            form))))
    (catch Exception _ nil)))

(defn- ns-meta
  "An ns form's metadata: on its name, and in the map after the name and
   the docstring."
  [[_ ns-name & more]]
  (merge (meta ns-name)
         (first (filter map? (take-while #(not (seq? %)) more)))))

(defn- canonical [^File f]
  (.getCanonicalFile f))

(defn- relative
  "A file's path from the working directory when it is under it, else whole."
  [^File f]
  (let [here (str (canonical (io/file ".")) File/separator)
        path (str (canonical f))]
    (if (str/starts-with? path here) (subs path (count here)) path)))

(defn- read-named [^File file]
  (when-let [form (ns-form file)]
    (let [css (:css (ns-meta form))]
      {:ns   (second form)
       :file (relative file)
       :css  (when (sequential? css)
               (mapv #(canonical (io/file (.getParentFile file) (str %))) css))
       :bad  (when (and (some? css) (not (and (sequential? css) (every? string? css))))
               css)})))

(defonce ^:private read-before
  ;; file → [when it changed, what it names]: every build reads every
  ;; namespace's file, and most haven't changed since the last one
  (atom {}))

(defn named
  "What one namespace's file names: {:ns app.orders.list :file \"src/…\"
   :css [File …]}, the stylesheets as files next to it; nil when the file
   has no ns form. source is a File or a file: URL."
  [source]
  (let [^File file (canonical (if (instance? URL source) (io/as-file source) source))
        changed    [(.lastModified file) (.length file)]
        [seen found] (get @read-before file)]
    (if (= seen changed)
      found
      (let [found (read-named file)]
        (swap! read-before assoc file [changed found])
        found))))

(defn- files-under [paths pred]
  (sort (for [path paths
              ^File f (file-seq (io/file path))
              :when (and (.isFile f) (pred (.getName f)))]
          (canonical f))))

(defn- under? [^File f dir]
  (str/starts-with? (str f) (str (canonical (io/file dir)) File/separator)))

(defn- missing
  "What namespaces name wrongly: a file that isn't there, :css that isn't a
   list of paths."
  [spaces]
  (concat
   (for [{:keys [file css]} spaces, ^File sheet css :when (not (.isFile sheet))]
     {:file file :message (str "names " (relative sheet) ", which isn't there")})
   (for [{:keys [file bad]} spaces :when (some? bad)]
     {:file file :message (str ":css must be a list of paths, like [\"orders.css\"], got " (pr-str bad))})))

(defn problems
  "Whether the stylesheets under :sources and what the namespaces there
   name agree: [{:file … :message …}], every one, sorted. A stylesheet no
   namespace names; a named file that isn't there; :css that isn't a list
   of paths. The bundles' own folder is left out."
  [{:keys [sources output-dir]}]
  (let [spaces  (keep named (files-under sources #(re-find #"\.clj[sc]$" %)))
        sheets  (remove #(under? % output-dir) (files-under sources #(str/ends-with? % ".css")))
        named?  (set (mapcat :css spaces))]
    (vec (sort-by (juxt :file :message)
                  (concat
                   (for [sheet sheets :when (not (named? sheet))]
                     {:file (relative sheet) :message "is named by no namespace's {:css […]}"})
                   (missing spaces))))))

(defn report
  "Problems as text, one a line."
  [found]
  (str "The stylesheets have " (count found) (if (= 1 (count found)) " problem" " problems") ":\n"
       (str/join "\n" (for [{:keys [file message]} found] (str "  " file "  " message)))))

(defn- fail! [found]
  (when (seq found)
    (throw (ex-info (report found) {:type ::problems :problems (vec found)}))))

(defn check!
  "Throws with every problem at once."
  [styles]
  (fail! (problems styles)))

;; =============================================================================
;; Bundles
;; =============================================================================

(defn- above
  "Every module a module depends on, through the ones between."
  [depends-on module]
  (loop [todo (vec (depends-on module)), seen #{}]
    (if-let [m (peek todo)]
      (if (seen m)
        (recur (pop todo) seen)
        (recur (into (pop todo) (depends-on m)) (conj seen m)))
      seen)))

(defn plan
  "What goes into each module's bundle, from the modules as the build has
   them: [{:module-id :main :depends-on #{…} :namespaces [what named returns,
   in loading order]}]. Returns {module [File …]}: each module's stylesheets
   in the order its namespaces load and name them, each once, without the
   ones a module it depends on carries; a module with none is left out."
  [modules]
  (let [depends-on (into {} (map (juxt :module-id :depends-on)) modules)
        own        (into {} (for [{:keys [module-id namespaces]} modules]
                              [module-id (vec (distinct (mapcat :css namespaces)))]))]
    (into (sorted-map)
          (for [{:keys [module-id]} modules
                :let [carried (set (mapcat own (above depends-on module-id)))
                      sheets  (vec (remove carried (own module-id)))]
                :when (seq sheets)]
            [module-id sheets]))))

(defn- bundle-text [sheets]
  (str/join "\n" (for [^File sheet sheets]
                   (str "/* " (relative sheet) " */\n" (slurp sheet)))))

(defn- fingerprint [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (str/upper-case (subs (apply str (map #(format "%02x" %) digest)) 0 8))))

(defn- write!
  "Write a file when its content changed, whole at once: the development
   watcher never sees half of it."
  [^File file ^String text]
  (when-not (and (.isFile file) (= text (slurp file)))
    (let [tmp (io/file (.getParentFile file) (str "." (.getName file) ".tmp"))]
      (spit tmp text)
      (Files/move (.toPath tmp) (.toPath file)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))))

(def ^:private minify-script
  "Run by node in the app's folder: reads [{name files}] on stdin, checks
   every file on its own (so a problem names its file), then minifies each
   bundle whole. Answers {bundles: {name: css}} or {error: …}."
  "
const fs = require('fs');
const path = require('path');
let lightningcss;
try {
  lightningcss = require('module').createRequire(path.join(process.cwd(), 'package.json'))('lightningcss');
} catch (e) {
  process.stdout.write(JSON.stringify({missing: true}));
  process.exit(0);
}
const jobs = JSON.parse(fs.readFileSync(0, 'utf8'));
const out = {};
for (const {name, files} of jobs) {
  const texts = [];
  for (const file of files) {
    const code = fs.readFileSync(file);
    try {
      lightningcss.transform({filename: file, code});
    } catch (e) {
      process.stdout.write(JSON.stringify({error: {file, message: e.message,
                                                   line: e.loc && e.loc.line, column: e.loc && e.loc.column}}));
      process.exit(0);
    }
    texts.push(code.toString('utf8'));
  }
  out[name] = lightningcss.transform({filename: name + '.css', code: Buffer.from(texts.join('\\n')),
                                      minify: true}).code.toString('utf8');
}
process.stdout.write(JSON.stringify({bundles: out}));
")

(defn minify
  "Every bundle minified by lightningcss, through node: {module css-text}.
   A stylesheet it can't parse throws, naming the file and the place."
  [plan]
  (let [process (try (.start (ProcessBuilder. ^java.util.List ["node" "-e" minify-script]))
                     (catch java.io.IOException e
                       (throw (ex-info (str "A release minifies the stylesheets with lightningcss, through node: "
                                            (ex-message e))
                                       {:type ::no-node}))))
        _       (with-open [in (.getOutputStream process)]
                  (.write in (.getBytes (json/write-value-as-string
                                         (for [[module sheets] plan]
                                           {:name (name module) :files (mapv str sheets)}))
                                        "UTF-8")))
        out     (future (slurp (.getInputStream process)))
        err     (future (slurp (.getErrorStream process)))
        exit    (.waitFor process)
        answer  (try (json/read-value @out json/keyword-keys-object-mapper) (catch Exception _ nil))]
    (cond
      (:missing answer)
      (throw (ex-info "A release minifies the stylesheets with lightningcss: npm install --save-dev lightningcss"
                      {:type ::no-lightningcss}))

      (:error answer)
      (let [{:keys [file message line column]} (:error answer)]
        (throw (ex-info (str "lightningcss can't read " (relative (io/file file))
                             (when line (str ":" line (when column (str ":" column)))) ": " message)
                        {:type ::unreadable :file (relative (io/file file)) :line line :message message})))

      (or (not (zero? exit)) (nil? (:bundles answer)))
      (throw (ex-info (str "lightningcss didn't run (node exited " exit "): " @err) {:type ::minify-failed}))

      :else
      (into (sorted-map) (for [[k text] (:bundles answer)] [(keyword (name k)) text])))))

(defn write-bundles!
  "Write the bundles and the manifest the page shell reads,
   {:modules {:main \"main.css\"}}: as they are under plain names, or for a
   release minified under fingerprinted ones. A file is written only when
   its content changed. Returns the manifest."
  [{:keys [output-dir]} plan release?]
  (let [out   (io/file output-dir)
        texts (if release?
                (minify plan)
                (into {} (for [[module sheets] plan] [module (bundle-text sheets)])))
        _     (.mkdirs out)
        files (into (sorted-map)
                    (for [[module text] texts]
                      (let [file-name (if release?
                                        (str (name module) "." (fingerprint text) ".css")
                                        (str (name module) ".css"))]
                        (write! (io/file out file-name) text)
                        [module file-name])))
        manifest {:modules files}]
    (write! (io/file out "manifest.edn") (pr-str manifest))
    manifest))

;; =============================================================================
;; The build hook
;; =============================================================================

(defn- module-namespaces
  "The modules as the build has them, each with what its namespaces name,
   in the order the build loads them."
  [build-state]
  (let [sources (:sources build-state)]
    (for [{:keys [module-id depends-on] rc-ids :sources} (:build-modules build-state)]
      {:module-id  module-id
       :depends-on (set depends-on)
       :namespaces (vec (for [rc-id rc-ids
                              :let [{:keys [type url]} (get sources rc-id)]
                              :when (and (= :cljs type) url (= "file" (.getProtocol ^URL url)))
                              :let [found (named url)]
                              :when found]
                          found))})))

(defn hook
  "The browser build's hook (groundwork.build adds it when the app has
   :styles): before compiling, the check; after, the bundles and their
   manifest."
  {:shadow.build/stages #{:compile-prepare :flush}}
  [build-state styles]
  (case (:shadow.build/stage build-state)
    :compile-prepare (check! styles)
    :flush           (let [modules (module-namespaces build-state)]
                       ;; namespaces from outside :sources are checked here
                       (fail! (distinct (missing (mapcat :namespaces modules))))
                       (write-bundles! styles (plan modules) (= :release (:shadow.build/mode build-state)))))
  build-state)

;; =============================================================================
;; The page
;; =============================================================================

(defn manifest
  "The bundles' manifest: from :output-dir when it's there, else from the
   classpath (a packaged app), or nil before the first build."
  [{:keys [output-dir asset-path]}]
  (let [file (io/file output-dir "manifest.edn")]
    (some-> (if (.exists file) file (io/resource (str "public" asset-path "/manifest.edn")))
            slurp
            edn/read-string)))

(defn links
  "The bundles a page links, in order: of the page's module and the modules
   it depends on; without a module, of the modules that depend on no other."
  [{:keys [asset-path] :as styles} browser module]
  (let [{:keys [modules]} (manifest styles)
        all   (build/modules browser)
        chain (if module
                (build/chain all module)
                (into [] (comp (mapcat #(build/chain all %)) (distinct)) (build/roots browser)))]
    (mapv #(str asset-path "/" %) (keep modules chain))))

(defn module-links
  "module → where the browser fetches its bundle, for a module loaded on demand."
  [{:keys [asset-path] :as styles}]
  (into {} (for [[module file] (:modules (manifest styles))] [(name module) (str asset-path "/" file)])))

;; =============================================================================
;; Development
;; =============================================================================

(defonce ^:private watchers (atom {}))

(defn watch!
  "In development: a saved, added or deleted stylesheet under :sources makes
   the build's watcher run again, which checks and writes the bundles."
  [{:keys [sources output-dir]} build-id]
  (let [start  (requiring-resolve 'shadow.cljs.devtools.server.fs-watch/start)
        worker (requiring-resolve 'shadow.cljs.devtools.api/get-worker)
        again  (requiring-resolve 'shadow.cljs.devtools.server.worker/compile)
        dirs   (vec (for [path sources :let [f (canonical (io/file path))] :when (.isDirectory f)] f))
        svc    (start {} dirs ["css"]
                      (fn [updates]
                        (when (some #(not (under? (canonical (:file %)) output-dir)) updates)
                          (some-> (worker build-id) again))))]
    (swap! watchers assoc build-id svc)
    build-id))

(defn stop-watch! [build-id]
  (when-let [svc (get @watchers build-id)]
    ((requiring-resolve 'shadow.cljs.devtools.server.fs-watch/stop) svc)
    (swap! watchers dissoc build-id)))
