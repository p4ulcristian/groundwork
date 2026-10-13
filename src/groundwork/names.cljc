(ns groundwork.names
  "One name for a piece of data, from the column to the browser.

     :post/created-at  <->  \"post__created_at\"
     :count            <->  \"count\"

   Both directions are exact: (= k (column->key (key->column k))) for every
   valid k. A part is lowercase letters and digits, words joined by single
   hyphens (keyword) or single underscores (column). Anything else throws
   ex-info {:type :groundwork.names/invalid}; nothing is guessed.

   How a thing looks has its name the same way. A CSS class is a keyword
   with the marker css. on its namespace, and the class keeps its hyphens:

     :css.job-card/title  <->  \"job-card__title\"

   The marker keeps a class apart from data, endpoints and events, which are
   namespaced keywords too; their names have no dots, so the two can't
   collide. A stylesheet's own variable is named after its namespace too:
   --job-card__group-color."
  (:require
   [clojure.string :as str]))

(def ^:private keyword-part #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
(def ^:private column-part #"[a-z][a-z0-9]*(?:_[a-z0-9]+)*")

(def max-length
  "Postgres cuts identifiers longer than this, silently."
  63)

(defn- invalid [message value]
  (throw (ex-info message {:type ::invalid :value value})))

(defn- snake [s] (str/replace s "-" "_"))
(defn- kebab [s] (str/replace s "_" "-"))

(defn- valid-keyword? [k]
  (and (keyword? k)
       (re-matches keyword-part (name k))
       (or (nil? (namespace k)) (re-matches keyword-part (namespace k)))))

(defn as-column
  "The column for a keyword, or nil when it isn't a valid name."
  [k]
  (when (valid-keyword? k)
    (let [column (if-let [entity (namespace k)]
                   (str (snake entity) "__" (snake (name k)))
                   (snake (name k)))]
      (when (<= (count column) max-length)
        column))))

(defn key->column
  ":post/created-at -> \"post__created_at\", :count -> \"count\"."
  [k]
  (or (as-column k)
      (invalid (str "Not a valid name, or longer than " max-length " characters: "
                    (pr-str k))
               k)))

(defn as-key
  "The keyword for a column name, or nil when it isn't one."
  [s]
  (when (string? s)
    (if-let [i (str/index-of s "__")]
      (let [entity (subs s 0 i)
            field  (subs s (+ i 2))]
        (when (and (re-matches column-part entity) (re-matches column-part field))
          (keyword (kebab entity) (kebab field))))
      (when (re-matches column-part s)
        (keyword (kebab s))))))

(defn column->key
  "\"post__created_at\" -> :post/created-at, \"count\" -> :count."
  [s]
  (or (as-key s)
      (invalid (str "Not a valid column name: " (pr-str s)) s)))

;; =============================================================================
;; Classes
;; =============================================================================

(def class-marker
  "What a class keyword's namespace starts with."
  "css.")

(defn class-key?
  "Whether a keyword says it is a class: :css.job-card/title."
  [k]
  (boolean (and (keyword? k)
                (when-let [n (namespace k)] (str/starts-with? n class-marker)))))

(defn as-class
  "The class name for a class keyword, or nil when it isn't a valid one."
  [k]
  (when (class-key? k)
    (let [block (subs (namespace k) (count class-marker))]
      (when (and (re-matches keyword-part block) (re-matches keyword-part (name k)))
        (str block "__" (name k))))))

(defn key->class
  ":css.job-card/title -> \"job-card__title\"."
  [k]
  (or (as-class k)
      (invalid (str "Not a class keyword like :css.job-card/title: " (pr-str k)) k)))

(defn as-class-key
  "The class keyword for a class name, or nil when it isn't block__name."
  [s]
  (when (string? s)
    (when-let [i (str/index-of s "__")]
      (let [block (subs s 0 i)
            part  (subs s (+ i 2))]
        (when (and (re-matches keyword-part block) (re-matches keyword-part part))
          (keyword (str class-marker block) part))))))

(defn class->key
  "\"job-card__title\" -> :css.job-card/title."
  [s]
  (or (as-class-key s)
      (invalid (str "Not a class name like job-card__title: " (pr-str s)) s)))

(defn class-namespace
  "The namespace a class name belongs to: \"job-card__title\" -> \"job-card\", or nil."
  [s]
  (some-> (as-class-key s) namespace (subs (count class-marker))))
