(ns groundwork.css
  "The door for classes in the browser. In a component a class is a keyword
   with the marker css., and Groundwork turns it into the class's name
   (groundwork.names) on the way to the page:

     [:div {:class :css.job-card/card}]                       ; class=\"job-card__card\"
     [:div {:class [:css.card/frame :css.job-card/card
                    (when selected? :css.job-card/selected)]}] ; nil and false are left out
     [button {:class :css.invoice-toolbar/save}]               ; one extra class for a component,
                                                               ; which puts it on its outer element

   A state that comes from data goes through a table written out, so every
   class it can be is named in the code, where the styles check sees it:

     (def stage-class {:coating :css.job-card/coating
                       :curing  :css.job-card/curing})
     [:div {:class [:css.job-card/card (css/lookup stage-class (:job/stage job))]}]

   A value with no entry throws in development, instead of an element that
   quietly has no style.

   The check that judges is the build's (groundwork.styles): it reads the
   code and the stylesheets. As a backup, in development the page knows
   every class the stylesheets define, and a class keyword that isn't one is
   reported in the console, once, by name."
  (:require
   [clojure.string :as str]
   [groundwork.loader :as loader]
   [groundwork.names :as names]
   [reagent.impl.util :as util]))

(defonce ^:private known (atom nil))
(defonce ^:private module-styles (atom {}))
(defonce ^:private reported (atom #{}))

(defn start!
  "What the page shell said about styles: the bundle of each module (for one
   loaded on demand) and, in development, every class the stylesheets define."
  [{:keys [styles classes]}]
  (reset! module-styles (or styles {}))
  (reset! known (when (seq classes) (set classes))))

(defn- class-of [k]
  (if (names/class-key? k)
    (let [class-name (names/key->class k)]
      (when-let [classes @known]
        (when-not (or (contains? classes class-name) (contains? @reported class-name))
          (swap! reported conj class-name)
          (js/console.error (str k " is no class of any stylesheet (." class-name ")"))))
      class-name)
    (name k)))

(defn class-names
  "What goes into an element's class: a class keyword, text (a library's own
   class), or a collection of them, nested or not; nil and false are left
   out. Returns the text, or nil when nothing is left."
  [x]
  (cond
    (or (nil? x) (false? x)) nil
    (string? x)              (when-not (str/blank? x) x)
    (keyword? x)             (class-of x)
    (symbol? x)              (name x)
    (coll? x)                (let [parts (keep class-names x)]
                               (when (seq parts) (str/join " " parts)))
    :else                    (str x)))

(defn lookup
  "The class for a value that comes from data, from a table written out in
   the code: (lookup {:coating :css.job-card/coating} stage). A value the
   table doesn't have throws in development and gives nil otherwise."
  [table value]
  (if (contains? table value)
    (get table value)
    (when ^boolean goog.DEBUG
      (throw (ex-info (str "No class for " (pr-str value) "; the table has " (pr-str (vec (keys table))))
                      {:type ::no-class :value value :table table})))))

(defn- reagent-class-names
  "What Reagent makes of an element's :class, with class keywords
   understood: everything else as Reagent itself does it."
  [class]
  (cond
    (coll? class)    (class-names class)
    (keyword? class) (class-of class)
    (symbol? class)  (name class)
    :else            class))

;; Every element's :class goes through here, whichever Reagent compiler the
;; app renders with (its default, one with function components, one handed
;; to a render by hand): Reagent turns :class into text in one function, and
;; this is that function from now on. A class keyword can't come out as its
;; bare name because some render went another way.
(set! (.-cljs$core$IFn$_invoke$arity$1 util/class-names) reagent-class-names)

(defn- linked?
  "Whether the page links a stylesheet already, whatever was added to its
   address since (the development build swaps a saved one in under ?r=…)."
  [href]
  (let [path (.-pathname (js/URL. href js/document.baseURI))]
    (boolean (some #(= path (.-pathname (js/URL. (.-href %))))
                   (array-seq (js/document.querySelectorAll "link[rel=\"stylesheet\"]"))))))

(defn ensure-module!
  "The stylesheets of a module loaded on demand, in the page before its
   screen shows: a promise. Nothing to do for a module the page loaded
   itself, or an app without styles."
  [module]
  (let [href (when module (get @module-styles (name module)))]
    (if (or (nil? href)
            (not (exists? js/document))
            (linked? href))
      (js/Promise.resolve nil)
      (js/Promise.
       (fn [resolve _]
         (let [link (js/document.createElement "link")]
           (set! (.-rel link) "stylesheet")
           (set! (.-href link) href)
           (set! (.-onload link) #(resolve nil))
           ;; a screen without its styles beats no screen
           (set! (.-onerror link) #(resolve nil))
           (.appendChild js/document.head link)))))))

(defn ensure-loaded!
  "The stylesheets of every module that is in the page by now, however its
   code got there: a promise. Code loaded on demand calls this before its
   screen shows, so no route has to say which module its screen lives in."
  []
  (js/Promise.all (to-array (for [module (keys @module-styles) :when (loader/loaded? module)]
                              (ensure-module! module)))))
