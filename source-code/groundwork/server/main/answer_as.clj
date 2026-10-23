(ns groundwork.server.main.answer-as
  "A message answered as the asker doorman finds in a request, off the
   server's addresses: on an app's own :browser route that speaks another
   protocol (MCP's JSON-RPC, a batch API), which turns a call into a
   message. The request is the route's own, with its proof: the session's
   cookie or a token doorman made (Authorization: Bearer); nobody names the
   person. It goes through every check a browser's message gets (doorman's
   session step, its access and the gates, the params' and the answer's
   shapes, a write's run-once key) and each handler's :rate-limit, counted
   per asker as the server counts them (a token's hash, else the cookie's,
   else the address):

     ;; a :browser route's handler, its tools' :answer-as
     (answer-as {:parts/update-part params} request)                ; JSON data, as the browser sends it
     ;=> {:parts/update-part {…}}                                    ; JSON data, as the browser gets it

   The REPL's ask-as-person (groundwork.server.main.tools) answers by the
   same answer-message, on handlers made ready with doorman.repl's step in
   place of the session step, and no request."
  (:require
   [bridge.api :as bridge]))

(defn make-answer-message
  "(fn [message request]): the message answered by ready-handlers, as
   over http, by the app's wire and log; limiter is bridge's
   make-message-limiter's, or :no-rate-limits."
  [{:keys [ready-handlers limiter wire log]}]
  (fn [message request]
    (bridge/answer-message ready-handlers limiter request message {:wire wire :log log})))

(defn make-answer-as
  "answer-as, (fn [message request]), from the running app's parts: its
   :handlers, made ready with doorman's session step (the server's own);
   :wire and :log the app's; :find-rate-limit-key the server's (fn
   [request]); :buckets-before-cleanup the :server settings'
   :rate-limit-buckets-before-cleanup."
  [{:keys [handlers wire log find-rate-limit-key buckets-before-cleanup]}]
  (make-answer-message {:ready-handlers handlers
                        :limiter        (bridge/make-message-limiter {:find-rate-limit-key    find-rate-limit-key
                                                                      :buckets-before-cleanup buckets-before-cleanup})
                        :wire           wire
                        :log            log}))
