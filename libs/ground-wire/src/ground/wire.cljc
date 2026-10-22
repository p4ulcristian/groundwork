(ns ground.wire
  "How a value travels as text and comes back as what it was, the same
   rules on the JVM and in the browser.

   JSON knows text, numbers, true/false, lists and maps. A value's shape
   (malli) says what each part is, and gives the types back on the way in.
   Keys like :post/title travel as \"post/title\". A date, a decimal and a
   number too big for JavaScript travel as text and come back as what they
   are. A moment (:time/instant) and a calendar day (:time/local-date):
   java.time on the JVM (and every other :time/… schema of malli's); in
   the browser this library's own Instant and Day, built on Closure's
   goog.date.UtcDateTime and goog.date.Date, read by the small decoder
   below. A decimal (:decimal): BigDecimal on the JVM; in the browser its
   text, \"12.30\", or an exact value once use-exact-decimals! is given one
   (ground.wire.decimal gives big.js). Money travels as whole cents, an
   :int.

   An id (:uuid) travels as text and is text in the browser: a name tag,
   compared and passed along. On the JVM it is a real UUID, and every id
   that arrives is checked strictly against its shape. In the browser the
   shape checks that the text is an id's; a UUID value given there goes
   out as its text."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [malli.transform :as mt]
   #?(:clj [malli.experimental.time :as time])
   #?(:clj [malli.experimental.time.transform :as time-transform])
   #?(:clj [jsonista.core :as json]))
  #?(:cljs (:import (goog.date Date UtcDateTime))))

;; =============================================================================
;; Shapes
;; =============================================================================

#?(:cljs
   (defonce ^:private exact
     ;; the exact decimal type, when the app has it on: {:decimal? :read :text}
     ;; (ground.wire.decimal); nil keeps a decimal as its text
     (volatile! nil)))

#?(:cljs
   (defn use-exact-decimals!
     "How the browser holds a :decimal: {:decimal? pred, :read text → value,
      :text value → text}, or nil for its text. ground.wire.decimal
      calls it with big.js when it loads."
     [kind]
     (vreset! exact kind)))

#?(:cljs
   (def ^:private decimal-text-pattern #"^-?\d+(\.\d+)?$"))

(def decimal-schema
  "An exact decimal: BigDecimal on the JVM; in the browser the exact value
   use-exact-decimals! was given, else its text."
  (m/-simple-schema {:type            :decimal
                     :pred            #?(:clj decimal?
                                         :cljs #(if-let [{:keys [decimal?]} @exact]
                                                  (decimal? %)
                                                  (and (string? %) (some? (re-matches decimal-text-pattern %)))))
                     :type-properties {:error/message "should be a decimal"
                                       ;; for generated values (tests): a whole number, as a decimal
                                       :gen/schema :int
                                       :gen/fmap   (fn [n] #?(:clj (BigDecimal. (str n ".00"))
                                                              :cljs (if-let [{:keys [read]} @exact] (read (str n)) (str n))))}}))

;; =============================================================================
;; Times in the browser: Instant and Day
;; =============================================================================
;;
;; A moment is an Instant, a goog.date.UtcDateTime; a calendar day is a Day,
;; a goog.date.Date (no time, no zone: its year, month and day are what the
;; wire said, wherever the browser is). Both print, and go to JSON, as the
;; ISO text they came as: \"2026-10-12T10:00:00Z\", \"2026-11-15\" (an
;; instant's fraction in 3, 6 or 9 digits, as java.time and js-joda print
;; it). JavaScript keeps milliseconds; an Instant keeps the finer part of its
;; second too, so a moment goes back to the server exactly as it came. They
;; compare with = and compare, and goog.date's methods work on them (a copy
;; from clone stays an Instant or a Day).

#?(:cljs
   (defn- pad [n width]
     (.padStart (str n) width "0")))

#?(:cljs
   (defn- fraction
     "A second's fraction in nanoseconds, as java.time prints it: none, or
      3, 6 or 9 digits."
     [nanos]
     (cond
       (zero? nanos)                   ""
       (zero? (rem nanos 1000000))     (str "." (pad (quot nanos 1000000) 3))
       (zero? (rem nanos 1000))        (str "." (pad (quot nanos 1000) 6))
       :else                           (str "." (pad nanos 9)))))

#?(:cljs
   (defn ^{:jsdoc ["@constructor" "@extends {goog.date.UtcDateTime}"]} Instant
     "A moment: `ms` since the epoch and `sub-nanos`, the nanoseconds within
      its last millisecond (0–999999). Made by parse-instant and the decoder."
     [ms sub-nanos]
     (this-as ^js this
       (.call UtcDateTime this (js/Date. ms))
       (set! (.-subNanos this) sub-nanos)
       this)))

#?(:cljs
   (defn- sub-nanos
     "The nanoseconds within an Instant's last millisecond."
     [^js i]
     (.-subNanos i)))

#?(:cljs (goog/inherits Instant UtcDateTime))

#?(:cljs
   (defn- instant-text [^js i]
     (let [d (js/Date. (.getTime i))]
       (str (pad (.getUTCFullYear d) 4) "-" (pad (inc (.getUTCMonth d)) 2) "-" (pad (.getUTCDate d) 2)
            "T" (pad (.getUTCHours d) 2) ":" (pad (.getUTCMinutes d) 2) ":" (pad (.getUTCSeconds d) 2)
            (fraction (+ (* 1000000 (.getUTCMilliseconds d)) (sub-nanos i)))
            "Z"))))

#?(:cljs
   (extend-type Instant
     Object
     (toString [this] (instant-text this))
     (toJSON [this] (instant-text this))
     (clone [this] (Instant. (.getTime this) (sub-nanos this)))
     IEquiv
     (-equiv [this other]
       (and (instance? Instant other)
            (== (.getTime this) (.getTime other))
            (== (sub-nanos this) (sub-nanos other))))
     IHash
     (-hash [this] (hash (instant-text this)))
     IComparable
     (-compare [this other]
       (if (instance? Instant other)
         (let [by-ms (compare (.getTime this) (.getTime other))]
           (if (zero? by-ms) (compare (sub-nanos this) (sub-nanos other)) by-ms))
         (throw (js/Error. (str "Cannot compare " this " to " other)))))))

#?(:cljs
   (defn ^{:jsdoc ["@constructor" "@extends {goog.date.Date}"]} Day
     "A calendar day: `year`, `month` (1–12), `day`. Made by parse-date and
      the decoder."
     [year month day]
     (this-as this
       (.call Date this year (dec month) day)
       this)))

#?(:cljs (goog/inherits Day Date))

#?(:cljs
   (defn- day-text [^js d]
     (str (pad (.getFullYear d) 4) "-" (pad (inc (.getMonth d)) 2) "-" (pad (.getDate d) 2))))

#?(:cljs
   (extend-type Day
     Object
     (toString [this] (day-text this))
     (toJSON [this] (day-text this))
     (clone [this] (Day. (.getFullYear this) (inc (.getMonth this)) (.getDate this)))
     IEquiv
     (-equiv [this other] (and (instance? Day other) (= (day-text this) (day-text other))))
     IHash
     (-hash [this] (hash (day-text this)))
     IComparable
     (-compare [this other]
       (if (instance? Day other)
         (compare (day-text this) (day-text other))
         (throw (js/Error. (str "Cannot compare " this " to " other)))))))

#?(:cljs
   (defn- utc-ms
     "Milliseconds since the epoch of a UTC date and time, or nil when it is
      no such date or time (2026-02-30, 24:00)."
     [y mo d h mi s ms]
     (let [t (doto (js/Date. 0) (.setUTCFullYear y (dec mo) d) (.setUTCHours h mi s ms))]
       (when (and (= y (.getUTCFullYear t)) (= (dec mo) (.getUTCMonth t)) (= d (.getUTCDate t))
                  (= h (.getUTCHours t)) (= mi (.getUTCMinutes t)) (= s (.getUTCSeconds t)))
         (.getTime t)))))

#?(:cljs
   (def ^:private instant-pattern
     #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|([+-])(\d{2}):(\d{2}))$"))

#?(:cljs
   (defn read-instant
     "An Instant from ISO text with Z or an offset, \"2026-10-12T10:00:00Z\",
      or nil when the text isn't one."
     [text]
     (when (string? text)
       (when-let [[_ y mo d h mi s frac _ sign oh om] (re-matches instant-pattern text)]
         (let [n       #(js/parseInt % 10)
               nanos   (if frac (n (.padEnd frac 9 "0")) 0)
               offset  (if sign (* (if (= "-" sign) -1 1) (+ (* 60 (n oh)) (n om))) 0)]
           (when (and (<= (n (or oh "0")) 18) (< (n (or om "0")) 60))
             (when-let [ms (utc-ms (n y) (n mo) (n d) (n h) (n mi) (n (or s "0")) (quot nanos 1000000))]
               (Instant. (- ms (* offset 60000)) (rem nanos 1000000)))))))))

#?(:cljs
   (defn read-day
     "A Day from ISO text, \"2026-11-15\", or nil when the text isn't one."
     [text]
     (when (string? text)
       (when-let [[_ y mo d] (re-matches #"^(\d{4})-(\d{2})-(\d{2})$" text)]
         (let [[y mo d] (map #(js/parseInt % 10) [y mo d])]
           (when (utc-ms y mo d 0 0 0 0)
             (Day. y mo d)))))))

#?(:cljs
   (def ^:private browser-time-schemas
     {:time/instant    (m/-simple-schema {:type            :time/instant
                                          :pred            #(instance? Instant %)
                                          :type-properties {:error/message "should be a moment, like 2026-10-12T10:00:00Z"}})
      :time/local-date (m/-simple-schema {:type            :time/local-date
                                          :pred            #(instance? Day %)
                                          :type-properties {:error/message "should be a day, like 2026-11-15"}})}))

#?(:cljs
   (def ^:private time-transformer
     "The decoder for times in the browser: ISO text becomes an
      Instant or a Day; text that isn't one stays, and fails its shape. Going
      out, an Instant or a Day is its text; a js/Date or another goog.date
      value given as a param becomes the same text."
     (mt/transformer
      {:name     :time
       :decoders {:time/instant    #(or (read-instant %) %)
                  :time/local-date #(or (read-day %) %)}
       :encoders {:time/instant    (fn [x] (cond (instance? Instant x)  (instant-text x)
                                                 (instance? js/Date x)  (instant-text (Instant. (.getTime x) 0))
                                                 (instance? Date x)     (instant-text (Instant. (.getTime x) 0))
                                                 :else                  x))
                  :time/local-date (fn [x] (if (instance? Date x) (day-text x) x))}})))

#?(:cljs
   (def ^:private id-text-pattern
     #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

#?(:cljs
   (def id-text-schema
     "An id in the browser: text shaped like a UUID. Its type isn't :uuid, so
      malli's JSON transformer leaves the text alone."
     (m/-simple-schema {:type            :ground.wire/id-text
                        :pred            #(and (string? %) (some? (re-matches id-text-pattern %)))
                        :type-properties {:error/message "should be an id"}})))

(def registry
  "The schemas a shape may use: malli's own, the :time/…
   schemas and :decimal. In the browser :uuid is an id as text, and the
   times are :time/instant and :time/local-date."
  (mr/composite-registry
   #?(:cljs {:uuid id-text-schema} :clj {})
   (m/default-schemas)
   #?(:clj (time/schemas) :cljs browser-time-schemas)
   {:decimal decimal-schema}))

(defn schema
  "A shape as a malli schema, with this library's schemas."
  [shape]
  (m/schema shape {:registry registry}))

(def ^:private decimal-transformer
  (mt/transformer
   {:name     :decimal
    :decoders {:decimal (fn [x] #?(:clj (if (string? x)
                                          (try (BigDecimal. ^String x) (catch Exception _ x))
                                          x)
                                   :cljs (let [{:keys [read]} @exact]
                                           (if (and read (string? x) (re-matches decimal-text-pattern x))
                                             (read x)
                                             x))))}
    :encoders {:decimal (fn [x] #?(:clj (if (decimal? x) (.toPlainString ^BigDecimal x) x)
                                   :cljs (let [{:keys [decimal? text]} @exact]
                                           (if (and decimal? (decimal? x)) (text x) x))))}}))

(def ^:private big-integer-transformer
  "Integers beyond JavaScript's safe range travel as text."
  (mt/transformer
   {:name     :big-integer
    :decoders {:int (fn [x] (if (string? x)
                              #?(:clj (or (parse-long x) x) :cljs x)
                              x))}
    :encoders {:int (fn [x] #?(:clj (if (and (integer? x) (> (abs x) 9007199254740991)) (str x) x)
                               :cljs x))}}))

#?(:cljs
   (def ^:private id-text-transformer
     "A UUID value given in the browser becomes its text, both ways."
     (let [text (fn [x] (if (uuid? x) (str x) x))]
       (mt/transformer {:name     :id-text
                        :decoders {:ground.wire/id-text text}
                        :encoders {:ground.wire/id-text text}}))))

(def transformer
  (mt/transformer (mt/json-transformer)
                  #?(:clj (time-transform/time-transformer) :cljs time-transformer)
                  decimal-transformer
                  big-integer-transformer
                  #?(:cljs id-text-transformer)))

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

(defn parse-date
  "A day from its ISO text, \"2026-11-15\": a LocalDate on the server, a Day
   in the browser. Text that isn't one throws."
  [s]
  #?(:clj  (java.time.LocalDate/parse s)
     :cljs (or (read-day s) (throw (ex-info (str "Not a day: " s) {:type ::not-a-day :text s})))))

(defn parse-instant
  "A moment from its ISO text, \"2026-10-12T10:00:00Z\": an Instant on both
   sides (java.time's on the server). Text that isn't one throws."
  [s]
  #?(:clj  (java.time.Instant/parse s)
     :cljs (or (read-instant s) (throw (ex-info (str "Not a moment: " s) {:type ::not-a-moment :text s})))))

(defn decimal
  "An exact decimal from its text, \"12.30\". In the browser with
   an exact value given (big.js) the value stays exact, but trailing zeros
   aren't kept: it reads 12.3; without one, it is the text."
  [s]
  #?(:clj  (BigDecimal. ^String s)
     :cljs (if-let [{:keys [read]} @exact] (read s) s)))

(defn decimal-text
  "An exact decimal as text."
  [d]
  #?(:clj  (.toPlainString ^BigDecimal d)
     :cljs (if-let [{:keys [decimal? text]} @exact] (if (decimal? d) (text d) (str d)) (str d))))

;; =============================================================================
;; JSON text
;; =============================================================================

(defn- key-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

#?(:clj
   (def ^:private mapper
     (json/object-mapper {:decode-key-fn keyword
                          :encode-key-fn key-name})))

(defn write-json
  "Data as JSON text; keyword keys keep their namespace: \"post/title\"."
  [x]
  #?(:clj  (json/write-value-as-string x mapper)
     :cljs (js/JSON.stringify (clj->js x :keyword-fn key-name))))

(defn read-json
  "JSON text as data; keys become keywords, \"post/title\" → :post/title."
  [s]
  #?(:clj  (json/read-value s mapper)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))
