(ns groundwork.wire.clojurescript.json
  "JSON text in the browser. Keyword keys keep their namespace: :post/title
   travels as \"post/title\" and comes back as :post/title.")

(defn- key-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn write-json
  "Data as JSON text."
  [x]
  (js/JSON.stringify (clj->js x :keyword-fn key-name)))

(defn read-json
  "JSON text as data."
  [s]
  (js->clj (js/JSON.parse s) :keywordize-keys true))
