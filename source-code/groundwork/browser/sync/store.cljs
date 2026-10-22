(ns groundwork.browser.sync.store
  "The browser keeps every answer under its question, [name params]:

     [:orders/of-shop {:shop/id #uuid \"…\"}]

   - A screen watches a question while it shows it; watching asks it, and
     shows the last answer at once while the fresh one comes.
   - Two asks of the same question while one is out make one request; the
     second is asked again when the first lands.
   - When asks overlap, only the newest answer counts.
   - Each answer comes with the tables it was read from (the server's notes);
     a change to one of them, heard over sse, asks again every watched
     question that read it, gathered for 50 ms. \"any\" means every change.
   - An answer no screen watches is kept 5 minutes, then dropped.")

(def ^:private keep-unwatched-ms (* 5 60 1000))
(def ^:private gather-ms 50)

(defn make-store
  "A store asking through send-message!, (fn [message]) -> a promise of the
   answer, keyed by name, with :mailroom/notes beside it."
  [send-message!]
  {:send-message! send-message!
   :questions     (atom {})
   :next-number   (atom 0)
   :changed       (atom #{})
   :gathering?    (atom false)})

(defn- tell-watchers! [store question]
  (let [{:keys [answer watchers]} (get @(:questions store) question)]
    (doseq [[_ on-answer] watchers]
      (on-answer answer))))

(defn- take-answer!
  "Keeps an answer and tells the watchers, if it is the newest ask's."
  [store question number answer tables]
  (when (= number (get-in @(:questions store) [question :newest-number]))
    (swap! (:questions store) update question assoc :answer answer :tables tables :answered? true)
    (tell-watchers! store question)))

(declare ask!)

(defn- land! [store question]
  (swap! (:questions store) update question assoc :out? false)
  (when (get-in @(:questions store) [question :ask-again?])
    (swap! (:questions store) update question assoc :ask-again? false)
    (ask! store question)))

(defn ask!
  "Asks a question now; while it is out, asks it once more when it lands."
  [{:keys [send-message! next-number questions] :as store} [message-name params :as question]]
  (if (get-in @questions [question :out?])
    (swap! questions update question assoc :ask-again? true)
    (let [number (swap! next-number inc)]
      (swap! questions update question assoc :out? true :newest-number number)
      (-> (send-message! {message-name params})
          (.then (fn [answer]
                   (take-answer! store question number (get answer message-name)
                                 (get-in answer [:mailroom/notes message-name :tables] "any"))))
          (.catch (fn [_]
                    (take-answer! store question number {:error "No answer from the server."} "any")))
          (.finally #(land! store question))))))

(defn- drop-later! [{:keys [questions]} question]
  (js/setTimeout #(swap! questions (fn [kept]
                                     (if (empty? (get-in kept [question :watchers]))
                                       (dissoc kept question)
                                       kept)))
                 keep-unwatched-ms))

(defn watch!
  "(on-answer answer) with the kept answer now, if any, and with every new
   one; asks the question. Returns a function that stops watching."
  [{:keys [questions] :as store} question on-answer]
  (let [watcher-id (random-uuid)]
    (swap! questions update-in [question :watchers] assoc watcher-id on-answer)
    (when (get-in @questions [question :answered?])
      (on-answer (get-in @questions [question :answer])))
    (ask! store question)
    (fn []
      (swap! questions update-in [question :watchers] dissoc watcher-id)
      (when (empty? (get-in @questions [question :watchers]))
        (drop-later! store question)))))

(defn find-kept-answer
  "The answer kept for a question, or nil."
  [{:keys [questions]} question]
  (get-in @questions [question :answer]))

(defn- does-question-read? [{:keys [tables]} changed]
  (or (= "any" tables) (= :all changed) (some (set changed) tables)))

(defn- ask-watched-again! [{:keys [questions] :as store} changed]
  (doseq [[question kept] @questions
          :when (and (seq (:watchers kept)) (does-question-read? kept changed))]
    (ask! store question)))

(defn hear-change!
  "A change to a table, from sync: gathered for 50 ms, then every watched
   question that read one of the changed tables is asked again."
  [{:keys [changed gathering?] :as store} table]
  (swap! changed conj table)
  (when-not @gathering?
    (reset! gathering? true)
    (js/setTimeout (fn []
                     (let [tables @changed]
                       (reset! changed #{})
                       (reset! gathering? false)
                       (ask-watched-again! store tables)))
                   gather-ms)))

(defn hear-missed!
  "Changes may have been missed (the line broke): every watched question
   is asked again."
  [store]
  (ask-watched-again! store :all))
