(ns groundwork.server.wire.time
  "Times on the JVM: java.time, through malli's time schemas (every :time/…
   schema of malli's)."
  (:require
   [malli.experimental.time :as time]
   [malli.experimental.time.transform :as time-transform]))

(def schemas
  "The :time/… schemas."
  (time/schemas))

(def transformer
  "ISO text to java.time and back."
  (time-transform/time-transformer))

(defn parse-date
  "A LocalDate from its ISO text, \"2026-11-15\". Text that isn't one throws."
  [s]
  (java.time.LocalDate/parse s))

(defn parse-instant
  "An Instant from its ISO text, \"2026-10-12T10:00:00Z\". Text that isn't one
   throws."
  [s]
  (java.time.Instant/parse s))
