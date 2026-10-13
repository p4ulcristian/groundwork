(ns groundwork.styles-test
  "The styles check, on small trees written for each case: what is allowed
   passes, and each kind of mistake is reported with its file and line."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [example.hello.routes :as routes]
   [example.hello.settings :as example]
   [groundwork.core :as gw]
   [groundwork.repl :as repl]
   [groundwork.styles :as styles]
   [groundwork.test-support :refer [app-settings]]
   [groundwork.web :as web])
  (:import
   (java.io File)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

;; =============================================================================
;; A small app that is right
;; =============================================================================

(def ^:private good
  {"styles/base/tokens.css"
   ":root { --ink: #222; --space: 8px; }\na { color: var(--ink); }\n@font-face { font-family: X; src: url(\"x;{}.woff\"); }\n"

   "styles/looks/card.css"
   (str ".card__frame { padding: var(--card__padding, var(--space)); color: var(--ink);\n"
        "  &:hover { color: red; }\n"
        "  .card__title { font-weight: 600; }\n"
        "  @media (min-width: 640px) { padding: 12px; }\n"
        "}\n"
        "/* a state the card offers */\n"
        ".card__compact { --card__padding: 4px; }\n"
        "@container side (min-width: 300px) { .card__frame { margin: 0; } }\n"
        "@keyframes card-in { from { opacity: 0; } to { opacity: 1; } }\n")

   "styles/looks/badge.css"
   ".badge__badge { color: var(--ink); }\n.badge__warning { color: orange; }\n"

   "styles/areas/flex/job-card.css"
   (str ".job-card__card { border-color: var(--job-card__group-color); --card__padding: 6px; }\n"
        ".job-card__card:has(.badge__warning) { border-color: red; }\n"
        ".card__frame .job-card__title, :is(.card__frame, .card__compact) > .job-card__title { font-weight: 700; }\n"
        ".job-card__card.card__frame { margin: 0; }\n"
        "@supports (display: grid) { @layer looks { .job-card__card:not(.card__compact) { display: grid; } } }\n")

   "styles/vendor/calendar.vendor.css"
   ".vc-day { color: blue }\ndiv.whatever > span { margin: 0 }\n"

   "styles/vendor/calendar.css"
   ".calendar__root .vc-day { color: var(--ink); }\n.calendar__root { display: block;\n  .vc-week { margin: 0; }\n}\n"

   "src/app/board.cljs"
   (str "(ns app.board (:require [app.card :as card]))\n"
        "(def stage-class {:coating :css.job-card/card})\n"
        "(defn view [job]\n"
        "  [:div#board {:class [:css.job-card/card :css.card/compact] :style {:--job-card__group-color (:color job)}}\n"
        "   [:span {:class :css.job-card/title}]\n"
        "   [:b {:class [:css.badge/badge :css.badge/warning]}]\n"
        "   [:i {:class [:css.calendar/root \"vc-day\"]}] [:u {:class \"vc-week\"}]])\n")

   "src/app/card.cljs"
   "(ns app.card)\n(defn card [] [:div {:class :css.card/frame} [:b {:class :css.card/title}]])\n"})

(defn- write-app!
  "The files under a fresh folder, and the :styles settings for them."
  [files]
  (let [root (str (Files/createTempDirectory "groundwork-styles" (make-array FileAttribute 0)))]
    (doseq [[path text] files :when text]
      (io/make-parents (io/file root path))
      (spit (io/file root path) text))
    {:dir        (str root "/styles")
     :sources    [(str root "/src")]
     :base       ["base/tokens.css"]
     :vendor     [{:file "vendor/calendar.vendor.css" :prefixes ["vc-"] :wrapper "calendar"}]
     :output-dir (str root "/out/css")
     :asset-path "/css"}))

(defn- delete! [{:keys [dir]}]
  (doseq [^File f (reverse (file-seq (.getParentFile (io/file dir))))] (.delete f)))

(defn- found
  "The problems of the good app with some files changed (nil removes one),
   each as \"path:line message\" with paths from the app's folder."
  ([changes] (found changes identity))
  ([changes settings-fn]
   (let [styles (settings-fn (write-app! (merge good changes)))
         root   (str (.getParent (io/file (:dir styles))) "/")]
     (try
       (mapv (fn [{:keys [file line message]}] (str (str/replace file root "") ":" line " " message))
             (styles/problems styles))
       (finally (delete! styles))))))

(defn- adding
  "The good file with more text after it."
  [path more]
  {path (str (good path) more)})

(defn- one? [problems re]
  (= 1 (count (filter #(re-find re %) problems))))

;; =============================================================================
;; Reading CSS
;; =============================================================================

(deftest css-is-read-with-its-nesting
  (let [[rule keyframes] (styles/parse (str ".a__b { color: red; /* } */ &:hover { color: blue; }\n"
                                            "  @media (min-width: 1px) { .a__c { x: url(\"}{;\"); } } }\n"
                                            "@keyframes k { from { opacity: 0 } }"))]
    (is (= ".a__b" (:selector rule)))
    (is (= [:declaration :rule :at] (map :kind (:children rule))))
    (is (= "&:hover" (:selector (second (:children rule)))))
    (is (= 2 (:line (nth (:children rule) 2))) "a line is the line in the file")
    (is (= ".a__c" (-> rule :children (nth 2) :children first :selector)))
    (is (= "url(\"}{;\")" (-> rule :children (nth 2) :children first :children first :value))
        "braces and semicolons inside a string are text")
    (is (= "keyframes" (:name keyframes))))
  (testing "a selector, down to what it styles and what it only looks at"
    (let [[cx] (styles/selectors ".x__a:has(.y__b) > .x__c.z__d[data-open]:not(.q__r)::before")]
      (is (= [["x__a"] ["x__c" "z__d"]] (map :classes cx)))
      (is (= ["has"] (map :name (:fns (first cx)))))
      (is (= ["data-open"] (:attrs (peek cx)))))
    (is (= 3 (count (styles/selectors ".a__b, :is(.c__d, .e__f) .a__g , .a__h")))
        "a comma inside :is() doesn't split the list")))

;; =============================================================================
;; The check
;; =============================================================================

(deftest a-right-app-has-no-problems
  (is (= [] (found {}))))

(deftest classes-in-code-and-stylesheets-match
  (testing "a class keyword no stylesheet defines"
    (let [problems (found (adding "src/app/card.cljs"
                                  "(def more [:css.card/ghost :css.nowhere/thing :css.card/Wrong])\n"))]
      (is (one? problems #"src/app/card.cljs:3 :css.card/ghost: card's stylesheet has no \.card__ghost"))
      (is (one? problems #":css.nowhere/thing: there is no stylesheet for nowhere"))
      (is (one? problems #":css.card/Wrong isn't a class keyword"))
      (is (= 3 (count problems)))))
  (testing "a class no code names is dead, and says where it is"
    (is (= ["styles/looks/card.css:10 .card__leftover is named by no code (:css.card/leftover)"]
           (found (adding "styles/looks/card.css" ".card__leftover { color: red; }\n")))))
  (testing "a keyword in a comment is no use"
    (is (one? (found {"src/app/card.cljs" "(ns app.card)\n(defn card [] [:b {:class :css.card/title}]) ;; :css.card/frame\n"})
              #"\.card__frame is named by no code")))
  (testing "a class typed as text is refused; an id alone, and a library's own class, are fine"
    (let [problems (found (adding "src/app/card.cljs"
                                  "(defn x [] [:div.plain#main {:class \"one two\"} [:p#only-an-id] [:i {:class \"vc-day\"}]])\n"))]
      (is (one? problems #"card.cljs:3 the class \.plain#main is typed as text"))
      (is (one? problems #"card.cljs:3 the class \"one two\" is typed as text"))
      (is (= 2 (count problems))))))

(deftest a-stylesheet-paints-only-its-own
  (testing "styling another namespace's element"
    (is (= ["styles/areas/flex/job-card.css:6 \".job-card__card .badge__badge\" styles an element that carries no .job-card__… class: a stylesheet paints only its own; change another look through a state or a variable it offers"]
           (found (adding "styles/areas/flex/job-card.css" ".job-card__card .badge__badge { color: red; }\n")))))
  (testing "a class in the wrong stylesheet: said where it is, where it is missing, and where it is used"
    (is (= ["src/app/card.cljs:3 :css.card/ribbon: card's stylesheet has no .card__ribbon"
            "styles/areas/flex/job-card.css:6 \".card__ribbon\" styles an element that carries no .job-card__… class: a stylesheet paints only its own; change another look through a state or a variable it offers"
            "styles/areas/flex/job-card.css:6 .card__ribbon is mentioned here, but card's stylesheet doesn't define it"]
           (found (merge (adding "styles/areas/flex/job-card.css" ".card__ribbon { color: red; }\n")
                         (adding "src/app/card.cljs" "(def ribbon :css.card/ribbon)\n"))))))
  (testing "nested, too: the element styled is the inner one"
    (is (one? (found (adding "styles/areas/flex/job-card.css" ".job-card__card {\n  .card__title { color: red; }\n}\n"))
              #"job-card.css:7 \"\.card__title\" styles an element that carries no")))
  (testing "an :is() with another's class among its alternatives"
    (is (one? (found (adding "styles/areas/flex/job-card.css" ":is(.job-card__card, .card__frame) { margin: 1px; }\n"))
              #"carries no \.job-card__… class")))
  (testing "looking at others to decide is fine: an ancestor, :has(), :not(), a sibling, & in a context"
    (is (= [] (found (adding "styles/areas/flex/job-card.css"
                             (str ".badge__badge + .job-card__title { margin: 0; }\n"
                                  ".job-card__card { .card__frame & { margin: 0; } &.card__compact { margin: 1px; } }\n"
                                  "@media (min-width: 1px) { .card__frame:hover .job-card__card:not(.badge__warning) { margin: 2px; } }\n"))))))
  (testing "a rule with nothing of its own to say isn't checked: only its nested rules are"
    (is (= [] (found (adding "styles/areas/flex/job-card.css" ".card__frame { .job-card__title { margin: 3px; } }\n")))))
  (testing "styling globally outside base"
    (let [problems (found (adding "styles/looks/card.css" "a, .card__frame { color: red; }\n:root { --ink: blue; }\n* { margin: 0; }\n"))]
      (is (one? problems #"card.css:10 \"a, \.card__frame\" styles globally; only base may"))
      (is (one? problems #"card.css:11 \":root\" styles globally"))
      (is (one? problems #"card.css:12 \"\*\" styles globally"))))
  (testing "a class that isn't block__name"
    (is (one? (found (adding "styles/looks/card.css" ".card__frame .loose { color: red; }\n"))
              #"card.css:10 \.loose isn't a class like card__name")))
  (testing "mentioning a class nobody defines"
    (let [problems (found (adding "styles/areas/flex/job-card.css"
                                  ".job-card__card:has(.badge__ghost) { margin: 0; }\n.nope__x .job-card__card { margin: 0; }\n"))]
      (is (one? problems #"job-card.css:6 \.badge__ghost is mentioned here, but badge's stylesheet doesn't define it"))
      (is (one? problems #"job-card.css:7 \.nope__x is mentioned here, but there is no stylesheet for nope")))))

(deftest base-is-the-only-global-place-and-has-no-classes
  (is (= ["styles/base/tokens.css:4 .card__frame: base has no classes; a class lives in its namespace's stylesheet"]
         (found (adding "styles/base/tokens.css" "body .card__frame { margin: 0; }\n"))))
  (testing "a base file is listed, in order; one that isn't, or is missing, is a problem"
    (is (one? (found {"styles/base/reset.css" "body { margin: 0; }\n"})
              #"styles/base/reset.css:0 is in base/ but not in the settings' :base list"))
    (is (= [] (found {"styles/base/reset.css" "body { margin: 0; }\n"} #(update % :base conj "base/reset.css"))))
    (is (one? (found {} #(update % :base conj "base/gone.css")) #"base/gone.css:0 is in the settings but not in")))
  (testing "a stylesheet outside the tree"
    (is (one? (found {"styles/extra.css" "a { color: red; }\n"}) #"styles/extra.css:0 is outside the tree"))))

(deftest every-variable-is-defined
  (testing "a token is defined in base"
    (is (= ["styles/looks/badge.css:3 --brand is defined nowhere: a token is defined in base"]
           (found (adding "styles/looks/badge.css" ".badge__badge { background: var(--brand); }\n")))))
  (testing "a namespace's own variable is read only in its own stylesheet"
    (is (one? (found (adding "styles/looks/badge.css" ".badge__badge { padding: var(--card__padding, 1px); }\n"))
              #"badge.css:3 --card__padding is card's own variable: only card's stylesheet reads it")))
  (testing "read with no fallback, it has to be set somewhere: a stylesheet, or the code"
    (is (one? (found (adding "styles/looks/badge.css" ".badge__badge { margin: var(--badge__gap); }\n"))
              #"badge.css:3 --badge__gap is read but set nowhere, and has no fallback"))
    (is (= [] (found (merge (adding "styles/looks/badge.css" ".badge__badge { margin: var(--badge__gap); }\n")
                            (adding "styles/areas/flex/job-card.css" ".job-card__card { --badge__gap: 2px; }\n"))))))
  (testing "set, but its stylesheet never reads it"
    (is (= ["styles/areas/flex/job-card.css:6 --badge__tone is set, but badge's stylesheet never reads it"]
           (found (adding "styles/areas/flex/job-card.css" ".job-card__card { --badge__tone: red; }\n"))))
    (is (one? (found (adding "styles/areas/flex/job-card.css" ".job-card__card { --ghost__x: red; }\n"))
              #"--ghost__x: there is no stylesheet for ghost")))
  (testing "@property declares one"
    (is (= [] (found (adding "styles/looks/badge.css"
                             "@property --badge__angle { syntax: '<angle>'; inherits: false; initial-value: 0deg; }\n.badge__badge { rotate: var(--badge__angle); }\n"))))))

(deftest a-library-is-styled-only-by-its-wrapper
  (testing "its classes anywhere else"
    (is (= ["styles/areas/flex/job-card.css:6 .vc-day is a class of the library vendor/calendar.vendor.css; only its wrapper, calendar, may style it"]
           (found (adding "styles/areas/flex/job-card.css" ".job-card__card .vc-day { margin: 0; }\n")))))
  (testing "the wrapper, but outside its own element"
    (let [problems (found (adding "styles/vendor/calendar.css" ".vc-day { margin: 0; }\n"))]
      (is (one? problems #"calendar.css:5 \"\.vc-day\" styles the library outside calendar's own element"))))
  (testing "the library's own file is never checked, and its wrapper has to be there"
    (is (one? (found {"styles/vendor/calendar.css" nil
                      "src/app/board.cljs" (str/replace (good "src/app/board.cljs") ":css.calendar/root " "")})
              #"vendor/calendar.vendor.css:0 its wrapper's stylesheet, vendor/calendar.css, isn't there"))))

(deftest what-no-stylesheet-may-do
  (is (one? (found (adding "styles/looks/card.css" ".card__frame[data-testid=\"x\"] { color: red; }\n"))
            #"card.css:10 selects by data-testid: that is a handle for tests"))
  (is (one? (found {"styles/looks/card.css" (str "@import \"badge.css\";\n" (good "styles/looks/card.css"))})
            #"card.css:1 @import: a stylesheet doesn't load another")))

(deftest one-namespace-is-one-stylesheet
  (testing "two files of one name"
    (is (one? (found {"styles/looks/job-card.css" ".job-card__card { margin: 1px; }\n"})
              #"styles/looks/job-card.css:0 is a second job-card.css \(the other is areas/flex/job-card.css\)")))
  (testing "a large one in parts: <namespace>.<part>.css, in a folder of their own or not"
    (is (= [] (found (merge {"styles/looks/card.states.css" ".card__open { color: red; }\n"}
                            (adding "src/app/card.cljs" "(def open :css.card/open)\n")))))
    (is (= [] (found {"styles/areas/flex/job-card.css"                 nil
                      "styles/areas/flex/job-card/job-card.css"        (good "styles/areas/flex/job-card.css")
                      "styles/areas/flex/job-card/job-card.states.css" ".job-card__card:hover { margin: 4px; }\n"}))))
  (testing "its parts sit together"
    (is (= ["styles/looks/job-card.states.css:0 is a part of job-card, whose other files are in areas/flex/: one namespace's files sit together"]
           (found {"styles/looks/job-card.states.css" ".job-card__card:hover { margin: 4px; }\n"}))))
  (testing "a file's name is its namespace"
    (is (one? (found {"styles/looks/Big_Card.css" "a { color: red; }\n"})
              #"styles/looks/Big_Card.css:0 isn't named like a namespace"))))

;; =============================================================================
;; Bundles
;; =============================================================================

(def ^:private two-modules
  {"styles/areas/admin.css" ".admin__panel { color: var(--ink); }\n"
   "src/app/admin.cljs"     "(ns app.admin (:require [app.card :as card]))\n(defn view [] [:div {:class [:css.admin/panel :css.card/frame]}])\n"
   "src/app/lonely.cljs"    "(ns app.lonely)\n(def unused [:css.badge/badge])\n"})

(def ^:private browser
  {:build :app :entry 'app.board/view :output-dir "out/js" :asset-path "/js" :watch? false
   :modules {:main  {:init-fn 'app.board/view}
             :admin {:entries ['app.admin] :depends-on #{:main}}}})

(deftest each-module-gets-the-stylesheets-its-code-uses
  (let [styles (write-app! (merge good two-modules))]
    (try
      (let [read (styles/check! styles)
            plan (styles/plan styles browser read)]
        (testing "base and the libraries' files first, in the settings' order"
          (is (= ["base/tokens.css" "vendor/calendar.vendor.css"] (:base plan))))
        (testing "a namespace two modules use goes to the one they share; the tree's order inside a bundle"
          (is (= {:main  ["vendor/calendar.css" "looks/badge.css" "looks/card.css" "areas/flex/job-card.css"]
                  :admin ["areas/admin.css"]}
                 (:modules plan)))
          (is (= #{:admin :main} (get-in plan [:users "card"]))))
        (testing "plain names, and the files as written"
          (is (= {:base "base.css" :modules {:admin "admin.css" :main "main.css"}}
                 (styles/write-bundles! styles plan false)))
          (let [main (slurp (io/file (:output-dir styles) "main.css"))]
            (is (str/includes? main (good "styles/looks/card.css")))
            (is (< (str/index-of main "/* looks/card.css */") (str/index-of main "/* areas/flex/job-card.css */")))))
        (testing "what a page loads: base, then its module's chain"
          (is (= ["/css/base.css" "/css/main.css" "/css/admin.css"] (styles/links styles browser :admin)))
          (is (= ["/css/base.css" "/css/main.css"] (styles/links styles browser nil)))
          (is (= {"main" "/css/main.css" "admin" "/css/admin.css"} (styles/module-links styles))))
        (testing "a release fingerprints them, and the manifest says their names"
          (let [manifest (styles/release! {:styles styles :browser browser})]
            (is (re-matches #"base\.[0-9A-F]{8}\.css" (:base manifest)))
            (is (re-matches #"admin\.[0-9A-F]{8}\.css" (get-in manifest [:modules :admin])))
            (is (= manifest (styles/manifest styles)))
            (is (re-matches #"/css/admin\.[0-9A-F]{8}\.css" (last (styles/links styles browser :admin))))))
        (testing "the questions: a class, and a page"
          (let [read (assoc read :plan plan)
                card (styles/explain-class read :css.card/frame)]
            (is (= "card__frame" (:class card)))
            (is (= [(str (:dir styles) "/looks/card.css:1")] (:defined card)))
            (is (= 2 (count (:used card))))
            (is (every? #(re-find #"\.cljs:2$" %) (:used card)))
            (is (= [:main] (:bundles card)))
            (is (= [(str (:dir styles) "/areas/flex/job-card.css:3") (str (:dir styles) "/areas/flex/job-card.css:4")]
                   (:mentioned card)))
            (let [page (styles/page-loads read browser :admin)]
              (is (= [:main :admin] (map :module (:modules page))))
              (is (= [{:file    (str (:dir styles) "/areas/admin.css")
                       :used-by [(str (first (:sources styles)) "/app/admin.cljs")]}]
                     (:stylesheets (second (:modules page)))))))))
      (finally (delete! styles)))))

(deftest the-part-refuses-to-start-on-any-problem
  (let [styles (write-app! (merge good (adding "styles/looks/card.css" ".card__leftover { color: red; }\n.loose { x: y; }\n")))]
    (try
      (let [e (try (styles/start {:settings styles :app-settings {:browser browser} :environment :test})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ::styles/problems (:type (ex-data e))))
        (is (= 3 (count (:problems (ex-data e)))) "every problem at once")
        (is (str/starts-with? (ex-message e) "The styles have 3 problems:\n")))
      (finally (delete! styles))))
  (testing "an app without :styles has no part"
    (is (nil? (styles/start {:settings nil :app-settings {:browser browser} :environment :test}))))
  (testing "in production it wants the release's manifest, and reads no code"
    (let [styles (write-app! good)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run the release first"
                              (styles/start {:settings styles :app-settings {:browser browser} :environment :production})))
        (styles/release! {:styles styles :browser (dissoc browser :modules)})
        (is (some? (styles/start {:settings (assoc styles :sources ["/nowhere"]) :app-settings {:browser browser}
                                  :environment :production})))
        (finally (delete! styles))))))

(deftest a-save-in-development-rebuilds-the-bundles
  (let [styles (write-app! good)
        part   (styles/start {:settings styles :app-settings {:browser (dissoc browser :modules)} :environment :development})]
    (try
      (is (not (str/includes? (slurp (io/file (:output-dir styles) "main.css")) "margin: 9px")))
      (Thread/sleep 1100)
      (spit (io/file (:dir styles) "looks/badge.css") (str (good "styles/looks/badge.css") ".badge__badge { margin: 9px; }\n"))
      (is (loop [tries 40]
            (cond
              (str/includes? (slurp (io/file (:output-dir styles) "main.css")) "margin: 9px") true
              (zero? tries) false
              :else (do (Thread/sleep 100) (recur (dec tries)))))
          "the bundle on disk follows the stylesheet, and shadow-cljs swaps it into the open page")
      (is (= {:build :app :options {:devtools {:watch-dir (str/replace (:output-dir styles) #"/css$" "")}}}
             (styles/with-watch-dir styles {:build :app})))
      (finally
        (styles/stop part)
        (delete! styles)))))

;; =============================================================================
;; A whole app
;; =============================================================================

(def ^:private example-app
  (-> app-settings
      (assoc :browser {:build :example-styles :entry 'example.hello.browser/start
                       :output-dir "test/public/example-styles" :asset-path "/example-styles" :watch? false
                       :modules example/modules :loader? true})
      (assoc :styles (example/styles "example-styles-css"))
      (assoc-in [:web :routes] (conj (web/page-routes routes/routes)
                                     ["/print/reports" {:get web/page :access :public :page {:module :reports}}]))))

(defn- stylesheets-of
  "The stylesheets a page of the running app links, in order."
  [app path]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (gw/port app) path)))
                            (.header "Accept" "text/html") (.GET) (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    {:links (mapv second (re-seq #"<link rel=\"stylesheet\" href=\"([^\"]+)\"" (.body response)))
     :body  (.body response)}))

(deftest the-page-shell-loads-what-the-pages-code-uses
  (let [app (gw/start example-app)
        css (get-in example-app [:styles :output-dir])]
    (try
      (testing "base and the page's modules, before the app's own stylesheets"
        (is (= ["/example-styles-css/base.css" "/example-styles-css/main.css" "/app.css"]
               (:links (stylesheets-of app "/"))))
        (is (= ["/example-styles-css/base.css" "/example-styles-css/main.css" "/example-styles-css/reports.css" "/app.css"]
               (:links (stylesheets-of app "/print/reports")))
            "a page that starts the reports module"))
      (testing "the page tells the browser each module's bundle, for one loaded on demand"
        (is (str/includes? (:body (stylesheets-of app "/"))
                           "\"styles\":{\"main\":\"/example-styles-css/main.css\",\"reports\":\"/example-styles-css/reports.css\"}")))
      (testing "the bundles are files the app serves"
        (is (str/includes? (slurp (io/file css "reports.css")) ".reports__screen")))
      (testing "the REPL's questions"
        (is (= "No problems." (repl/styles-report app)))
        (is (= {:class     "reports__screen"
                :defined   ["test/example/styles/areas/reports.css:2"]
                :used      ["test/example/hello/reports.cljs:6"]
                :bundles   [:reports]
                :mentioned []}
               (repl/explain-class app :css.reports/screen)))
        (is (= [:main] (:bundles (repl/explain-class app :css.panel/frame)))
            "used by both modules: in the one they share")
        (let [by-address (repl/page-styles app "/print/reports")]
          (is (= "/print/reports" (:route by-address)))
          (is (= ["base/base.css"] (:base by-address)))
          (is (= [:main :reports] (map :module (:modules by-address))))
          (is (= [{:file "test/example/styles/areas/reports.css" :used-by ["test/example/hello/reports.cljs"]}]
                 (:stylesheets (second (:modules by-address)))))
          (is (= (dissoc by-address :route) (repl/page-styles app :reports))))
        (is (= [:main] (map :module (:modules (repl/page-styles app "/orders/7")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No route of the app serves /nowhere"
                              (repl/page-styles app "/nowhere"))))
      (finally
        (gw/stop app)
        (doseq [^File f (reverse (file-seq (io/file css)))] (.delete f))))))

(deftest an-app-without-styles-brings-its-own
  (doseq [settings [(dissoc app-settings :styles) (assoc app-settings :styles nil)]]
    (let [app (gw/start (assoc-in settings [:web :routes] [["/" {:get web/page :access :public}]]))]
      (try
        (is (= ["/app.css"] (:links (stylesheets-of app "/"))))
        (is (not (str/includes? (:body (stylesheets-of app "/")) "\"styles\"")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"This app has no :styles" (repl/styles-report app)))
        (finally (gw/stop app))))))
