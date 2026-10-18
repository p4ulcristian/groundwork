(ns groundwork.repl.browser
  "The browser's side of talking to a running app: what the store holds,
   from the browser build's REPL (shadow-cljs).

     (browser/entries)                       ; every question, its status and owners
     (browser/answer :jobs/list {:shop/id id})  ; one entry, data included
     (browser/store)                         ; all of it"
  (:require
   [re-frame.db :as rf-db]))

(defn store
  "The store, as it is in re-frame's db."
  []
  (get @rf-db/app-db :groundwork/parquery-store))

(defn entries
  "Every question the store holds: its status, who needs it, whether it's
   stale or being asked, and how many items its data has."
  []
  (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
        (map (fn [[k {:keys [status owners stale? fetching? data released-at]}]]
               [k (cond-> {:status status :owners owners}
                    stale?      (assoc :stale? true)
                    fetching?   (assoc :fetching? true)
                    released-at (assoc :released-at released-at)
                    (coll? data) (assoc :items (count data)))]))
        (:entries (store))))

(defn answer
  "One question's entry, data included."
  [endpoint params]
  (get-in (store) [:entries [endpoint params]]))
