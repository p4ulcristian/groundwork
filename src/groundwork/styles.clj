(ns groundwork.styles
  "How an app looks: its stylesheets, checked against its code and gathered
   into what each page loads. An app that has styles says so in its settings
   (without :styles it brings its own stylesheets through :page :stylesheets,
   and nothing here runs):

     :styles {:dir        \"styles\"                 ; the styles tree
              :sources    [\"src\"]                  ; where the browser code is
              :base       [\"base/tokens.css\" \"base/reset.css\"]   ; in loading order
              :vendor     [{:file \"vendor/calendar.vendor.css\"   ; a library's own file
                            :prefixes [\"vc-\"]                    ; how its classes start
                            :wrapper \"calendar\"}]                ; the one namespace that may style them
              :output-dir \"resources/public/css\"   ; where the bundles go
              :asset-path \"/css\"}                  ; where the browser fetches them

   The tree, in loading order:

     styles/
       base/     tokens, fonts, resets, plain element styles, themes: no classes
       vendor/   libraries' own files, and the namespaces that wrap them
       looks/    shared looks
       areas/    one area's own looks

   In a component a class is a keyword, :css.job-card/title, and the class it
   names is .job-card__title (groundwork.names). The first half is the
   class's namespace, and a namespace is one stylesheet named after it,
   job-card.css, anywhere under looks/, areas/ or vendor/; a large one is
   several files named job-card.<part>.css. The stylesheets are plain CSS.

   What is checked, every problem reported at once with its file and line
   (problems, report, check!):

   - a class keyword in the code that no stylesheet defines, and a class in a
     stylesheet that no code names; typed class names in the code
   - a rule that styles an element not carrying one of its file's own
     classes (it may look at anything around that element); a rule that
     styles globally, or any class, outside or inside base
   - a variable that is read but defined nowhere: a token is defined in
     base, a namespace's own variable (--job-card__group-color) is read only
     in its own stylesheet and set by anyone
   - a library's classes anywhere but in its wrapper, inside the wrapper's
     own element; a selector on data-testid; two files for one namespace

   What a page loads is worked out from the code: each browser module
   (groundwork.build) gets one bundle with the stylesheets its code uses,
   whole and as written; a namespace several modules use goes to the bundle
   they share. Base and the libraries' files are one bundle before them
   all. A release fingerprints the bundles; the page shell reads their names
   from the manifest next to them.

   CSS is read by a small reader of its own here: rules with their nesting,
   at-rules with their blocks (@media, @container, @supports, @layer,
   @scope), selectors down to their compounds and :is() / :where() / :has()
   / :not(). A general CSS library on the JVM reads @container and newer
   at-rules as text it doesn't look into, and the rules inside them are
   exactly what has to be checked."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [groundwork.build :as build]
   [groundwork.names :as names]
   [groundwork.settings :as settings])
  (:import
   (java.io File PushbackReader)
   (java.security MessageDigest)
   (java.util Arrays)))

;; =============================================================================
;; Reading CSS
;; =============================================================================

(defn- without-comments
  "The text with every comment blanked out, its line breaks kept."
  [^String s]
  (let [sb (StringBuilder. s)
        n  (count s)]
    (loop [i 0, quote nil]
      (when (< i n)
        (let [c (.charAt s i)]
          (cond
            quote                   (cond (= c \\)    (recur (+ i 2) quote)
                                          (= c quote) (recur (inc i) nil)
                                          :else       (recur (inc i) quote))
            (or (= c \") (= c \')) (recur (inc i) c)
            (and (= c \/) (< (inc i) n) (= \* (.charAt s (inc i))))
            (let [close (.indexOf s "*/" (int (+ i 2)))
                  end   (if (neg? close) n (+ close 2))]
              (doseq [j (range i end)]
                (when-not (= \newline (.charAt s j)) (.setCharAt sb j \space)))
              (recur end nil))
            :else                   (recur (inc i) nil)))))
    (str sb)))

(defn- scan
  "Index of the first of stops from i that is outside strings, () and []; the
   text's length when there is none."
  [^String s i stops]
  (let [n (count s)]
    (loop [i i, depth 0, quote nil]
      (if (>= i n)
        n
        (let [c (.charAt s i)]
          (cond
            quote                                   (cond (= c \\)    (recur (+ i 2) depth quote)
                                                          (= c quote) (recur (inc i) depth nil)
                                                          :else       (recur (inc i) depth quote))
            (= c \\)                                (recur (+ i 2) depth nil)
            (or (= c \") (= c \'))                 (recur (inc i) depth c)
            (and (zero? depth) (contains? stops c)) i
            (or (= c \() (= c \[))                  (recur (inc i) (inc depth) nil)
            (or (= c \)) (= c \]))                  (recur (inc i) (max 0 (dec depth)) nil)
            :else                                   (recur (inc i) depth nil)))))))

(defn- line-counter
  "A function from an index in the text to its line."
  [^String s]
  (let [breaks (long-array (keep-indexed (fn [i c] (when (= \newline c) i)) s))]
    (fn [i]
      (let [found (Arrays/binarySearch breaks (long i))]
        (inc (if (neg? found) (- (inc found)) found))))))

(defn- statement [prelude line]
  (if (str/starts-with? prelude "@")
    (let [[_ at params] (re-matches #"(?s)@([A-Za-z-]+)\s*(.*)" prelude)]
      {:kind :at-statement :name (str/lower-case (or at "")) :params (str/trim (or params "")) :line line})
    (let [colon (scan prelude 0 #{\:})]
      {:kind  :declaration
       :prop  (str/trim (subs prelude 0 colon))
       :value (str/trim (subs prelude (min (count prelude) (inc colon))))
       :line  line})))

(defn- block [prelude line children]
  (if (str/starts-with? prelude "@")
    (let [[_ at params] (re-matches #"(?s)@([A-Za-z-]+)\s*(.*)" prelude)]
      {:kind :at :name (str/lower-case (or at "")) :params (str/trim (or params "")) :line line :children children})
    {:kind :rule :selector prelude :line line :children children}))

(defn- read-block
  "The statements from i up to the block's closing brace: [nodes index-after]."
  [^String s i line-of]
  (let [n (count s)]
    (loop [i i, nodes []]
      (let [i (loop [i i] (if (and (< i n) (Character/isWhitespace (.charAt s i))) (recur (inc i)) i))]
        (cond
          (>= i n)              [nodes n]
          (= \} (.charAt s i))  [nodes (inc i)]
          :else
          (let [j       (scan s i #{\{ \; \}})
                prelude (str/trim (subs s i j))
                c       (when (< j n) (.charAt s j))]
            (if (= c \{)
              (let [[children k] (read-block s (inc j) line-of)]
                (recur k (conj nodes (block prelude (line-of i) children))))
              (recur (if (= c \;) (inc j) j)
                     (cond-> nodes (seq prelude) (conj (statement prelude (line-of i))))))))))))

(defn parse
  "CSS text as nodes: {:kind :rule :selector … :children […]}, {:kind :at
   :name \"media\" :params … :children […]}, {:kind :at-statement …} and
   {:kind :declaration :prop … :value …}, each with its :line."
  [text]
  (let [s (without-comments text)]
    (first (read-block s 0 (line-counter s)))))

;; -----------------------------------------------------------------------------
;; Selectors
;; -----------------------------------------------------------------------------

(defn- split-top
  "The text split at every one of stops outside strings, () and []."
  [^String s stops]
  (loop [i 0, parts []]
    (let [j (scan s i stops)]
      (if (>= j (count s))
        (conj parts (subs s i))
        (recur (inc j) (conj parts (subs s i j)))))))

(defn- ident-end [^String s i]
  (let [n (count s)]
    (loop [i i]
      (if (>= i n)
        n
        (let [c (.charAt s i)]
          (cond
            (= c \\)                                                                     (recur (min n (+ i 2)))
            (or (Character/isLetterOrDigit c) (= c \-) (= c \_) (< 127 (int c))) (recur (inc i))
            :else                                                                        i))))))

(defn- compound
  "One compound selector, taken apart: its classes, ids, attributes, type,
   pseudo-classes, and the pseudo-classes that take selectors."
  [^String s]
  (let [n (count s)]
    (loop [i 0, m {:classes [] :ids [] :attrs [] :pseudos [] :fns [] :amp? false :type nil}]
      (if (>= i n)
        m
        (let [c (.charAt s i)]
          (case c
            \. (let [j (ident-end s (inc i))] (recur (max j (inc i)) (update m :classes conj (subs s (inc i) j))))
            \# (let [j (ident-end s (inc i))] (recur (max j (inc i)) (update m :ids conj (subs s (inc i) j))))
            \[ (let [j (scan s (inc i) #{\]})] (recur (inc j) (update m :attrs conj (subs s (inc i) (min n j)))))
            \: (let [k    (if (and (< (inc i) n) (= \: (.charAt s (inc i)))) (+ i 2) (inc i))
                     j    (ident-end s k)
                     word (str/lower-case (subs s k j))]
                 (if (and (< j n) (= \( (.charAt s j)))
                   (let [e (scan s (inc j) #{\)})]
                     (recur (inc e) (update m :fns conj {:name word :arg (subs s (inc j) (min n e))})))
                   (recur (max j (inc i)) (update m :pseudos conj word))))
            \& (recur (inc i) (assoc m :amp? true))
            \* (recur (inc i) (assoc m :type "*"))
            (let [j (ident-end s i)]
              (if (= j i)
                (recur (inc i) m)
                (recur j (assoc m :type (subs s i j)))))))))))

(defn- complex
  "A complex selector as its compounds, left to right; the last is the
   element the rule styles."
  [^String s]
  (let [n (count s)]
    (loop [i 0, start nil, depth 0, quote nil, out []]
      (let [end (fn [] (if start (conj out (compound (subs s start i))) out))]
        (if (>= i n)
          (end)
          (let [c (.charAt s i)]
            (cond
              quote                  (recur (inc i) start depth (when-not (= c quote) quote) out)
              (or (= c \") (= c \')) (recur (inc i) (or start i) depth c out)
              (or (= c \() (= c \[)) (recur (inc i) (or start i) (inc depth) nil out)
              (or (= c \)) (= c \])) (recur (inc i) (or start i) (max 0 (dec depth)) nil out)
              (and (zero? depth)
                   (or (Character/isWhitespace c) (= c \>) (= c \+) (= c \~)))
              (recur (inc i) nil 0 nil (end))
              :else                  (recur (inc i) (or start i) depth nil out))))))))

(defn selectors
  "A selector list as complex selectors, each a vector of compounds."
  [text]
  (into [] (comp (map str/trim) (remove str/blank?) (map complex) (remove empty?)) (split-top text #{\,})))

(def ^:private taking-selectors #{"is" "where" "not" "has" "matches" "-webkit-any" "-moz-any" "host" "host-context" "slotted"})

(defn- everything
  "Every compound a selector list mentions, the ones inside :is(), :not(),
   :has() and the like too."
  [complexes]
  (for [cx  complexes
        cmp cx
        c   (cons cmp (mapcat (fn [{:keys [name arg]}]
                                (when (taking-selectors name) (everything (selectors arg))))
                              (:fns cmp)))]
    c))

;; -----------------------------------------------------------------------------
;; A stylesheet, flat
;; -----------------------------------------------------------------------------

(def ^:private grouping
  "At-rules whose block holds rules of the place they stand in."
  #{"media" "container" "supports" "layer" "scope" "starting-style" "document" "-moz-document"})

(defn- flatten-nodes
  "A stylesheet's rules, declarations and at-rules in one map. A rule knows
   the rule it is nested in; :styled holds the rules that have declarations
   of their own. Inside @keyframes, @font-face and such nothing is a rule."
  [nodes]
  (letfn [(walk [acc nodes parent opaque?]
            (reduce
             (fn [acc node]
               (case (:kind node)
                 :declaration  (cond-> (update acc :declarations conj node)
                                 (and parent (not opaque?)) (update :styled conj (:id parent)))
                 :at-statement (update acc :statements conj node)
                 :at           (let [acc (update acc :ats conj (dissoc node :children))]
                                 (if (and (not opaque?) (grouping (:name node)))
                                   (walk acc (:children node) parent false)
                                   (walk acc (:children node) nil true)))
                 :rule         (if opaque?
                                 (walk acc (:children node) nil true)
                                 (let [rule {:id        (count (:rules acc))
                                             :line      (:line node)
                                             :selector  (:selector node)
                                             :complexes (selectors (:selector node))
                                             :parent    (:id parent)}]
                                   (walk (update acc :rules conj rule) (:children node) rule false)))))
             acc nodes))]
    (walk {:rules [] :declarations [] :statements [] :ats [] :styled #{}} nodes nil false)))

(defn- variables-read
  "The variables a value reads: [{:name \"--x\" :fallback? true}]."
  [value]
  (for [[_ var-name more] (re-seq #"var\(\s*(--[A-Za-z0-9_-]+)\s*(,)?" (or value ""))]
    {:name var-name :fallback? (some? more)}))

;; =============================================================================
;; The tree
;; =============================================================================

(def ^:private folders ["base" "vendor" "looks" "areas"])

(defn- relative [^File dir ^File file]
  (str/replace (str (.relativize (.toPath dir) (.toPath file))) File/separator "/"))

(defn- css-files [dir]
  (let [dir (io/file dir)]
    (when (.isDirectory dir)
      (sort (for [^File f (file-seq dir)
                  :when (and (.isFile f) (str/ends-with? (.getName f) ".css"))]
              (relative dir f))))))

(defn- file-namespace
  "The namespace a stylesheet is named after: job-card.css and
   job-card.states.css are both job-card's."
  [path]
  (first (str/split (last (str/split path #"/")) #"\.")))

(defn- role
  "What a file in the tree is: :base, a :library's own file, a :namespace's
   stylesheet, or :stray."
  [{:keys [base vendor]} path]
  (let [folder (first (str/split path #"/"))]
    (cond
      (some #{path} base)                                {:role :base}
      (some #(= path (:file %)) vendor)                  {:role :library}
      (and (#{"vendor" "looks" "areas"} folder)
           (str/includes? path "/"))                     {:role :namespace :namespace (file-namespace path) :folder folder}
      :else                                              {:role :stray})))

(defn read-tree
  "The styles tree, read: every stylesheet with its role, and what it holds."
  [{:keys [dir] :as styles}]
  (into (sorted-map)
        (for [path (css-files dir)]
          (let [what (role styles path)
                text (slurp (io/file dir path))]
            [path (cond-> (assoc what :path path :text text)
                    (not= :library (:role what)) (merge (flatten-nodes (parse text))))]))))

;; =============================================================================
;; The code
;; =============================================================================

(def ^:private tags
  (str "a|abbr|article|aside|audio|b|blockquote|body|br|button|canvas|caption|circle|code|col|colgroup|"
       "dd|details|dialog|div|dl|dt|em|fieldset|figcaption|figure|footer|form|g|h1|h2|h3|h4|h5|h6|head|"
       "header|hr|i|iframe|img|input|label|legend|li|line|main|nav|ol|optgroup|option|p|path|picture|"
       "polygon|polyline|pre|progress|rect|section|select|small|source|span|strong|sub|summary|sup|svg|"
       "table|tbody|td|text|textarea|tfoot|th|thead|time|tr|u|ul|video"))

(def ^:private class-keyword #"(?<![A-Za-z0-9*+!_'?<>=/.:-]):(css\.[A-Za-z0-9._\-]*/[A-Za-z0-9._*+!?<>=\-]*)")
(def ^:private shorthand (re-pattern (str "\\[:(?:" tags ")((?:[.#][A-Za-z_\\-][A-Za-z0-9_\\-]*)+)(?![A-Za-z0-9_/.*+!?\\-])")))
(def ^:private typed-class #":(?:class|className|class-name)\s+\"([^\"]*)\"")
(def ^:private code-variable #"--[a-z][a-z0-9]*(?:-[a-z0-9]+)*__[a-z][a-z0-9]*(?:-[a-z0-9]+)*")

(defn- ns-form [file]
  (try
    (with-open [reader (PushbackReader. (io/reader file))]
      (binding [*read-eval* false, *default-data-reader-fn* tagged-literal]
        (let [form (read {:read-cond :allow :features #{:cljs} :eof nil} reader)]
          (when (and (seq? form) (= 'ns (first form)))
            form))))
    (catch Exception _ nil)))

(defn- requires
  "The namespaces an ns form requires."
  [form]
  (set (for [clause form
             :when  (and (seq? clause) (#{:require :use} (first clause)))
             spec   (rest clause)
             :let   [sym (if (sequential? spec) (first spec) spec)]
             :when  (symbol? sym)]
         sym)))

(defn- code-line
  "A line of code without what follows a ;; comment."
  [line]
  (let [i (str/index-of line ";;")]
    (if i (subs line 0 i) line)))

(defn read-sources
  "The browser code under the source paths: every .cljs and .cljc file with
   its namespace, what it requires, and the class keywords, typed class
   names and stylesheet variables in it, each with its line."
  [paths]
  (vec
   (for [path  paths
         ^File f (sort (file-seq (io/file path)))
         :when (and (.isFile f) (re-find #"\.clj[sc]$" (.getName f)))]
     (let [form  (ns-form f)
           lines (map-indexed (fn [i line] [(inc i) (code-line line)]) (str/split-lines (slurp f)))]
       {:path      (str f)
        :ns        (when form (second form))
        :requires  (if form (requires form) #{})
        :classes   (vec (for [[n line] lines, [_ k] (re-seq class-keyword line)]
                          {:line n :text (str ":" k) :key (keyword k)}))
        :typed     (vec (concat
                         (for [[n line] lines, [_ more] (re-seq shorthand line) :when (str/includes? more ".")]
                           {:line n :text more :classes (mapv #(subs % 1) (re-seq #"\.[A-Za-z0-9_\-]+" more))})
                         (for [[n line] lines, [_ text] (re-seq typed-class line) :when (not (str/blank? text))]
                           {:line n :text (pr-str text) :classes (str/split (str/trim text) #"\s+")})))
        :variables (set (for [[_ line] lines, v (re-seq code-variable line)] v))}))))

;; =============================================================================
;; Problems
;; =============================================================================

(defn- library-of
  "The library a class name belongs to, by how it starts."
  [vendor class-name]
  (first (filter (fn [{:keys [prefixes]}] (some #(str/starts-with? class-name %) prefixes)) vendor)))

(defn- own-classes
  "The classes of its own namespace a stylesheet mentions, with the line of
   each one's first rule."
  [{:keys [rules] file-ns :namespace}]
  (reduce (fn [m rule]
            (reduce (fn [m class-name]
                      (if (and (= file-ns (names/class-namespace class-name)) (not (contains? m class-name)))
                        (assoc m class-name (:line rule))
                        m))
                    m
                    (mapcat :classes (everything (:complexes rule)))))
          (sorted-map)
          rules))

(defn- owned?
  "Whether the element a complex selector styles carries a class own? accepts:
   on its last compound, through & when the rule it is nested in does, or in
   every alternative of an :is() / :where() there."
  [cx own? parent-owned?]
  (let [subject (peek cx)]
    (boolean
     (or (some own? (:classes subject))
         (and (:amp? subject) parent-owned?)
         (some (fn [{:keys [name arg]}]
                 (and (#{"is" "where" "matches" "-webkit-any" "-moz-any"} name)
                      (let [alternatives (selectors arg)]
                        (and (seq alternatives)
                             (every? #(owned? % own? parent-owned?) alternatives)))))
               (:fns subject))))))

(defn- rule-owned?
  "Whether every element a rule styles carries one of the file's classes."
  [rules rule own?]
  (let [parent (some->> (:parent rule) (nth rules))
        above? (boolean (and parent (rule-owned? rules parent own?)))]
    (and (seq (:complexes rule))
         (every? #(owned? % own? above?) (:complexes rule)))))

(defn- inside-own?
  "Whether a complex selector, or a rule it is nested in, names one of the
   file's own classes on the way to the element it styles."
  [rules rule cx own?]
  (or (some own? (mapcat :classes cx))
      (loop [parent (some->> (:parent rule) (nth rules))]
        (when parent
          (or (some own? (mapcat :classes (apply concat (:complexes parent))))
              (recur (some->> (:parent parent) (nth rules))))))))

(defn- namespace-problems
  "The problems in one namespace's stylesheet."
  [{:keys [vendor]} defined {:keys [path rules styled] file-ns :namespace}]
  (let [wraps  (first (filter #(= file-ns (:wrapper %)) vendor))
        own?   #(= file-ns (names/class-namespace %))
        theirs #(and wraps (some (fn [prefix] (str/starts-with? % prefix)) (:prefixes wraps)))
        said   (fn [rule message] {:file path :line (:line rule) :message message})]
    (concat
     (for [rule rules, class-name (distinct (mapcat :classes (everything (:complexes rule))))
           :let [library (library-of vendor class-name)
                 home    (names/class-namespace class-name)]
           :when (not (own? class-name))]
       (cond
         (and library (not= file-ns (:wrapper library)))
         (said rule (str "." class-name " is a class of the library " (:file library) "; only its wrapper, "
                         (:wrapper library) ", may style it"))
         library nil
         (nil? home)
         (said rule (str "." class-name " isn't a class like " file-ns "__name"))
         (not (contains? (get defined home) class-name))
         (said rule (str "." class-name " is mentioned here, but " (if (contains? defined home)
                                                                     (str home "'s stylesheet doesn't define it")
                                                                     (str "there is no stylesheet for " home))))))
     (for [rule rules, cx (:complexes rule)
           :when (and (some theirs (mapcat :classes (everything [cx])))
                      (not (inside-own? rules rule cx own?)))]
       (said rule (str "\"" (:selector rule) "\" styles the library outside " file-ns "'s own element; start from a ."
                       file-ns "__… class")))
     (for [rule rules
           :when (contains? styled (:id rule))
           :let  [subjects (map peek (:complexes rule))
                  classes  (mapcat :classes subjects)]
           :when (not (rule-owned? rules rule #(or (own? %) (theirs %))))]
       (cond
         (and (empty? classes) (not-any? :amp? subjects) (not-any? #(seq (:fns %)) subjects))
         (said rule (str "\"" (:selector rule) "\" styles globally; only base may, a namespace's stylesheet styles its own classes"))
         :else
         (said rule (str "\"" (:selector rule) "\" styles an element that carries no ." file-ns "__… class: a stylesheet "
                         "paints only its own; change another look through a state or a variable it offers")))))))

(defn- base-problems [{:keys [path rules]}]
  (for [rule rules, class-name (distinct (mapcat :classes (everything (:complexes rule))))]
    {:file path :line (:line rule) :message (str "." class-name ": base has no classes; a class lives in its namespace's stylesheet")}))

(defn- everywhere-problems
  "What no stylesheet may do, base included."
  [{:keys [path rules statements]}]
  (concat
   (for [rule rules
         :when (some #(str/includes? % "data-testid") (mapcat :attrs (everything (:complexes rule))))]
     {:file path :line (:line rule) :message "selects by data-testid: that is a handle for tests, never for styles"})
   (for [{:keys [name line]} statements :when (= "import" name)]
     {:file path :line line :message "@import: a stylesheet doesn't load another; the bundles are worked out from the code"})))

(defn- variable-problems
  "A variable read but defined nowhere, read outside its own stylesheet, or
   set though its stylesheet never reads it."
  [tree sources]
  (let [checked     (remove #(= :library (:role %)) (vals tree))
        namespaces  (set (keep :namespace checked))
        set-in      (fn [file] (concat (for [{:keys [prop line]} (:declarations file) :when (str/starts-with? prop "--")]
                                         {:name prop :line line})
                                       (for [{:keys [name params line]} (:ats file) :when (= "property" name)]
                                         {:name (first (str/split params #"\s+")) :line line})))
        reads-in    (fn [file] (for [{:keys [value line]} (:declarations file), read (variables-read value)]
                                 (assoc read :line line)))
        tokens      (set (for [file checked :when (= :base (:role file)), v (set-in file)] (:name v)))
        set-names   (into (set (mapcat :variables sources))
                          (for [file checked, v (set-in file)] (:name v)))
        home        (fn [var-name] (some-> (names/class-namespace (subs var-name 2))))
        own-reads   (into {} (for [file checked :when (:namespace file)]
                               [(:namespace file) (group-by :name (filter #(= (:namespace file) (home (:name %))) (reads-in file)))]))
        read-at-home (reduce (fn [m [ns-name by-name]] (merge-with merge m {ns-name by-name})) {} own-reads)]
    (concat
     (for [file checked, {:keys [name line fallback?]} (reads-in file)
           :let [owner (home name)]]
       (cond
         (and owner (not= owner (:namespace file)))
         {:file (:path file) :line line
          :message (str name " is " owner "'s own variable: only " owner "'s stylesheet reads it (anyone may set it)")}
         (and owner (not (or fallback? (contains? set-names name)
                             (some :fallback? (get-in read-at-home [owner name])))))
         {:file (:path file) :line line
          :message (str name " is read but set nowhere, and has no fallback: var(" name ", <value>)")}
         (and (nil? owner) (not (contains? tokens name)))
         {:file (:path file) :line line :message (str name " is defined nowhere: a token is defined in base")}))
     (for [file checked, {:keys [name line]} (set-in file)
           :let [owner (home name)]
           :when owner]
       (cond
         (not (contains? namespaces owner))
         {:file (:path file) :line line :message (str name ": there is no stylesheet for " owner)}
         (empty? (get-in read-at-home [owner name]))
         {:file (:path file) :line line :message (str name " is set, but " owner "'s stylesheet never reads it")})))))

(defn- code-problems
  "A class keyword no stylesheet defines, typed class names, and a class no
   code names."
  [{:keys [vendor]} tree defined sources]
  (let [used (set (for [source sources, {k :key} (:classes source)] (names/as-class k)))]
    (concat
     (for [{:keys [path classes]} sources, {:keys [line text] k :key} classes
           :let [class-name (names/as-class k)
                 home       (some-> class-name names/class-namespace)]]
       (cond
         (nil? class-name)
         {:file path :line line :message (str text " isn't a class keyword like :css.job-card/title")}
         (not (contains? defined home))
         {:file path :line line :message (str text ": there is no stylesheet for " home)}
         (not (contains? (defined home) class-name))
         {:file path :line line :message (str text ": " home "'s stylesheet has no ." class-name)}))
     (for [{:keys [path typed]} sources, {:keys [line text classes]} typed
           :when (not (every? #(library-of vendor %) classes))]
       {:file path :line line :message (str "the class " text " is typed as text; a class is a keyword like :css.job-card/title")})
     (for [file (vals tree) :when (:namespace file)
           [class-name line] (own-classes file)
           :when (not (contains? used class-name))]
       {:file (:path file) :line line :message (str "." class-name " is named by no code (" (names/class->key class-name) ")")}))))

(defn- tree-problems
  "Files that are missing, astray, or two for one namespace."
  [{:keys [dir base vendor]} tree]
  (concat
   (for [path (concat base (map :file vendor)) :when (not (contains? tree path))]
     {:file path :line 0 :message (str "is in the settings but not in " dir)})
   (for [{:keys [file]} vendor :when (not (str/starts-with? file "vendor/"))]
     {:file file :line 0 :message "a library's file belongs under vendor/"})
   (for [path base :when (not (str/starts-with? path "base/"))]
     {:file path :line 0 :message "a base file belongs under base/"})
   (for [{:keys [wrapper file]} vendor
         :when (not-any? #(and (= wrapper (:namespace %)) (= "vendor" (:folder %))) (vals tree))]
     {:file file :line 0 :message (str "its wrapper's stylesheet, vendor/" wrapper ".css, isn't there")})
   (for [[path {:keys [role]}] tree :when (= :stray role)]
     {:file path :line 0
      :message (if (str/starts-with? path "base/")
                 "is in base/ but not in the settings' :base list, which gives the loading order"
                 (str "is outside the tree: a stylesheet lives under " (str/join "/, " folders) "/"))})
   (for [[_ files] (group-by #(last (str/split (:path %) #"/")) (filter :namespace (vals tree)))
         :when (< 1 (count files))
         file (rest (sort-by :path files))]
     {:file (:path file) :line 0
      :message (str "is a second " (last (str/split (:path file) #"/")) " (the other is " (:path (first (sort-by :path files)))
                    "): one namespace is one stylesheet; name a part " (:namespace file) ".<part>.css")})
   (for [file (vals tree) :when (:namespace file)
         :when (not (re-matches #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*" (:namespace file)))]
     {:file (:path file) :line 0 :message "isn't named like a namespace: lowercase words joined by single hyphens"})))

(defn- defined-classes
  "namespace → the classes its stylesheets define."
  [tree]
  (reduce (fn [m file] (update m (:namespace file) (fnil into (sorted-set)) (keys (own-classes file))))
          {}
          (filter :namespace (vals tree))))

(defn analysis
  "The tree and the code, read, and what they define: for problems, bundles
   and the REPL's questions."
  [styles]
  (let [tree (read-tree styles)]
    {:styles  styles
     :tree    tree
     :sources (read-sources (:sources styles))
     :defined (defined-classes tree)}))

(defn problems
  "Every problem with an app's styles, [{:file … :line … :message …}],
   sorted by file and line."
  ([styles] (problems styles (analysis styles)))
  ([styles {:keys [tree sources defined]}]
   (let [dir (:dir styles)]
     (->> (concat
           (tree-problems styles tree)
           (mapcat #(namespace-problems styles defined %) (filter :namespace (vals tree)))
           (mapcat base-problems (filter #(= :base (:role %)) (vals tree)))
           (mapcat everywhere-problems (remove #(= :library (:role %)) (vals tree)))
           (variable-problems tree sources)
           (code-problems styles tree defined sources))
          (remove nil?)
          (map (fn [problem]
                 (cond-> problem
                   (contains? tree (:file problem)) (update :file #(str dir "/" %)))))
          distinct
          (sort-by (juxt :file :line :message))
          vec))))

(defn report
  "The problems as text, one a line: file:line message."
  [found]
  (str "The styles have " (count found) (if (= 1 (count found)) " problem" " problems") ":\n"
       (str/join "\n" (for [{:keys [file line message]} found]
                        (str "  " file (when (pos? line) (str ":" line)) "  " message)))))

(defn check!
  "Throws with every problem at once, or returns the analysis."
  ([styles] (check! styles (analysis styles)))
  ([styles read]
   (let [found (problems styles read)]
     (when (seq found)
       (throw (ex-info (report found) {:type ::problems :problems found})))
     read)))

;; =============================================================================
;; Bundles
;; =============================================================================

(defn- reachable
  "The namespaces a module's entries reach through their requires."
  [by-ns entries]
  (loop [todo (vec entries), seen #{}]
    (if-let [n (peek todo)]
      (if (or (seen n) (not (contains? by-ns n)))
        (recur (pop todo) seen)
        (recur (into (pop todo) (:requires (by-ns n))) (conj seen n)))
      seen)))

(defn- module-entries [{:keys [init-fn entries]}]
  (cond-> (vec entries) init-fn (conj (symbol (namespace init-fn)))))

(defn- above
  "A module and every module it depends on."
  [modules module]
  (loop [todo [module], seen #{}]
    (if-let [m (peek todo)]
      (if (seen m)
        (recur (pop todo) seen)
        (recur (into (pop todo) (:depends-on (modules m))) (conj seen m)))
      seen)))

(defn- home
  "The module whose bundle carries a namespace its users share: the user
   itself when there is one, else the deepest module all of them load."
  [modules users]
  (let [shared (apply set/intersection (map #(above modules %) users))]
    (when (seq shared)
      (apply max-key #(count (above modules %)) (sort shared)))))

(defn- tree-order
  "A stylesheet's place in the loading order: its folder, then its path."
  [{:keys [folder path]}]
  [(.indexOf ^java.util.List folders folder) path])

(defn plan
  "What goes into which bundle: {:base [paths] :modules {module [paths]}
   :users {namespace #{modules}}}. A namespace goes to the bundle of the
   module that uses it; one that several use, to the bundle they share, or
   to each of them when they share none."
  [{:keys [base vendor] :as _styles} browser {:keys [tree sources]}]
  (let [modules  (build/modules browser)
        by-ns    (into {} (keep (fn [s] (when (:ns s) [(:ns s) s]))) sources)
        uses     (fn [source] (set (keep #(some-> (names/as-class (:key %)) names/class-namespace) (:classes source))))
        used-by  (into {} (for [[module spec] modules]
                            [module (set (mapcat #(uses (by-ns %)) (reachable by-ns (module-entries spec))))]))
        users    (reduce (fn [m [module spaces]] (reduce #(update %1 %2 (fnil conj (sorted-set)) module) m spaces))
                         (sorted-map) used-by)
        files-of (group-by :namespace (filter :namespace (vals tree)))
        placed   (for [[space mods] users
                       module (if-let [shared (home modules mods)] [shared] mods)
                       file   (files-of space)]
                   [module file])]
    {:base    (vec (concat base (map :file vendor)))
     :modules (into (sorted-map)
                    (for [[module pairs] (group-by first placed)]
                      [module (mapv :path (sort-by tree-order (map second pairs)))]))
     :users   users}))

(defn- bundle-text [dir paths]
  (str/join "\n" (for [path paths]
                   (str "/* " path " */\n" (slurp (io/file dir path))))))

(defn- fingerprint [^String text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (str/upper-case (subs (apply str (map #(format "%02x" %) digest)) 0 8))))

(defn write-bundles!
  "Write the bundles and the manifest the page shell reads. Fingerprinted
   names for a release, plain ones otherwise; a file is written only when
   its content changed. Returns the manifest: {:base \"base.css\" :modules
   {:main \"main.css\"}}."
  [{:keys [dir output-dir]} {:keys [base modules]} fingerprint?]
  (let [out    (io/file output-dir)
        write! (fn [bundle paths]
                 (let [text (bundle-text dir paths)
                       file (io/file out (if fingerprint?
                                           (str bundle "." (fingerprint text) ".css")
                                           (str bundle ".css")))]
                   (when-not (and (.exists file) (= text (slurp file)))
                     (spit file text))
                   (.getName file)))]
    (.mkdirs out)
    (let [manifest {:base    (write! "base" base)
                    :modules (into (sorted-map) (for [[module paths] modules] [module (write! (name module) paths)]))}
          file     (io/file out "manifest.edn")]
      (when-not (and (.exists file) (= manifest (edn/read-string (slurp file))))
        (spit file (pr-str manifest)))
      manifest)))

(defn manifest
  "The bundles' manifest: from :output-dir when it's there, else from the
   classpath (a packaged app), or nil before the first build."
  [{:keys [output-dir asset-path]}]
  (let [file (io/file output-dir "manifest.edn")]
    (some-> (if (.exists file) file (io/resource (str "public" asset-path "/manifest.edn")))
            slurp
            edn/read-string)))

(defn links
  "The stylesheets a page loads, in order: base, then the bundles of the
   page's module and the modules it depends on. Without a module, of the
   modules that depend on no other."
  [{:keys [asset-path] :as styles} browser module]
  (let [{:keys [base modules]} (manifest styles)
        chain (if module
                (build/chain (build/modules browser) module)
                (into [] (comp (mapcat #(build/chain (build/modules browser) %)) (distinct)) (build/roots browser)))]
    (mapv #(str asset-path "/" %) (cons base (keep modules chain)))))

(defn module-links
  "module → where the browser fetches its bundle, for a module loaded on demand."
  [{:keys [asset-path] :as styles}]
  (into {} (for [[module file] (:modules (manifest styles))] [(name module) (str asset-path "/" file)])))

(defn build!
  "Read, check and write the bundles: for development and tests (plain
   names). Throws with every problem at once."
  [styles browser]
  (let [read  (check! styles)
        plan* (plan styles browser read)]
    (assoc read :plan plan* :manifest (write-bundles! styles plan* false))))

(defn release!
  "Check, then write the fingerprinted bundles and their manifest: with the
   browser build's release, before packaging the app."
  [{:keys [styles browser]}]
  (when styles
    (let [read (check! styles)]
      (write-bundles! styles (plan styles browser read) true))))

;; =============================================================================
;; Questions
;; =============================================================================

(defn explain-class
  "Where a class is defined, which code uses it and which bundles carry it."
  [{:keys [tree sources plan styles]} class-key]
  (let [class-name (names/key->class class-key)
        space      (names/class-namespace class-name)
        dir        (:dir styles)]
    {:class      class-name
     :defined    (vec (for [file (vals tree) :when (= space (:namespace file))
                            :let [line (get (own-classes file) class-name)] :when line]
                        (str dir "/" (:path file) ":" line)))
     :used       (vec (for [{:keys [path classes]} sources, {:keys [line] k :key} classes :when (= k class-key)]
                        (str path ":" line)))
     :bundles    (vec (for [[module paths] (:modules plan)
                            :when (some #(= space (:namespace (tree %))) paths)]
                        module))
     :mentioned  (vec (for [file (vals tree) :when (and (:namespace file) (not= space (:namespace file)))
                            rule (:rules file)
                            :when (some #{class-name} (mapcat :classes (everything (:complexes rule))))]
                        (str dir "/" (:path file) ":" (:line rule))))}))

(defn page-loads
  "What a page of this module loads, and why: the base files in order, then
   each module's stylesheets with the code that uses them."
  [{:keys [tree sources plan styles]} browser module]
  (let [modules (build/modules browser)
        chain   (if module
                  (build/chain modules module)
                  (into [] (comp (mapcat #(build/chain modules %)) (distinct)) (build/roots browser)))
        users   (fn [space]
                  (vec (sort (distinct (for [{:keys [path classes]} sources, {k :key} classes
                                             :when (= space (some-> (names/as-class k) names/class-namespace))]
                                         path)))))]
    {:base    (:base plan)
     :modules (vec (for [m chain :when (contains? (:modules plan) m)]
                     {:module m
                      :stylesheets (vec (for [path (get-in plan [:modules m])]
                                          {:file (str (:dir styles) "/" path) :used-by (users (:namespace (tree path)))}))}))}))

(defn known-classes
  "Every class the stylesheets define, for the browser's own check in development."
  [{:keys [defined]}]
  (vec (sort (mapcat val defined))))

;; =============================================================================
;; The part
;; =============================================================================

(defn- stamp
  "What says whether the tree or the code changed since the last look."
  [{:keys [dir sources]}]
  (reduce (fn [stamp ^File f] (+ stamp (.lastModified f) (.length f) (hash (.getPath f))))
          0
          (filter #(.isFile ^File %) (mapcat #(file-seq (io/file %)) (cons dir sources)))))

(defn- watch!
  "In development: look at the tree and the code twice a second; after a
   save, check again and write the bundles, so the open page shows it."
  [styles browser state]
  (let [running (atom true)]
    (doto (Thread.
           (fn []
             (loop [last-stamp (stamp styles)]
               (Thread/sleep 500)
               (when @running
                 (let [now (try (stamp styles) (catch Exception _ last-stamp))]
                   (when (not= now last-stamp)
                     (try
                       (let [read  (analysis styles)
                             found (problems styles read)
                             plan* (plan styles browser read)]
                         (reset! state (assoc read :plan plan* :manifest (write-bundles! styles plan* false)))
                         (when (seq found) (println (report found))))
                       (catch Exception e
                         (println "The styles couldn't be read:" (ex-message e)))))
                   (recur now))))))
      (.setDaemon true)
      (.setName "groundwork-styles")
      (.start))
    #(reset! running false)))

(defn start
  "The styles part, or nil for an app without :styles. Outside production it
   reads the tree and the code, refuses to start on any problem (every one
   reported), and writes the bundles; in development it then follows saves.
   In production it only needs the release's manifest to be there."
  [{:keys [settings app-settings environment]}]
  (when settings
    (settings/check-part! :styles settings)
    (let [browser (:browser app-settings)]
      (if (= :production environment)
        (do (when-not (manifest settings)
              (throw (ex-info (str "No styles manifest in " (:output-dir settings) ": run the release first")
                              {:type ::no-manifest})))
            {:settings settings :browser browser :state (atom nil)})
        (let [state (atom (build! settings browser))]
          {:settings settings
           :browser  browser
           :state    state
           :stop     (when (= :development environment) (watch! settings browser state))})))))

(defn stop [part]
  (when-let [stop (:stop part)] (stop)))

(defn with-watch-dir
  "The :browser settings with shadow-cljs told where the bundles are served
   from, so that in development a changed bundle is swapped into the open
   page without a reload."
  [{:keys [output-dir asset-path] :as styles} browser]
  (if (and styles (str/ends-with? output-dir asset-path))
    (update-in browser [:options :devtools :watch-dir]
               #(or % (subs output-dir 0 (- (count output-dir) (count asset-path)))))
    browser))
