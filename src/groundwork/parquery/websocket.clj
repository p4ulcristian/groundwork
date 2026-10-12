(ns groundwork.parquery.websocket
  "parquery over a websocket, the server half: the bells. Not built yet: a
   websocket to /parquery is refused.")

(defn handle
  "The Ring handler for a websocket to /parquery."
  [_part _request]
  {:status 426 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "The bells aren't built yet"})

(defn start
  "The bells part."
  [_part]
  nil)

(defn stop
  "Stop the bells."
  [_bells]
  nil)
