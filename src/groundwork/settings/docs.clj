(ns groundwork.settings.docs
  "The settings' schemas read back as documentation: the README's settings
   reference, the example settings file (docs/example-settings.edn) and the
   REPL's explain-setting. Every key's description and example come from
   groundwork.settings."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [groundwork.settings :as settings]
   [malli.core :as m]))

(defn- props [s] (m/properties (m/schema s)))

(defn- map-schema
  "The :map inside a schema, through :maybe and :and, or nil."
  [s]
  (let [s (m/schema s)]
    (case (m/type s)
      :map   s
      :maybe (map-schema (first (m/children s)))
      :and   (some map-schema (m/children s))
      nil)))

(defn- type-text [s]
  (let [s (m/schema s)
        p (m/properties s)]
    (or (:doc/type p)
        (case (m/type s)
          :map     "map"
          :maybe   (str (type-text (first (m/children s))) " or nil")
          :and     (type-text (first (m/children s)))
          :enum    (str/join ", " (map pr-str (m/children s)))
          :string  "text"
          :boolean "true or false"
          :int     "whole number"
          :keyword "keyword"
          :vector  "list"
          "value"))))

(defn- rows
  "Every documented key under a schema: {:path :type :description :example}."
  [s path]
  (when-let [ms (map-schema s)]
    (mapcat (fn [[k p child]]
              (let [here   (conj path k)
                    nested (map-schema child)
                    values (let [c (m/schema child)]
                             (when (#{:map-of :vector :sequential} (m/type c))
                               (map-schema (last (m/children c)))))]
                (cons {:path here :type (type-text child) :description (:description p) :example (:example p)
                       :optional? (boolean (:optional p))}
                      (cond
                        nested (rows nested here)
                        values (rows values (conj here (or (:doc/key (props (last (m/children (m/schema child))))) "…")))))))
            (m/children ms))))

(defn all
  "Every documented key of the settings, in order."
  []
  (mapcat (fn [[part s]]
            (cons {:path [part] :type (type-text s) :description (settings/part-descriptions part)
                   :example (when-not (map-schema s) (:example (props s)))}
                  (rows s [part])))
          settings/parts))

(defn explain
  "What goes at a path of the settings: (explain [:sessions :visitor-hours])."
  [path]
  (or (some #(when (= path (:path %)) %) (all))
      (throw (ex-info (str "No setting " (pr-str path)) {:type ::no-setting :path path}))))

(defn- cell [s] (str/replace (str s) "|" "\\|"))

(defn- table [entries]
  (str "| Key | Type | What it is | Example |\n"
       "|-----|------|------------|---------|\n"
       (apply str (for [{:keys [path type description example optional?]} entries]
                    (str "| `" (str/join " " (map #(if (keyword? %) (pr-str %) %) path)) "`"
                         (when optional? " (optional)")
                         " | " (cell type) " | " (cell description) " | "
                         (if (some? example) (str "`" (cell (settings/shown example)) "`") "") " |\n")))))

(defn reference
  "The README's settings reference, as Markdown."
  []
  (str "Generated from `groundwork.settings`; `(groundwork.repl/explain-setting [:sessions :visitor-hours])`\n"
       "answers the same in the REPL, and `docs/example-settings.edn` holds every key with an example.\n\n"
       (apply str
              (for [[part s] settings/parts]
                (str "#### `" (pr-str part) "`: " (settings/part-descriptions part) "\n\n"
                     (if-let [entries (seq (rows s [part]))]
                       (table entries)
                       (str "`" (type-text s) "`\n"))
                     "\n")))
       "#### A route's data (in `:web :routes`)\n\n" (table (rows settings/route ["<path>"])) "\n"
       "#### An endpoint's entry (in `:parquery :endpoints`)\n\n" (table (rows settings/endpoint ["<endpoint name>"]))))

(defn- pretty [v indent]
  (let [lines (str/split-lines (str/trimr (with-out-str (pprint/pprint v))))]
    (str/join (str "\n" (apply str (repeat indent " "))) lines)))

(defn- example-map [s indent]
  (let [pad (apply str (repeat indent " "))]
    (str "{"
         (str/join (str "\n\n" pad " ")
                   (for [[k p child] (m/children (map-schema s))]
                     (let [nested (map-schema child)
                           key-s  (pr-str k)]
                       (str ";; " (:description p) (when (:optional p) " (optional)") "\n" pad " "
                            (if (and nested (not (some? (:example p))) (not= :maybe (m/type (m/schema child))))
                              (str key-s "\n" pad " " (example-map nested (inc indent)))
                              (if (and nested (= :maybe (m/type (m/schema child))))
                                (str key-s " " (pretty (:example p) (+ indent 2 (count key-s))))
                                (if nested
                                  (str key-s "\n" pad " " (example-map nested (inc indent)))
                                  (str key-s " " (pretty (:example p) (+ indent 2 (count key-s)))))))))))
         "}")))

(defn example
  "The example settings file: every key, an example value and a comment."
  []
  (str ";; Every setting gw/start takes, with an example and what it is.\n"
       ";; Generated from groundwork.settings; don't edit by hand.\n"
       "{"
       (str/join "\n\n "
                 (for [[part s] settings/parts]
                   (let [key-s (pr-str part)]
                     (str ";; " (settings/part-descriptions part) "\n "
                          (cond
                            (map-schema s)     (str key-s "\n " (example-map s 1))
                            :else              (str key-s " " (pr-str (first (m/children (m/schema s))))))))))
       "}\n"))
