(ns groundwork.transport.browser-code.http
  "http from the browser: POST a named message, get the answer keyed by the
   same names."
  (:require
   [groundwork.wire.api :as wire]))

(defn- make-fetch-options [message]
  #js {:method      "POST"
       :credentials "same-origin"
       :headers     #js {"accept" "application/json" "content-type" "application/json"}
       :body        (wire/turn-data-into-json-text message)})

(defn- read-answer [response]
  (.then (.text response) wire/turn-json-text-into-data))

(defn send-message!
  "POSTs {:orders/of-shop {…}} to the http address. A promise of the answer,
   {:orders/of-shop …}, each name's answer or {:error …}."
  [address message]
  (.then (js/fetch address (make-fetch-options message)) read-answer))
