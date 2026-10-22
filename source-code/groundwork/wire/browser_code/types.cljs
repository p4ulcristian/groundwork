(ns groundwork.wire.browser-code.types
  "Decimals, whole numbers and ids in the browser. A decimal is its text,
   \"12.30\", or an exact value once use-exact-decimals! is given one
   (groundwork.wire.browser-code.big gives big.js). An id is its text."
  (:require
   [malli.core :as m]
   [malli.transform :as mt]))

(defonce ^:private exact
  ;; the exact decimal type, once one is given: {:decimal? :read :text}
  ;; (groundwork.wire.browser-code.big); nil keeps a decimal as its text
  (volatile! nil))

(defn use-exact-decimals!
  "How the browser holds a :decimal: {:decimal? pred, :read text → value,
   :text value → text}, or nil for its text. groundwork.wire.browser-code.big
   calls it with big.js when it loads."
  [kind]
  (vreset! exact kind))

(def ^:private decimal-text-pattern #"^-?\d+(\.\d+)?$")

(defn decimal-value?
  "Whether x is a decimal as the browser holds it."
  [x]
  (if-let [{:keys [decimal?]} @exact]
    (decimal? x)
    (and (string? x) (some? (re-matches decimal-text-pattern x)))))

(defn decimal
  "An exact decimal from its text, \"12.30\". With an exact value given (big.js)
   it stays exact, but trailing zeros aren't kept: it reads 12.3; without one,
   it is the text."
  [s]
  (if-let [{:keys [read]} @exact] (read s) s))

(defn decimal-text
  "An exact decimal as text."
  [d]
  (if-let [{:keys [decimal? text]} @exact] (if (decimal? d) (text d) (str d)) (str d)))

(defn decimal-from-wire [x]
  (let [{:keys [read]} @exact]
    (if (and read (string? x) (re-matches decimal-text-pattern x))
      (read x)
      x)))

(defn decimal-to-wire [x]
  (let [{:keys [decimal? text]} @exact]
    (if (and decimal? (decimal? x)) (text x) x)))

(defn example-decimal
  "A whole number as a decimal, for generated values (tests)."
  [n]
  (if-let [{:keys [read]} @exact] (read (str n)) (str n)))

(defn int-from-wire
  "Whole numbers stay as they came: one beyond JavaScript's safe range is text."
  [x]
  x)

(defn int-to-wire [x] x)

(def ^:private id-text-pattern
  #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(def id-text-schema
  "An id in the browser: text shaped like a UUID. Its type isn't :uuid, so
   malli's JSON transformer leaves the text alone."
  (m/-simple-schema {:type            :groundwork.wire/id-text
                     :pred            #(and (string? %) (some? (re-matches id-text-pattern %)))
                     :type-properties {:error/message "should be an id"}}))

(def id-schemas
  "In the browser :uuid is an id as text."
  {:uuid id-text-schema})

(def id-transformer
  "A UUID value given in the browser becomes its text, both ways."
  (let [text (fn [x] (if (uuid? x) (str x) x))]
    (mt/transformer {:name     :id-text
                     :decoders {:groundwork.wire/id-text text}
                     :encoders {:groundwork.wire/id-text text}})))
