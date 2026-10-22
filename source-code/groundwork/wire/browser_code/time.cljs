(ns groundwork.wire.browser-code.time
  "Times in the browser. A moment is an Instant, a goog.date.UtcDateTime; a
   calendar day is a Day, a goog.date.Date (no time, no zone: its year, month
   and day are what the wire said, wherever the browser is). Both print, and
   go to JSON, as the ISO text they came as: \"2026-10-12T10:00:00Z\",
   \"2026-11-15\" (an instant's fraction in 3, 6 or 9 digits, as java.time
   and js-joda print it). JavaScript keeps milliseconds; an Instant keeps the
   finer part of its second too, so a moment goes back exactly as it came.
   They compare with = and compare, and goog.date's methods work on them (a
   copy from clone stays an Instant or a Day)."
  (:require
   [malli.core :as m]
   [malli.transform :as mt])
  (:import (goog.date Date UtcDateTime)))

(defn- pad [n width]
  (.padStart (str n) width "0"))

(defn- fraction
  "A second's fraction in nanoseconds, as java.time prints it: none, or
   3, 6 or 9 digits."
  [nanos]
  (cond
    (zero? nanos)                   ""
    (zero? (rem nanos 1000000))     (str "." (pad (quot nanos 1000000) 3))
    (zero? (rem nanos 1000))        (str "." (pad (quot nanos 1000) 6))
    :else                           (str "." (pad nanos 9))))

(defn ^{:jsdoc ["@constructor" "@extends {goog.date.UtcDateTime}"]} Instant
  "A moment: `ms` since the epoch and `sub-nanos`, the nanoseconds within
   its last millisecond (0–999999). Made by parse-instant and the decoder."
  [ms sub-nanos]
  (this-as ^js this
    (.call UtcDateTime this (js/Date. ms))
    (set! (.-subNanos this) sub-nanos)
    this))

(defn- sub-nanos
  "The nanoseconds within an Instant's last millisecond."
  [^js i]
  (.-subNanos i))

(goog/inherits Instant UtcDateTime)

(defn- instant-text [^js i]
  (let [d (js/Date. (.getTime i))]
    (str (pad (.getUTCFullYear d) 4) "-" (pad (inc (.getUTCMonth d)) 2) "-" (pad (.getUTCDate d) 2)
         "T" (pad (.getUTCHours d) 2) ":" (pad (.getUTCMinutes d) 2) ":" (pad (.getUTCSeconds d) 2)
         (fraction (+ (* 1000000 (.getUTCMilliseconds d)) (sub-nanos i)))
         "Z")))

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
      (throw (js/Error. (str "Cannot compare " this " to " other))))))

(defn ^{:jsdoc ["@constructor" "@extends {goog.date.Date}"]} Day
  "A calendar day: `year`, `month` (1–12), `day`. Made by parse-date and
   the decoder."
  [year month day]
  (this-as this
    (.call Date this year (dec month) day)
    this))

(goog/inherits Day Date)

(defn- day-text [^js d]
  (str (pad (.getFullYear d) 4) "-" (pad (inc (.getMonth d)) 2) "-" (pad (.getDate d) 2)))

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
      (throw (js/Error. (str "Cannot compare " this " to " other))))))

(defn- utc-ms
  "Milliseconds since the epoch of a UTC date and time, or nil when it is
   no such date or time (2026-02-30, 24:00)."
  [y mo d h mi s ms]
  (let [t (doto (js/Date. 0) (.setUTCFullYear y (dec mo) d) (.setUTCHours h mi s ms))]
    (when (and (= y (.getUTCFullYear t)) (= (dec mo) (.getUTCMonth t)) (= d (.getUTCDate t))
               (= h (.getUTCHours t)) (= mi (.getUTCMinutes t)) (= s (.getUTCSeconds t)))
      (.getTime t))))

(def ^:private instant-pattern
  #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|([+-])(\d{2}):(\d{2}))$")

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
            (Instant. (- ms (* offset 60000)) (rem nanos 1000000))))))))

(defn read-day
  "A Day from ISO text, \"2026-11-15\", or nil when the text isn't one."
  [text]
  (when (string? text)
    (when-let [[_ y mo d] (re-matches #"^(\d{4})-(\d{2})-(\d{2})$" text)]
      (let [[y mo d] (map #(js/parseInt % 10) [y mo d])]
        (when (utc-ms y mo d 0 0 0 0)
          (Day. y mo d))))))

(def schemas
  "The :time/instant and :time/local-date schemas, as the browser holds them."
  {:time/instant    (m/-simple-schema {:type            :time/instant
                                       :pred            #(instance? Instant %)
                                       :type-properties {:error/message "should be a moment, like 2026-10-12T10:00:00Z"}})
   :time/local-date (m/-simple-schema {:type            :time/local-date
                                       :pred            #(instance? Day %)
                                       :type-properties {:error/message "should be a day, like 2026-11-15"}})})

(def transformer
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
               :time/local-date (fn [x] (if (instance? Date x) (day-text x) x))}}))

(defn parse-date
  "A Day from its ISO text, \"2026-11-15\". Text that isn't one throws."
  [s]
  (or (read-day s) (throw (ex-info (str "Not a day: " s) {:type :groundwork.wire/not-a-day :text s}))))

(defn parse-instant
  "An Instant from its ISO text, \"2026-10-12T10:00:00Z\". Text that isn't one
   throws."
  [s]
  (or (read-instant s) (throw (ex-info (str "Not a moment: " s) {:type :groundwork.wire/not-a-moment :text s}))))
