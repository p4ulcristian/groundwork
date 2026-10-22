(ns groundwork.browser.wire.api
  "How shaped data travels as text and comes back as what it was, the same
   rules on the JVM and in the browser. The api of groundwork.browser.wire.

   Shaped data: data whose every value is what its shape (malli) names.
   JSON knows text, numbers, true/false, lists and maps, so on the way out
   the shape turns the rest into text, and on the way in turns it back.
   Keys like :post/title travel as \"post/title\". A date, a decimal and a
   number too big for JavaScript travel as text and come back as what they
   are: on the JVM java.time, BigDecimal and real UUIDs; in the browser an
   Instant and a Day (groundwork.browser.wire.time), a decimal's text or
   big.js (groundwork.browser.wire.big), and an id as text. Money
   travels as whole cents, an :int.

   The language's own parts are picked once, in the requires below; nothing
   else here differs between the two languages."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [malli.transform :as mt]
   [malli.util :as mu]
   [groundwork.browser.wire.json :as json]
   [groundwork.browser.wire.time :as time]
   [groundwork.browser.wire.types :as types]))

(def ^:private decimal-schema
  (m/-simple-schema {:type            :decimal
                     :pred            types/decimal-value?
                     :type-properties {:error/message "should be a decimal"
                                       :gen/schema    :int
                                       :gen/fmap      types/example-decimal}}))

(def ^:private registry
  ;; malli's own schemas, the language's :time/… schemas, :decimal, and in
  ;; the browser :uuid as an id's text
  (mr/composite-registry
   types/id-schemas
   (m/default-schemas)
   time/schemas
   {:decimal decimal-schema}))

(def ^:private transformer
  (apply mt/transformer
         (remove nil?
                 [(mt/json-transformer)
                  time/transformer
                  (mt/transformer {:name     :decimal
                                   :decoders {:decimal types/decimal-from-wire}
                                   :encoders {:decimal types/decimal-to-wire}})
                  (mt/transformer {:name     :big-integer
                                   :decoders {:int types/int-from-wire}
                                   :encoders {:int types/int-to-wire}})
                  types/id-transformer])))

(defn make-schema-from-shape
  "A shape made into a malli schema, with this library's schemas."
  [shape]
  (m/schema shape {:registry registry}))

(defn turn-json-data-into-shaped-data-or-errors
  "JSON data turned into shaped data: {:shaped-data …} when it fits the
   shape, or {:errors …} (readable, by key) when it doesn't."
  [shape json-data]
  (let [schema      (make-schema-from-shape shape)
        shaped-data (m/decode schema json-data transformer)]
    (if (m/validate schema shaped-data)
      {:shaped-data shaped-data}
      {:errors (me/humanize (m/explain schema shaped-data))})))

(defn turn-shaped-data-into-json-data
  "Shaped data turned into JSON data: ids, decimals and dates become text."
  [shape shaped-data]
  (m/encode (make-schema-from-shape shape) shaped-data transformer))

(defn does-data-fit-shape?
  "Whether data fits a shape."
  [shape data]
  (m/validate (make-schema-from-shape shape) data))

(defn find-errors-in-data-for-shape
  "Why data doesn't fit a shape, readable, by key; nil when it fits."
  [shape data]
  (me/humanize (m/explain (make-schema-from-shape shape) data)))

(defn turn-data-into-json-text
  "Data as JSON text; keyword keys keep their namespace: \"post/title\"."
  [data]
  (json/write-json data))

(defn turn-json-text-into-data
  "JSON text as data; keys become keywords, \"post/title\" → :post/title."
  [json-text]
  (json/read-json json-text))

(defn turn-shape-into-closed-shape
  "The same shape with every map closed: a key it doesn't name makes data
   not fit. For answers, so an extra column can't leave the server."
  [shape]
  (mu/closed-schema (make-schema-from-shape shape)))
