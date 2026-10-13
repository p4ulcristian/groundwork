(ns groundwork.parquery-store
  "The browser's answers from parquery, kept by question: an endpoint and its
   params. It isn't transport, so it sits beside groundwork.parquery, named
   for whose answers it keeps. It lives in re-frame's db under
   :groundwork/parquery-store; the app's own state is beside it.

     (rf/dispatch [::store/start {:shapes {…}}])                  ; once, at boot
     (rf/dispatch [::store/ensure :jobs-page [{:endpoint :jobs/list :params {:shop/id id}}]])
     @(rf/subscribe [::store/answer {:endpoint :jobs/list :params {:shop/id id}}])
     ;=> {:status :ready :data […]} or :loading, :error (with :error), and
     ;   :refresh-error when a refresh failed while the old data still shows
     (rf/dispatch [::store/release :jobs-page])
     (rf/dispatch [::store/ask-again {:endpoint :jobs/list :params {:shop/id id}}])
     (rf/dispatch [::store/write {:endpoint :jobs/move :params {…}} [:jobs/moved]])
     @(rf/subscribe [::store/can? :jobs/move shop-id])

   - A page or panel says what it needs when it opens (ensure), and lets go
     when it closes (release); showing data never fetches by itself.
   - Two asks for one question make one request and one entry.
   - An entry with data keeps showing it while a fresh answer loads.
   - Only the newest answer counts; a late answer to an older request is
     dropped.
   - When the bells say something on screen changed, it's asked again,
     gathered for a moment (200 ms), and only what's on screen. A bell
     that rings while the question is out asks once more after the answer:
     that answer may be from before the change.
   - A question can be asked again now (ask-again), for what no change in the
     database announces: a \"try again\" button on a failed page, a refresh
     button, a timer, putting a screen back after a refused write. If the
     question is out, it's asked once more when its answer lands. Asking
     again a question nothing has ensured does nothing.
   - An entry nothing needs is kept 5 minutes, then dropped.
   - Writes are sent once, with a one-time key, and never kept; their
     effect reaches screens through the bells.
   - A caller over a rate limit gets its answer as the entry's :error:
     {:status :too-many :limit :per-minute|:per-day :retry-after-seconds n};
     the store doesn't ask again by itself, and a write is never repeated.
   - \"Can I?\" reads the person's grants: ensure
     {:endpoint :groundwork/grants :params {}} for a signed-in person."
  (:require
   [groundwork.parquery.http :as http]
   [groundwork.parquery.websocket :as websocket]
   [re-frame.core :as rf]))

(def gc-ms (* 5 60 1000))
(def gather-ms 200)

(def empty-store {:entries {} :seq 0 :refresh-pending? false})

(defn- key-of [{:keys [endpoint params]}]
  [endpoint (or params {})])

;; =============================================================================
;; The rules, as plain functions over the store
;; =============================================================================

(defn ensure-asks
  "An owner needs these asks: [store keys-to-fetch]. A question asked
   already and still loading isn't asked again."
  [store owner asks]
  (reduce (fn [[store fetch] ask]
            (let [k     (key-of ask)
                  entry (get-in store [:entries k])
                  store (if entry
                          (-> store
                              (update-in [:entries k :owners] conj owner)
                              (update-in [:entries k] dissoc :released-at))
                          (assoc-in store [:entries k] {:ask    (assoc ask :params (second k))
                                                        :form   (http/form ask)
                                                        :owners #{owner}
                                                        :status :loading}))]
              [store (if (get-in store [:entries k :fetching?]) fetch (conj fetch k))]))
          [store []]
          asks))

(defn start-fetch
  "Mark an entry as being asked: [store request-id]."
  [store k]
  (let [n (inc (:seq store))]
    [(-> store
         (assoc :seq n)
         (update-in [:entries k] assoc :fetching? true :request-id n :stale? false))
     n]))

(defn answered
  "An answer arrived for a request. Only the newest request's answer
   counts."
  [store k request-id {:keys [status data] :as answer}]
  (let [entry (get-in store [:entries k])]
    (if (or (nil? entry) (not= request-id (:request-id entry)))
      store
      (update-in store [:entries k]
                 (fn [entry]
                   (let [entry (dissoc entry :fetching?)]
                     (cond
                       (= :ok status)          (-> entry (assoc :status :ready :data data) (dissoc :error :refresh-error))
                       (contains? entry :data) (assoc entry :refresh-error answer)
                       :else                   (assoc entry :status :error :error answer))))))))

(defn ask-again
  "Ask a question again now: [store keys-to-fetch]. A question that is out
   is asked once more when its answer lands (:again?); one nothing has
   ensured isn't asked."
  [store ask]
  (let [k     (key-of ask)
        entry (get-in store [:entries k])]
    (cond
      (nil? entry)       [store []]
      (:fetching? entry) [(assoc-in store [:entries k :again?] true) []]
      :else              [store [k]])))

(defn asked-again
  "After an answer: the question itself, when it was asked again meanwhile:
   [store keys-to-fetch]."
  [store k]
  (if (and (get-in store [:entries k :again?]) (not (get-in store [:entries k :fetching?])))
    [(update-in store [:entries k] dissoc :again?) [k]]
    [store []]))

(defn release-owner
  "An owner doesn't need its asks anymore; entries nothing needs are marked
   with the time."
  [store owner now]
  (update store :entries
          (fn [entries]
            (into {}
                  (map (fn [[k entry]]
                         (let [entry (update entry :owners disj owner)]
                           [k (if (and (empty? (:owners entry)) (not (:released-at entry)))
                                (assoc entry :released-at now)
                                entry)])))
                  entries))))

(defn collect
  "Drop entries nothing has needed for gc-ms."
  [store now]
  (update store :entries
          (fn [entries]
            (into {} (remove (fn [[_ {:keys [released-at]}]] (and released-at (<= gc-ms (- now released-at)))))
                  entries))))

(defn changed
  "The bells say these reads changed: mark them stale."
  [store forms]
  (let [forms (set forms)]
    (update store :entries
            (fn [entries]
              (into {}
                    (map (fn [[k entry]]
                           [k (cond-> entry (forms (:form entry)) (assoc :stale? true))]))
                    entries)))))

(defn mark-all-stale
  "Everything on screen may have changed (the line was down)."
  [store]
  (update store :entries update-vals #(cond-> % (seq (:owners %)) (assoc :stale? true))))

(defn stale-on-screen
  "The entries to ask again: stale, needed by a screen, not being asked."
  [store]
  (into [] (keep (fn [[k {:keys [stale? owners fetching?]}]] (when (and stale? (seq owners) (not fetching?)) k)))
        (:entries store)))

(defn watched
  "What is on screen, for the bells."
  [store]
  (into [] (keep (fn [[_ {:keys [owners form]}]] (when (seq owners) form))) (:entries store)))

(defn can?
  "Whether the person holds an action in a scope, from their grants."
  [store action scope-id]
  (boolean (some #(and (= action (:grant/action %)) (= scope-id (:grant/scope-id %)))
                 (get-in store [:entries [:groundwork/grants {}] :data]))))

;; =============================================================================
;; Events
;; =============================================================================

(defn- store-of [db] (get db :groundwork/parquery-store empty-store))

(defn- fetching
  "Start asking these keys: [store fetches], for the ::fetch effect."
  [store ks]
  (reduce (fn [[store fetches] k]
            (let [[store n] (start-fetch store k)]
              [store (conj fetches [k n (get-in store [:entries k :ask])])]))
          [store []]
          ks))

(rf/reg-fx ::fetch
           (fn [fetches]
             (doseq [[k n ask] fetches]
               (http/ask! ask #(rf/dispatch [::-answered k n %])))))

(rf/reg-fx ::watch
           (fn [forms] (websocket/watch! forms)))

(rf/reg-fx ::send-write
           (fn [[ask on-answer]]
             (http/ask! (assoc ask :key (str (random-uuid)))
                        #(when on-answer (rf/dispatch (conj on-answer %))))))

(rf/reg-fx ::start-line
           (fn [options]
             (websocket/start! (merge {:on-changed     #(rf/dispatch [::-changed %])
                                       :on-reconnected #(rf/dispatch [::-reconnected])}
                                      options))))

(rf/reg-cofx ::now (fn [cofx] (assoc cofx :now (js/Date.now))))

(rf/reg-event-fx ::start
                 (fn [{:keys [db]} [_ {:keys [shapes max-asks line]}]]
                   (http/set-shapes! shapes)
                   (when max-asks (http/set-max-asks! max-asks))
                   {:db          (assoc db :groundwork/parquery-store empty-store)
                    ::start-line (or line {})}))

(rf/reg-event-fx ::ensure
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ owner asks]]
                   (let [[store ks]      (ensure-asks (collect (store-of db) now) owner asks)
                         [store fetches] (fetching store ks)]
                     {:db     (assoc db :groundwork/parquery-store store)
                      ::fetch fetches
                      ::watch (watched store)})))

(rf/reg-event-fx ::release
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ owner]]
                   (let [store (collect (release-owner (store-of db) owner now) now)]
                     {:db (assoc db :groundwork/parquery-store store) ::watch (watched store)})))

(rf/reg-event-fx ::-answered
                 ;; A bell that rang while the question was out left the entry stale:
                 ;; this answer may be from before the change, so it's asked again.
                 (fn [{:keys [db]} [_ k n answer]]
                   (let [store           (answered (store-of db) k n answer)
                         ;; asked again while it was out: once more, now
                         [store ks]      (asked-again store k)
                         [store fetches] (fetching store ks)
                         again?          (and (not (:refresh-pending? store)) (seq (stale-on-screen store)))]
                     (cond-> {:db (assoc db :groundwork/parquery-store
                                         (cond-> store again? (assoc :refresh-pending? true)))}
                       (seq fetches) (assoc ::fetch fetches)
                       again?        (assoc :dispatch-later [{:ms gather-ms :dispatch [::-refresh]}])))))

(rf/reg-event-fx ::ask-again
                 (fn [{:keys [db]} [_ ask]]
                   (let [[store ks]      (ask-again (store-of db) ask)
                         [store fetches] (fetching store ks)]
                     {:db (assoc db :groundwork/parquery-store store) ::fetch fetches})))

(rf/reg-event-fx ::-changed
                 (fn [{:keys [db]} [_ forms]]
                   (let [store (changed (store-of db) forms)]
                     (if (:refresh-pending? store)
                       {:db (assoc db :groundwork/parquery-store store)}
                       {:db             (assoc db :groundwork/parquery-store (assoc store :refresh-pending? true))
                        :dispatch-later [{:ms gather-ms :dispatch [::-refresh]}]}))))

(rf/reg-event-fx ::-reconnected
                 (fn [{:keys [db]} _]
                   {:db       (assoc db :groundwork/parquery-store (mark-all-stale (store-of db)))
                    :dispatch [::-refresh]}))

(rf/reg-event-fx ::-refresh
                 (fn [{:keys [db]} _]
                   (let [store           (assoc (store-of db) :refresh-pending? false)
                         [store fetches] (fetching store (stale-on-screen store))]
                     {:db (assoc db :groundwork/parquery-store store) ::fetch fetches})))

(rf/reg-event-fx ::write
                 (fn [_ [_ ask on-answer]]
                   {::send-write [ask on-answer]}))

(rf/reg-sub ::store (fn [db _] (store-of db)))

(rf/reg-sub ::answer
            :<- [::store]
            (fn [store [_ ask]]
              (or (get-in store [:entries (key-of ask)]) {:status :loading})))

(rf/reg-sub ::can?
            :<- [::store]
            (fn [store [_ action scope-id]]
              (can? store action scope-id)))
