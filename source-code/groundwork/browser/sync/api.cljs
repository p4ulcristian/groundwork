(ns groundwork.browser.sync.api
  "Keeping what screens show in step with the database, the browser's side.
   The api of groundwork.browser.sync; the server's side is
   groundwork.server.sync.

   A store keeps answers by question, [name params]. A screen watches the
   questions it shows; a change the server tells of asks again the watched
   questions whose answers were read from the changed table:

     (def store (sync/make-question-store send-message!))   ; groundwork.browser.main makes it
     (def stop (sync/watch-question! store [:orders/of-shop {:shop/id shop-id}] show-orders!))
     (stop)                                                 ; when the screen goes"
  (:require
   [groundwork.browser.sync.store :as store]))

(defn make-question-store
  "A store that asks through send-message!: (fn [message]) -> a promise of
   the answer keyed by name, as mailroom's send-message! gives it."
  [send-message!]
  (store/make-store send-message!))

(defn watch-question!
  "(on-answer answer) with the kept answer now, if any, and with each new
   one: asked now, and again when a table it was read from changes. Returns
   a function that stops watching; the answer is kept 5 minutes after the
   last watcher stops."
  [store question on-answer]
  (store/watch! store question on-answer))

(defn ask-question-again!
  "Asks a question again now (a refresh button, a try-again, a timer)."
  [store question]
  (store/ask! store question))

(defn find-kept-answer
  "The answer kept for a question, or nil."
  [store question]
  (store/find-kept-answer store question))

(def sse-shapes-by-name
  "The shapes of the server's sync messages, for mailroom's shapes-by-name:
   {:sync/changed {:table \"orders\"}} and {:sync/missed {}}."
  {:sync/changed {:params-shape nil :answer-shape [:map {:closed true} [:table :string]]}
   :sync/missed  {:params-shape nil :answer-shape [:map {:closed true}]}})

(defn make-sse-handlers
  "The handlers for the server's sync messages, to open the sse line with:
   :sync/changed asks again what a changed table touches, :sync/missed asks
   again everything watched."
  [store]
  {:sync/changed #(store/hear-change! store (:table %))
   :sync/missed  (fn [_] (store/hear-missed! store))})

(defn hear-missed-changes!
  "Asks again every watched question: for when the sse line opens again
   after a break, and changes may have gone unheard."
  [store]
  (store/hear-missed! store))
