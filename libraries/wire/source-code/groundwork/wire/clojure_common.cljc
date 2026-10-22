(ns groundwork.wire.clojure-common
  "How a value travels as text and comes back as what it was, the same rules
   on the JVM and in the browser. The door of groundwork.wire.

   JSON knows text, numbers, true/false, lists and maps. A value's shape
   (malli) says what each part is, and gives the types back on the way in.
   Keys like :post/title travel as \"post/title\". A date, a decimal and a
   number too big for JavaScript travel as text and come back as what they
   are: on the JVM java.time, BigDecimal and real UUIDs; in the browser an
   Instant and a Day (groundwork.wire.clojurescript.time), a decimal's text or
   big.js (groundwork.wire.clojurescript.big), and an id as text. Money
   travels as whole cents, an :int.

   The language's own parts are picked once, in the requires below; nothing
   else here differs between the two languages."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [malli.transform :as mt]
   #?(:clj  [groundwork.wire.clojure.json :as json]
      :cljs [groundwork.wire.clojurescript.json :as json])
   #?(:clj  [groundwork.wire.clojure.time :as time]
      :cljs [groundwork.wire.clojurescript.time :as time])
   #?(:clj  [groundwork.wire.clojure.types :as types]
      :cljs [groundwork.wire.clojurescript.types :as types])))

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

(defn schema
  "A shape as a malli schema, with this library's schemas."
  [shape]
  (m/schema shape {:registry registry}))

(defn decode
  "A value from JSON, given its shape: {:value …} when it fits, or
   {:errors …} (readable, by key) when it doesn't."
  [s value]
  (let [s       (schema s)
        decoded (m/decode s value transformer)]
    (if (m/validate s decoded)
      {:value decoded}
      {:errors (me/humanize (m/explain s decoded))})))

(defn encode
  "A value made ready for JSON, given its shape."
  [s value]
  (m/encode (schema s) value transformer))

(defn fits?
  "Whether a value fits its shape."
  [s value]
  (m/validate (schema s) value))

(defn explain
  "Why a value doesn't fit its shape, readable."
  [s value]
  (me/humanize (m/explain (schema s) value)))

(defn write-json
  "Data as JSON text; keyword keys keep their namespace: \"post/title\"."
  [x]
  (json/write-json x))

(defn read-json
  "JSON text as data; keys become keywords, \"post/title\" → :post/title."
  [s]
  (json/read-json s))
