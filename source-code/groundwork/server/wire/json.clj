(ns groundwork.server.wire.json
  "JSON text on the JVM, through jsonista. Keyword keys keep their
   namespace: :post/title travels as \"post/title\" and comes back as
   :post/title."
  (:require
   [jsonista.core :as json]))

(defn- key-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn keyword
                       :encode-key-fn key-name}))

(defn write-json
  "Data as JSON text."
  [x]
  (json/write-value-as-string x mapper))

(defn read-json
  "JSON text as data."
  [s]
  (json/read-value s mapper))
