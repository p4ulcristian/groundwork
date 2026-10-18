(ns groundwork.postgres.names
  "One name for a piece of data, from the column to the browser.

     :post/created-at  <->  \"post__created_at\"
     :count            <->  \"count\"

   Both directions are exact: (= k (column->key (key->column k))) for every
   valid k. A part is lowercase letters and digits, words joined by single
   hyphens (keyword) or single underscores (column). Anything else throws
   ex-info {:type :groundwork.postgres.names/invalid}; nothing is guessed."
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
