(ns groundwork.parquery.wire
  "What travels over parquery, the same rules on both sides.

   JSON on the wire; the endpoint's shapes (malli) give the types back.
   Keys like :post/title travel as \"post/title\". A UUID, a date, a decimal
   and a number too big for JavaScript travel as text and come back as what
   they are: java.time on the server, js-joda in the browser (the :time/…
   schemas, like :time/local-date); BigDecimal on the server, big.js in the
   browser (:decimal). Money travels as whole cents, an :int.

   An ask:     {\"endpoint\": \"jobs/list\", \"params\": {…}, \"key\": \"…\"}   (key: writes)
   An answer:  {\"status\": \"ok\", \"data\": …}
               {\"status\": \"invalid\", \"errors\": {…}}
               {\"status\": \"signed-out\"} {\"status\": \"not-found\"}
               {\"status\": \"refused\", \"message\": \"…\"}
               {\"status\": \"too-many\", \"limit\": \"per-minute\", \"retry-after-seconds\": 40}
               {\"status\": \"error\", \"message\": \"…\", \"request-id\": \"…\"}
   A request carries a list of asks and gets the answers in the same order.

   A write may say what its browser shows, \"watching\": [[endpoint, params], …];
   its answer then says which of those it changed, with the tag its bells
   carry on the websocket, so the same change isn't asked for twice:
               {\"status\": \"ok\", \"data\": …, \"changed\": [[…], …], \"tag\": \"…\"}

   Over the websocket, the browser says what it shows and the server says
   what changed, both as asks [endpoint params]:
               {\"watch\": [[\"jobs/list\", {…}], …]}
               {\"changed\": [[\"jobs/list\", {…}], …], \"tag\": \"…\"}   (tag: a write's)"
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.experimental.time :as time]
   [malli.experimental.time.transform :as time-transform]
   [malli.registry :as mr]
   [malli.transform :as mt]
   #?(:clj [jsonista.core :as json])
   #?(:cljs ["big.js" :default Big])
   #?(:cljs ["@js-joda/core" :as js-joda])))

;; =============================================================================
;; Shapes
;; =============================================================================

(def decimal-schema
  "An exact decimal: BigDecimal on the server, big.js in the browser."
  (m/-simple-schema {:type            :decimal
                     :pred            #?(:clj decimal? :cljs #(instance? Big %))
                     ;; for generated values (tests): a whole number, as a decimal
                     :type-properties {:gen/schema :int
                                       :gen/fmap   (fn [n] #?(:clj (BigDecimal. (str n ".00")) :cljs (Big. (str n))))}}))

(def registry
  "The schemas an endpoint's shapes may use: malli's own, the :time/…
   schemas and :decimal."
  (mr/composite-registry
   (m/default-schemas)
   (time/schemas)
   {:decimal decimal-schema}))

(defn schema
  "A shape as a malli schema, with Groundwork's schemas."
  [shape]
  (m/schema shape {:registry registry}))

(def ^:private decimal-transformer
  (mt/transformer
   {:name     :decimal
    :decoders {:decimal (fn [x] (if (string? x)
                                  (try #?(:clj (BigDecimal. ^String x) :cljs (Big. x))
                                       (catch #?(:clj Exception :cljs :default) _ x))
                                  x))}
    :encoders {:decimal (fn [x] #?(:clj (if (decimal? x) (.toPlainString ^BigDecimal x) x)
                                   :cljs (if (instance? Big x) (.toString x) x)))}}))

(def ^:private big-integer-transformer
  "Integers beyond JavaScript's safe range travel as text."
  (mt/transformer
   {:name     :big-integer
    :decoders {:int (fn [x] (if (string? x)
                              #?(:clj (or (parse-long x) x) :cljs x)
                              x))}
    :encoders {:int (fn [x] #?(:clj (if (and (integer? x) (> (abs x) 9007199254740991)) (str x) x)
                               :cljs x))}}))

(def transformer
  (mt/transformer (mt/json-transformer)
                  (time-transform/time-transformer)
                  decimal-transformer
                  big-integer-transformer))

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
  "A day from its ISO text, \"2026-11-15\": a LocalDate on both sides."
  [s]
  #?(:clj (java.time.LocalDate/parse s) :cljs (.parse (.-LocalDate js-joda) s)))

(defn parse-instant
  "A moment from its ISO text, \"2026-10-12T10:00:00Z\"."
  [s]
  #?(:clj (java.time.Instant/parse s) :cljs (.parse (.-Instant js-joda) s)))

(defn decimal
  "An exact decimal from its text, \"12.30\". In the browser (big.js) the
   value stays exact, but trailing zeros aren't kept: it reads 12.3."
  [s]
  #?(:clj (BigDecimal. ^String s) :cljs (Big. s)))

(defn decimal-text
  "An exact decimal as text."
  [d]
  #?(:clj (.toPlainString ^BigDecimal d) :cljs (.toString d)))

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

;; =============================================================================
;; Messages
;; =============================================================================

(defn endpoint-name
  "An endpoint as it travels: :jobs/list → \"jobs/list\"."
  [k]
  (key-name k))

(defn ask
  "One ask as it travels."
  [{:keys [endpoint params key watching]}]
  (cond-> {:endpoint (endpoint-name endpoint) :params params}
    key      (assoc :key key)
    watching (assoc :watching watching)))

(defn read-ask
  "One ask as it arrived: the endpoint a keyword, params still as JSON gave
   them (the endpoint's input shape decodes them)."
  [{:keys [endpoint params key watching]}]
  (when (and (string? endpoint) (or (nil? params) (map? params)) (or (nil? key) (string? key))
             (or (nil? watching) (sequential? watching)))
    (cond-> {:endpoint (keyword endpoint) :params (or params {})}
      key      (assoc :key key)
      watching (assoc :watching (vec watching)))))
