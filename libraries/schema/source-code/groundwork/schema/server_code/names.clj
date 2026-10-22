(ns groundwork.schema.server-code.names
  "A key's column, a table key's table, and the names schema gives what it
   makes, all lowercase, words joined by _.

     :order/shop-id  ->  \"order__shop_id\"
     :orders         ->  \"orders\"
     (make-identifier \"orders\" \"order__shop_id\" \"fkey\")  ->  \"orders_order__shop_id_fkey\""
  (:require
   [clojure.string :as str]))

(def ^:private word #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")

(defn- dashes-into-underscores [s]
  (str/replace s "-" "_"))

(defn turn-key-into-column-name
  ":order/shop-id -> \"order__shop_id\"; a key outside the rules throws."
  [k]
  (when-not (and (keyword? k)
                 (re-matches word (name k))
                 (or (nil? (namespace k)) (re-matches word (namespace k))))
    (throw (ex-info (str "Not a column key Groundwork can name: " (pr-str k))
                    {:type :groundwork.schema/bad-name :key k})))
  (if-let [entity (namespace k)]
    (str (dashes-into-underscores entity) "__" (dashes-into-underscores (name k)))
    (dashes-into-underscores (name k))))

(defn turn-table-key-into-table-name
  ":orders -> \"orders\"; a key outside the rules throws."
  [k]
  (when-not (and (keyword? k) (nil? (namespace k)) (re-matches word (name k)))
    (throw (ex-info (str "Not a table key Groundwork can name: " (pr-str k))
                    {:type :groundwork.schema/bad-name :key k})))
  (dashes-into-underscores (name k)))

(defn make-identifier
  "Parts joined by _. Postgres cuts names past 63 characters, so a longer
   one ends in a hash of the whole instead."
  [& parts]
  (let [joined (str/join "_" parts)]
    (if (<= (count joined) 63)
      joined
      (str (subs joined 0 54) "_" (format "%08x" (bit-and (hash joined) 0xffffffff))))))

(defn make-foreign-key-name [table-name column-name] (make-identifier table-name column-name "fkey"))
(defn make-enum-check-name [table-name column-name] (make-identifier table-name column-name "enum"))
(defn make-not-null-check-name [table-name column-name] (make-identifier table-name column-name "not_null"))
(defn make-index-name [table-name column-names] (apply make-identifier table-name (concat column-names ["idx"])))
