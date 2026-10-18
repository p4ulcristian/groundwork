(ns groundwork.parquery.store
  "The browser's answers from parquery, kept by question: an endpoint and its
   params. It isn't transport, so it sits beside groundwork.parquery, named
   for whose answers it keeps. It lives in re-frame's db under
   :groundwork/parquery-store; the app's own state is beside it.

     (rf/dispatch [::store/start {:shapes {…}}])                  ; once, at boot; nothing asks before it
     (rf/dispatch [::store/ensure :jobs-page [{:endpoint :jobs/list :params {:shop/id id}}]])
     @(rf/subscribe [::store/answer {:endpoint :jobs/list :params {:shop/id id}}])
     ;=> {:status :ready :data […]} or :loading, :error (with :error), and
     ;   :refresh-error when a refresh failed while the old data still shows
     (rf/dispatch [::store/release :jobs-page])
     (rf/dispatch [::store/own :jobs-page [asks…]])   ; exactly these now: shared ones kept, the rest let go
     (rf/dispatch [::store/ask-again {:endpoint :jobs/list :params {:shop/id id}}])
     (rf/dispatch [::store/once-answered {:endpoint :jobs/list :params {:shop/id id}} [:jobs/loaded]])
     (rf/dispatch [::store/hear :jobs-table [{:endpoint :jobs/list :params {:shop/id id}}] [:jobs-table/reload]])
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
   - An ask may say how long its answer stays fresh, :fresh-ms: ensuring it
     again within that time asks nothing. Without it every ensure asks.
   - Something can wait for a question's answer once (once-answered): its
     event gets the data now when the answer is there and nothing is out,
     else when the answer lands; a failed answer tells it nothing.
   - The app can hear of every read that failed (::start's :on-read-failed,
     an event that gets the ask and the answer), to say so its own way.
   - An entry nothing needs is kept 5 minutes, then dropped.
   - An owner can hear the bell for asks whose answers something else keeps
     (hear): a list with its own loader. Its event is dispatched when one of
     them changed, and when the line came back; release stops it.
   - Writes are sent once, with a one-time key, and never kept. A write
     that brings its own :key keeps it: a form holds one until it is saved,
     so saving again after a lost answer can't save twice. A write
     says what is on screen, and its answer says which of that it changed:
     those are asked again at once, without waiting for the websocket. The
     websocket's bell for the same write carries the same tag, so nothing
     is asked twice; everyone else's screens hear it there.
   - A caller over a rate limit gets its answer as the entry's :error:
     {:status :too-many :limit :per-minute|:per-day :retry-after-seconds n};
     the store doesn't ask again by itself, and a write is never repeated.
   - Nothing asks before the start: ensure, own, hear, once-answered,
     ask-again and write throw before ::start, naming the endpoint. The start
     makes the store and sets how answers are read; a question from before it
     would be answered into nothing.
   - \"Can I?\" reads the person's grants: ensure
     {:endpoint :groundwork/grants :params {}} for a signed-in person.
   - An id is text here, in params, answers and keys; a UUID value given in
     params counts as its text."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [groundwork.parquery.http :as http]
   [groundwork.parquery.websocket :as websocket]
   [re-frame.core :as rf]))

(def gc-ms (* 5 60 1000))
(def gather-ms 200)

(def tags-kept 50)

(def empty-store {:entries {} :seq 0 :refresh-pending? false :tags {} :tag-order []})

(defn- ids-as-text
  "Params with every UUID value as its text: an id is text in the browser,
   so one question has one key however its ids were given."
  [params]
  (walk/postwalk #(if (uuid? %) (str %) %) params))

(defn key-of
  "The key an ask's entry is kept under: [endpoint params], ids as text."
  [{:keys [endpoint params]}]
  [endpoint (ids-as-text (or params {}))])

;; =============================================================================
;; The rules, as plain functions over the store
;; =============================================================================

(defn- fresh?
  "Whether an entry's answer is still fresh for an ask's :fresh-ms."
  [{:keys [status stale? answered-at]} fresh-ms now]
  (and fresh-ms now answered-at
       (= :ready status)
       (not stale?)
       (< (- now answered-at) fresh-ms)))

(defn ensure-asks
  "An owner needs these asks: [store keys-to-fetch]. A question asked
   already and still loading isn't asked again, nor one whose answer is
   fresh by the ask's :fresh-ms (given the time, `now`)."
  ([store owner asks] (ensure-asks store owner asks nil))
  ([store owner asks now]
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
               [store (if (or (get-in store [:entries k :fetching?])
                              (fresh? entry (:fresh-ms ask) now))
                        fetch
                        (conj fetch k))]))
           [store []]
           asks)))

(defn start-fetch
  "Mark an entry as being asked: [store request-id]."
  [store k]
  (let [n (inc (:seq store))]
    [(-> store
         (assoc :seq n)
         (update-in [:entries k]
                    (fn [entry]
                      (cond-> (assoc entry :fetching? true :request-id n :stale? false)
                        ;; asking again after a failed first load reads as
                        ;; loading, not as the old error
                        (not (contains? entry :data)) (-> (assoc :status :loading) (dissoc :error))))))
     n]))

(defn answered
  "An answer arrived for a request. Only the newest request's answer
   counts. With the time, `now`, a good answer is stamped for :fresh-ms."
  ([store k request-id answer] (answered store k request-id answer nil))
  ([store k request-id {:keys [status data] :as answer} now]
   (let [entry (get-in store [:entries k])]
     (if (or (nil? entry) (not= request-id (:request-id entry)))
       store
       (update-in store [:entries k]
                  (fn [entry]
                    (let [entry (dissoc entry :fetching?)]
                      (cond
                        (= :ok status)          (-> entry
                                                    (assoc :status :ready :data data)
                                                    (cond-> now (assoc :answered-at now))
                                                    (dissoc :error :refresh-error))
                        (contains? entry :data) (assoc entry :refresh-error answer)
                        :else                   (assoc entry :status :error :error answer)))))))))

(defn once-answered
  "Something waits for a question's answer, once: [store data-now?]. With
   the answer there and nothing out, data-now? is true and the caller tells
   it at once; with a request out, its event waits for that answer; a
   question nothing ensured, or one that failed, tells it nothing."
  [store ask event]
  (let [k     (key-of ask)
        entry (get-in store [:entries k])]
    (cond
      (nil? entry)                                           [store false]
      (:fetching? entry)                                     [(update-in store [:listeners k] (fnil conj []) event) false]
      (and (= :ready (:status entry)) (contains? entry :data)) [store true]
      :else                                                  [store false])))

(defn heard
  "After an answer: [store events-to-tell]. A good answer that is the last
   word (not about to be asked once more) goes to whoever waited for it; a
   failed one drops them."
  [store k {:keys [status]}]
  (let [waiting (get-in store [:listeners k])
        entry   (get-in store [:entries k])]
    (cond
      (empty? waiting)                         [store []]
      (:fetching? entry)                       [store []]
      (and (= :ok status) (not (:again? entry))) [(update store :listeners dissoc k) waiting]
      (= :ok status)                           [store []]
      :else                                    [(update store :listeners dissoc k) []])))

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

(defn own-asks
  "An owner needs exactly these asks now: [store keys-to-fetch]. The ones it
   had and still needs stay as they are, the ones it doesn't are let go
   (marked with the time when nothing else needs them), new ones are asked.
   A route's reads work this way: what the next route shares is kept."
  [store owner asks now]
  (let [wanted (set (map key-of asks))
        had    (into #{} (keep (fn [[k entry]] (when (contains? (:owners entry) owner) k))) (:entries store))
        store  (update store :entries
                       (fn [entries]
                         (into {}
                               (map (fn [[k entry]]
                                      [k (if (and (had k) (not (wanted k)))
                                           (let [entry (update entry :owners disj owner)]
                                             (cond-> entry
                                               (and (empty? (:owners entry)) (not (:released-at entry)))
                                               (assoc :released-at now)))
                                           entry)]))
                               entries)))]
    (ensure-asks store owner (remove #(had (key-of %)) asks))))

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

(defn- remember-tag
  "Note something about a write's tag; only the newest tags-kept are kept."
  [store tag f]
  (let [new?  (not (contains? (:tags store) tag))
        store (cond-> (update-in store [:tags tag] f)
                new? (update :tag-order (fnil conj []) tag))
        extra (- (count (:tag-order store)) tags-kept)]
    (if (pos? extra)
      (-> store
          (update :tags #(apply dissoc % (take extra (:tag-order store))))
          (update :tag-order #(vec (drop extra %))))
      store)))

(defn rang
  "The websocket's bell: these reads changed. A bell with a write's tag
   leaves out what that write's own answer covered already (rang-in-answer),
   and is remembered, in case the answer comes second."
  [store forms tag]
  (if (nil? tag)
    (changed store forms)
    (let [covered (get-in store [:tags tag :covered] #{})]
      (-> store
          (remember-tag tag #(update % :heard (fnil into #{}) forms))
          (changed (remove covered forms))))))

(defn rang-in-answer
  "A write's own answer says which of the reads it was sent with
   (`watching`) changed: [store keys-to-fetch], asked at once. What the
   websocket's bell for the same tag rang already isn't asked again; a
   question that is out is asked once more when its answer lands."
  [store tag watching forms]
  (let [heard  (get-in store [:tags tag :heard] #{})
        forms  (set (remove heard forms))
        store  (remember-tag store tag #(assoc % :covered (set watching)))
        hit    (for [[k {:keys [form owners]}] (:entries store)
                     :when (and (forms form) (seq owners))]
                 k)
        out?   #(get-in store [:entries % :fetching?])]
    [(reduce #(assoc-in %1 [:entries %2 :again?] true) store (filter out? hit))
     (vec (remove out? hit))]))

(defn hearing
  "An owner hears the bell for these asks, whose answers the store doesn't
   keep; its event is dispatched when one of them changed."
  [store owner asks event]
  (assoc-in store [:hearing owner] {:forms (into #{} (map http/form) asks) :event event}))

(defn hearers
  "The events of whoever hears one of these forms."
  [store forms]
  (let [forms (set forms)]
    (into [] (keep (fn [[_ {hears :forms :keys [event]}]] (when (some forms hears) event)))
          (:hearing store))))

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
  "What is on screen, for the bells: the entries something needs, and what
   an owner hears."
  [store]
  (into (into [] (keep (fn [[_ {:keys [owners form]}]] (when (seq owners) form))) (:entries store))
        (mapcat (comp :forms val))
        (:hearing store)))

(defn can?
  "Whether the sentences give the person an action in a scope, from their
   grants. A grant with no scope is for every scope."
  [store action scope-id]
  (boolean (some #(and (= action (:grant/action %))
                       (or (nil? (:grant/scope-id %)) (= (str scope-id) (:grant/scope-id %))))
                 (get-in store [:entries [:groundwork/grants {}] :data]))))

;; =============================================================================
;; Events
;; =============================================================================

(defn- store-of [db] (get db :groundwork/parquery-store empty-store))

(defn- started!
  "Throws when the app hasn't started: nothing asks before the start. The
   start makes the store and sets how answers are read; a question from
   before it would be answered into nothing, and its screen would wait
   forever. `asks` are the questions, to name them."
  [db what asks]
  (when-not (contains? db :groundwork/parquery-store)
    (let [endpoints (distinct (keep :endpoint asks))]
      (throw (ex-info (str "Asked " (if (seq endpoints) (str/join ", " endpoints) "the store") " (" what
                           ") before the app's start. Nothing asks before groundwork.browser/start!: "
                           "the data a screen needs on arrival is declared on its route (:reads), "
                           "and code that asks while its namespace loads belongs in an event after the start.")
                      {:type ::asked-before-start :what what :endpoints (vec endpoints)})))))

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
           (fn [[ask watching on-answer]]
             (http/ask! (assoc ask :key (or (:key ask) (str (random-uuid))) :watching watching)
                        #(rf/dispatch [::-written watching on-answer %]))))

(rf/reg-fx ::start-line
           (fn [options]
             (websocket/start! (merge {:on-changed     #(rf/dispatch [::-changed %1 %2])
                                       :on-reconnected #(rf/dispatch [::-reconnected])}
                                      options))))

(rf/reg-cofx ::now (fn [cofx] (assoc cofx :now (js/Date.now))))

(rf/reg-event-fx ::start
                 (fn [{:keys [db]} [_ {:keys [shapes max-asks line on-read-failed]}]]
                   (http/set-shapes! shapes)
                   (when max-asks (http/set-max-asks! max-asks))
                   {:db          (assoc db :groundwork/parquery-store
                                        (cond-> empty-store on-read-failed (assoc :on-read-failed on-read-failed)))
                    ::start-line (or line {})}))

(rf/reg-event-fx ::ensure
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ owner asks]]
                   (started! db "ensure" asks)
                   (let [[store ks]      (ensure-asks (collect (store-of db) now) owner asks now)
                         [store fetches] (fetching store ks)]
                     {:db     (assoc db :groundwork/parquery-store store)
                      ::fetch fetches
                      ::watch (watched store)})))

(rf/reg-event-fx ::own
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ owner asks]]
                   (started! db "own" asks)
                   (let [[store ks]      (own-asks (collect (store-of db) now) owner asks now)
                         [store fetches] (fetching store ks)]
                     {:db     (assoc db :groundwork/parquery-store store)
                      ::fetch fetches
                      ::watch (watched store)})))

(rf/reg-event-fx ::release
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ owner]]
                   (let [store (-> (collect (release-owner (store-of db) owner now) now)
                                   (update :hearing dissoc owner))]
                     {:db (assoc db :groundwork/parquery-store store) ::watch (watched store)})))

(rf/reg-event-fx ::hear
                 (fn [{:keys [db]} [_ owner asks event]]
                   (started! db "hear" asks)
                   (let [store (hearing (store-of db) owner asks event)]
                     {:db (assoc db :groundwork/parquery-store store) ::watch (watched store)})))

(rf/reg-event-fx ::-answered
                 ;; A bell that rang while the question was out left the entry stale:
                 ;; this answer may be from before the change, so it's asked again.
                 [(rf/inject-cofx ::now)]
                 (fn [{:keys [db now]} [_ k n answer]]
                   (let [before          (store-of db)
                         current?        (= n (get-in before [:entries k :request-id]))
                         store           (answered before k n answer now)
                         [store told]    (if current? (heard store k answer) [store []])
                         ;; asked again while it was out: once more, now
                         [store ks]      (asked-again store k)
                         [store fetches] (fetching store ks)
                         again?          (and (not (:refresh-pending? store)) (seq (stale-on-screen store)))
                         failed          (when (and current? (not= :ok (:status answer)))
                                           (:on-read-failed store))
                         tell            (cond-> (mapv #(conj % (:data answer)) told)
                                           failed (conj (conj failed (get-in store [:entries k :ask]) answer)))]
                     (cond-> {:db (assoc db :groundwork/parquery-store
                                         (cond-> store again? (assoc :refresh-pending? true)))}
                       (seq fetches) (assoc ::fetch fetches)
                       (seq tell)    (assoc :fx (mapv (fn [event] [:dispatch event]) tell))
                       again?        (assoc :dispatch-later [{:ms gather-ms :dispatch [::-refresh]}])))))

(rf/reg-event-fx ::once-answered
                 (fn [{:keys [db]} [_ ask event]]
                   (started! db "once-answered" [ask])
                   (let [[store now?] (once-answered (store-of db) ask event)]
                     (cond-> {:db (assoc db :groundwork/parquery-store store)}
                       now? (assoc :dispatch (conj event (get-in store [:entries (key-of ask) :data])))))))

(rf/reg-event-fx ::ask-again
                 (fn [{:keys [db]} [_ ask]]
                   (started! db "ask-again" [ask])
                   (let [[store ks]      (ask-again (store-of db) ask)
                         [store fetches] (fetching store ks)]
                     {:db (assoc db :groundwork/parquery-store store) ::fetch fetches})))

(rf/reg-event-fx ::-changed
                 (fn [{:keys [db]} [_ forms tag]]
                   (let [before  (store-of db)
                         ;; what the write's own answer told already isn't told again
                         news    (remove (get-in before [:tags tag :covered] #{}) forms)
                         store   (rang before forms tag)
                         tell    (mapv (fn [event] [:dispatch event]) (hearers store news))]
                     (if (:refresh-pending? store)
                       {:db (assoc db :groundwork/parquery-store store) :fx tell}
                       {:db             (assoc db :groundwork/parquery-store (assoc store :refresh-pending? true))
                        :fx             tell
                        :dispatch-later [{:ms gather-ms :dispatch [::-refresh]}]}))))

(rf/reg-event-fx ::-reconnected
                 (fn [{:keys [db]} _]
                   (let [store (store-of db)]
                     {:db (assoc db :groundwork/parquery-store (mark-all-stale store))
                      :fx (into [[:dispatch [::-refresh]]]
                                (map (fn [[_ {:keys [event]}]] [:dispatch event]))
                                (:hearing store))})))

(rf/reg-event-fx ::-refresh
                 (fn [{:keys [db]} _]
                   (let [store           (assoc (store-of db) :refresh-pending? false)
                         [store fetches] (fetching store (stale-on-screen store))]
                     {:db (assoc db :groundwork/parquery-store store) ::fetch fetches})))

(rf/reg-event-fx ::write
                 (fn [{:keys [db]} [_ ask on-answer]]
                   (started! db "write" [ask])
                   {::send-write [ask (watched (store-of db)) on-answer]}))

(rf/reg-event-fx ::-written
                 ;; The answer says which reads on screen the write changed: they
                 ;; are asked at once, without waiting for the websocket.
                 (fn [{:keys [db]} [_ watching on-answer answer]]
                   (let [before          (store-of db)
                         ;; what the websocket's bell for this write told already
                         news            (remove (get-in before [:tags (:tag answer) :heard] #{}) (:changed answer))
                         [store ks]      (if (contains? answer :changed)
                                           (rang-in-answer before (:tag answer) watching (:changed answer))
                                           [before []])
                         [store fetches] (fetching store ks)]
                     {:db     (assoc db :groundwork/parquery-store store)
                      ::fetch fetches
                      :fx     (cond-> (mapv (fn [event] [:dispatch event]) (hearers store news))
                                on-answer (conj [:dispatch (conj on-answer (dissoc answer :changed :tag))]))})))

(rf/reg-sub ::store (fn [db _] (store-of db)))

(rf/reg-sub ::answer
            :<- [::store]
            (fn [store [_ ask]]
              (or (get-in store [:entries (key-of ask)]) {:status :loading})))

(rf/reg-sub ::can?
            :<- [::store]
            (fn [store [_ action scope-id]]
              (can? store action scope-id)))
