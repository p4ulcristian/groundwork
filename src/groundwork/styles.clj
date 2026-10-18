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
              :asset-path \"/css\"                   ; where the browser fetches them
              :targets    \"safari >= 16.4, chrome >= 119\"} ; the browsers they're built for

   The browser build (groundwork.build) does the rest, through a build hook
   of its own:

   - every build first checks the :styles settings and the :targets query
     (browserslist must know it and find browsers for it), and that every
     .css under :sources is named by some namespace and every named file
     exists; otherwise it stops, saying what's wrong
   - each browser module gets one bundle: the stylesheets its namespaces
     name, in the order the build loads them (a namespace's after those of
     the namespaces it requires), each file once; a file a module it depends
     on carries already is left out
   - every bundle is made by lightningcss for the :targets browsers, in
     development as in a release: nesting flattened when one of them lacks
     it, the vendor prefixes they need written. lightningcss and browserslist
     are npm packages in the app's package.json. A stylesheet lightningcss
     can't parse stops the build, naming the file
   - development writes the bundles readable, each file under a comment with
     its path, under plain names (main.css); a saved stylesheet makes the
     build run again, and the open page swaps the changed bundle in
   - a release minifies every bundle and writes fingerprinted names
     (main.3F2A91C0.css)
   - either way it writes manifest.edn next to the bundles; the page shell
     reads it (links, module-links) and links the bundles of the modules a
     page starts with. A module loaded on demand brings its own bundle
     before its code runs (groundwork.browser.loader)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [groundwork.build :as build]
   [groundwork.settings :as settings]
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

;; =============================================================================
;; lightningcss, through node
;; =============================================================================

(def ^:private node-script
  "Run by node in the app's folder, with a job on stdin, the packages taken
   from the app's node_modules. Two jobs:

   {query}: the lightningcss targets for a browserslist query, as
   {targets: …}, or {badQuery: why}.

   {targets, release, bundles: [{name, files: [[path, label]]}]}: every file
   read on its own (so a problem names its file) for the targets; in
   development each one as it comes out, under a comment with its label, in
   a release the bundle's files together, minified. Answers {bundles: {name:
   css}} or {error: …}.

   Before lightningcss writes a rule, a vendor-prefixed declaration that
   repeats an unprefixed one before it in the rule, with the same value, is
   dropped: lightningcss would keep only the prefixed one (backdrop-filter,
   then -webkit-backdrop-filter), and it writes the prefixes the targets
   need itself. Either job answers {missing: package} when a package isn't
   installed."
  "
const fs = require('fs');
const path = require('path');
const need = require('module').createRequire(path.join(process.cwd(), 'package.json'));
const job = JSON.parse(fs.readFileSync(0, 'utf8'));
// The answer goes to a file: process.exit() right after writing to a pipe cuts
// off anything past the pipe's buffer (64 KB), and bundles are larger.
const answer = x => { fs.writeFileSync(job.answer, JSON.stringify(x)); process.exit(0); };
const load = name => { try { return need(name); } catch (e) { answer({missing: name}); } };
const lightningcss = load('lightningcss');

if (job.query !== undefined) {
  const browserslist = load('browserslist');
  let browsers;
  try { browsers = browserslist(job.query); } catch (e) { answer({badQuery: e.message}); }
  if (!browsers.length) answer({badQuery: 'it names no browser'});
  answer({targets: lightningcss.browserslistToTargets(browsers)});
}

// Within one style rule, a prefixed declaration repeating the unprefixed
// one before it is dropped. Run only on text with a prefixed property in
// it: going through the visitor slows lightningcss down several times.
const repeats = () => {
  const rules = [];
  return {
    Rule: {style() { rules.push(new Map()); }},
    RuleExit: {style() { rules.pop(); }},
    Declaration(d) {
      const plain = rules[rules.length - 1];
      if (!plain || !Array.isArray(d.vendorPrefix)) return;
      const value = JSON.stringify(d.value);
      if (d.vendorPrefix.length === 0) { plain.set(d.property, value); return; }
      if (plain.get(d.property) === value) return [];
    }
  };
};
const prefixed = /-(webkit|moz|ms|o)-[a-z-]+\\s*:/i;
const transform = (options, text) =>
  lightningcss.transform(prefixed.test(text) ? {...options, visitor: repeats()} : options);

const {targets, release} = job;
const out = {};
for (const {name, files} of job.bundles) {
  const texts = [];
  for (const [file, label] of files) {
    const code = fs.readFileSync(file);
    const text = code.toString('utf8');
    let result;
    try {
      result = release ? lightningcss.transform({filename: file, code})
                       : transform({filename: file, code, targets}, text);
    } catch (e) {
      answer({error: {file, message: e.message, line: e.loc && e.loc.line, column: e.loc && e.loc.column}});
    }
    texts.push(release ? text : '/* ' + label + ' */\\n' + result.code.toString('utf8'));
  }
  const whole = texts.join('\\n');
  out[name] = release
    ? transform({filename: name + '.css', code: Buffer.from(whole), minify: true, targets}, whole).code.toString('utf8')
    : whole;
}
answer({bundles: out});
")

(defn- run-node
  "node-script run on a job, answering what it wrote."
  [job]
  (let [file    (doto (File/createTempFile "groundwork-styles" ".json") (.deleteOnExit))
        job     (assoc job :answer (.getPath file))
        process (try (.start (ProcessBuilder. ^java.util.List ["node" "-e" node-script]))
                     (catch java.io.IOException e
                       (throw (ex-info (str "The stylesheets are built by lightningcss, through node: "
                                            (ex-message e))
                                       {:type ::no-node}))))
        _       (with-open [in (.getOutputStream process)]
                  (.write in (.getBytes (json/write-value-as-string job) "UTF-8")))
        _       (future (slurp (.getInputStream process)))   ; drained, so node never blocks on a full pipe
        err     (future (slurp (.getErrorStream process)))
        exit    (.waitFor process)
        answer  (try (json/read-value file json/keyword-keys-object-mapper) (catch Exception _ nil))
        _       (.delete file)]
    (cond
      (:missing answer)
      (throw (ex-info (str "The stylesheets are built by lightningcss for the browsers browserslist finds for "
                           ":styles :targets: npm install --save-dev " (:missing answer))
                      {:type ::missing-package :package (:missing answer)}))

      (or (not (zero? exit)) (nil? answer))
      (throw (ex-info (str "lightningcss didn't run (node exited " exit "): " @err) {:type ::node-failed}))

      :else answer)))

(def ^:private example-query "\"safari >= 16.4, chrome >= 119\"")

(defonce ^:private resolved
  ;; query → targets: every build asks, and the query rarely changes
  (atom {}))

(defn targets
  "The lightningcss targets for the app's :styles :targets, a browserslist
   query: {\"safari\" 1049600 …}. Throws, saying why, when :targets is
   missing or empty, isn't a query browserslist knows, or names no browser."
  [{query :targets}]
  (when-not (and (string? query) (not (str/blank? query)))
    (throw (ex-info (str ":styles :targets must name the browsers the stylesheets are built for, "
                         "a browserslist query like " example-query ", got " (pr-str query))
                    {:type ::bad-targets :targets query})))
  (or (get @resolved query)
      (let [answer (run-node {:query query})]
        (when-let [why (:badQuery answer)]
          (throw (ex-info (str ":styles :targets " (pr-str query) " isn't a browserslist query for some browsers: "
                               why ". Like " example-query)
                          {:type ::bad-targets :targets query :message why})))
        (let [found (into {} (for [[browser version] (:targets answer)] [(name browser) version]))]
          (swap! resolved assoc query found)
          found))))

(defn build-bundles
  "Every bundle made by lightningcss for the app's targets: {module
   css-text}. In development each file as lightningcss writes it, under a
   comment with its path; in a release the whole bundle minified. A
   stylesheet it can't parse throws, naming the file and the place."
  [styles plan release?]
  (let [found  (targets styles)
        answer (run-node {:targets found
                          :release (boolean release?)
                          :bundles (for [[module sheets] plan]
                                     {:name  (name module)
                                      :files (mapv (fn [^File f] [(str f) (relative f)]) sheets)})})]
    (if-let [{:keys [file message line column]} (:error answer)]
      (throw (ex-info (str "lightningcss can't read " (relative (io/file file))
                           (when line (str ":" line (when column (str ":" column)))) ": " message)
                      {:type ::unreadable :file (relative (io/file file)) :line line :message message}))
      (into (sorted-map) (for [[k text] (:bundles answer)] [(keyword (name k)) text])))))

(defn write-bundles!
  "Write the bundles and the manifest the page shell reads,
   {:modules {:main \"main.css\"}}: under plain names, or for a release
   minified under fingerprinted ones. A file is written only when its
   content changed. Returns the manifest."
  [{:keys [output-dir] :as styles} plan release?]
  (let [out   (io/file output-dir)
        texts (build-bundles styles plan release?)
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
   :styles): before compiling, the :styles settings, the browsers they name
   and the check; after, the bundles and their manifest."
  {:shadow.build/stages #{:compile-prepare :flush}}
  [build-state styles]
  (case (:shadow.build/stage build-state)
    :compile-prepare (do (settings/check-part! :styles styles)
                         (targets styles)
                         (check! styles))
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
