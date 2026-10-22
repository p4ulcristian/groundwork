(ns groundwork.browser.transport.shapes
  "The same shapes as the server's handlers, by name, in the browser:
   {:orders/of-shop {:params-shape … :answer-shape …}}. A message's data
   goes out as JSON data by its :params-shape; an answer comes back as
   shaped data by its :answer-shape. A name without a shape passes as it is."
  (:require
   [groundwork.browser.wire.api :as wire]))

(defn- turn-each-name [message turn]
  (into {} (map (fn [[message-name data]] [message-name (turn message-name data)])) message))

(defn turn-message-into-json-data
  "Each name's data as JSON data, by its :params-shape."
  [shapes message]
  (turn-each-name message (fn [message-name data]
                            (if-let [shape (get-in shapes [message-name :params-shape])]
                              (wire/turn-shaped-data-into-json-data shape data)
                              data))))

(defn turn-answer-into-shaped-data
  "Each name's answer as shaped data, by its :answer-shape. An answer that
   doesn't fit stays as it came, with :groundwork.browser.transport/errors added."
  [shapes answer]
  (turn-each-name answer (fn [message-name data]
                           (if-let [shape (get-in shapes [message-name :answer-shape])]
                             (let [{:keys [shaped-data errors]} (wire/turn-json-data-into-shaped-data-or-errors shape data)]
                               (if errors {:groundwork.browser.transport/errors errors :data data} shaped-data))
                             data))))
