(ns groundwork.server.main.tools
  "groundwork's tools for study's REPL: plain functions, put in study.tools
   while a REPL is open (study's start-repl-server!, :tools). Each is made
   from the running app's parts, so a tool reaches the app the REPL was
   opened into; the REPL closes when that app stops.

     (study.tools/ask-as-person {:orders/of-shop {:shop/id shop-id}}
                                {:person-id anna :visitor-id nil :session-flags #{}})
     ;=> {:orders/of-shop [{:order/id \"…\" …}] :bridge/notes {…}}

     (study.tools/why-refused {:person-id anna :visitor-id nil :session-flags #{}}
                              :orders/add {:order/shop-id bobs-shop :order/note \"x\"})
     ;=> {:handler :orders/add :answer :not-found …}   ; doorman's explain-refusal

     (study.tools/explain-setting [:server :ping-every-ms])
     ;=> {:path [:server :ping-every-ms] :shape :int :doc \"The settings, every key required …\"}"
  (:require
   [logbook.api :as logbook]
   [bridge.api :as bridge]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.util :as malli-util]
   [doorman.api :as doorman]
   [wire.api :as wire]))

(def asker-shape
  "Who a tool asks as, every key required, as doorman's explain-refusal
   takes it: the person (nil for nobody signed in), the visitor (nil
   unless a visitor's session) and the session's flags (#{} for none)."
  [:map {:closed true}
   [:person-id [:maybe :uuid]]
   [:visitor-id [:maybe :uuid]]
   [:session-flags [:set simple-keyword?]]])

(defn- check-asker [asker]
  (when-let [explanation (malli/explain asker-shape asker)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The asker has problems: " (pr-str errors))
                      {:type :groundwork.server.main/bad-asker :errors errors}))))
  asker)

(defn- make-stand-in-session-step
  "In place of doorman's find-session step: the asker given, not a
   cookie's, in the context as doorman's step puts it, and in the
   message's log."
  [{:keys [person-id visitor-id session-flags]}]
  {:name  :groundwork/stand-in-session
   :enter (fn [context]
            (-> context
                (assoc :doorman/person-id person-id
                       :doorman/visitor-id visitor-id
                       :doorman/session-flags session-flags)
                (update :bridge/log logbook/with-context
                        (cond-> {} person-id (assoc :person-id person-id) visitor-id (assoc :visitor-id visitor-id)))))})

(defn- turn-params-into-json-data
  "Each named message's params as the browser sends them, JSON data, by
   its handler's :params-shape; params that don't fit are left as they
   are, so bridge answers their errors."
  [wire handlers message]
  (into {} (map (fn [[message-name params]]
                  (let [shape (get-in handlers [message-name :params-shape])]
                    [message-name (if shape
                                    (let [{:keys [json-data errors]} (wire/turn-shaped-data-into-json-data-or-errors wire shape params)]
                                      (if errors params json-data))
                                    params)])))
        message))

(defn- make-ask-as-person
  [{:keys [make-ready-handlers wire log handlers allowed-origin]}]
  (fn [message asker]
    (let [ready (make-ready-handlers (make-stand-in-session-step (check-asker asker)))]
      (bridge/answer-message ready :no-rate-limits
                               {:request-method :post :uri "/" :headers {"origin" allowed-origin}}
                               (turn-params-into-json-data wire handlers message)
                               {:wire wire :log log}))))

(defn- make-why-refused
  [{:keys [pool handlers tables scope-kinds]}]
  (fn [asker handler-name params]
    (doorman/explain-refusal {:pool pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                             (check-asker asker) handler-name params)))

(defn- make-explain-setting
  [{:keys [settings-shape docs-of-parts]}]
  (fn [path]
    (let [path   (vec path)
          shape  (if (empty? path) settings-shape (malli-util/get-in settings-shape path))
          source (get docs-of-parts (first path))]
      {:path  path
       :shape (some-> shape malli/form)
       :doc   (cond
                (nil? shape)  "No setting is at this path."
                (empty? path) (:doc (meta (:settings docs-of-parts)))
                :else         (:doc (meta source)))})))

(defn make-tools
  "study's :tools, from the running app's parts: :make-ready-handlers (fn
   [find-session-step]) the app's handlers made ready with that step in
   place of doorman's; :wire, :log and :pool the app's; :handlers, :tables
   and :scope-kinds the app's own, as it handed them in; :allowed-origin
   the :sessions settings'; :settings-shape groundwork's; :docs-of-parts each
   top settings key's shape var, and :settings the whole one's."
  [parts]
  {:ask-as-person   {:doc      (str "(ask-as-person message asker): the message answered as the asker, through every check a "
                                    "browser's message gets: its params' shape, doorman's access, the gates, the answer's shape; "
                                    "a write runs and is saved. message is {name params}, the params as data with their types; "
                                    "asker is {:person-id :visitor-id :session-flags}. No rate limits.")
                     :function (make-ask-as-person parts)}
   :why-refused     {:doc      (str "(why-refused asker handler-name params): why the asker's message would be refused, or "
                                    "not, as data (doorman's explain-refusal): the check's answer, the record, the asker's "
                                    "roles and actions there, each sentence's parts, the denies. Read-only.")
                     :function (make-why-refused parts)}
   :explain-setting {:doc      (str "(explain-setting path): what goes at a place in groundwork's settings, "
                                    "[:server :ping-every-ms]: {:path :shape :doc}, the shape there and the text of "
                                    "the part's shape. [] for the whole.")
                     :function (make-explain-setting parts)}})
