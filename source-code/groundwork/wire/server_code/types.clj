(ns groundwork.wire.server-code.types
  "Decimals, whole numbers and ids on the JVM. A decimal is a BigDecimal and
   travels as its plain text, \"12.30\". A whole number beyond JavaScript's
   safe range travels as text. An id is a real UUID.")

(defn decimal-value?
  "Whether x is a decimal as the JVM holds it."
  [x]
  (decimal? x))

(defn decimal
  "An exact decimal from its text, \"12.30\"."
  [s]
  (BigDecimal. ^String s))

(defn decimal-text
  "An exact decimal as text."
  [d]
  (.toPlainString ^BigDecimal d))

(defn decimal-from-wire [x]
  (if (string? x)
    (try (BigDecimal. ^String x) (catch Exception _ x))
    x))

(defn decimal-to-wire [x]
  (if (decimal? x) (.toPlainString ^BigDecimal x) x))

(defn example-decimal
  "A whole number as a decimal, for generated values (tests)."
  [n]
  (BigDecimal. (str n ".00")))

(defn int-from-wire [x]
  (if (string? x) (or (parse-long x) x) x))

(defn int-to-wire [x]
  (if (and (integer? x) (> (abs x) 9007199254740991)) (str x) x))

(def id-schemas
  "On the JVM :uuid is malli's own: a real UUID."
  {})

(def id-transformer
  "On the JVM ids need no transformer of their own."
  nil)
