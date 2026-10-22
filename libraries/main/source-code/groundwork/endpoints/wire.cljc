(ns groundwork.endpoints.wire
  "What travels over parquery. How values travel as text lives in the
   groundwork.wire library (groundwork.wire.api and its language
   parts); its functions keep their old names here. What an ask and an answer
   are is parquery's, and lives here until parquery is its own library:

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
   [groundwork.wire.api :as wire]
   #?(:clj  [groundwork.wire.clojure.time :as time]
      :cljs [groundwork.wire.clojurescript.time :as time])
   #?(:clj  [groundwork.wire.clojure.types :as types]
      :cljs [groundwork.wire.clojurescript.types :as types])))

(def schema wire/schema)
(def decode wire/decode)
(def encode wire/encode)
(def fits? wire/fits?)
(def explain wire/explain)
(def write-json wire/write-json)
(def read-json wire/read-json)
(def parse-date time/parse-date)
(def parse-instant time/parse-instant)
(def decimal types/decimal)
(def decimal-text types/decimal-text)

#?(:cljs (def use-exact-decimals! types/use-exact-decimals!))
#?(:cljs (def Instant time/Instant))
#?(:cljs (def Day time/Day))
#?(:cljs (def read-instant time/read-instant))
#?(:cljs (def read-day time/read-day))
#?(:cljs (def id-text-schema types/id-text-schema))

;; =============================================================================
;; Messages: parquery's, not the wire's
;; =============================================================================

(defn endpoint-name
  "An endpoint as it travels: :jobs/list → \"jobs/list\"."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

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
