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
   [groundwork.server.main.answer-as :as answer-as]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.util :as malli-util]
   [doorman.api :as doorman]
   [doorman.repl :as doorman-repl]
   [wire.api :as wire]))

(def asker-shape
  "Who a tool asks as, every key required, as doorman's explain-refusal
   and doorman.repl's step take it: the person (nil for nobody signed in),
   the visitor (nil unless a visitor's session) and the session's flags
   (#{} for none). Only the REPL names a person: the app's code finds the
   asker in a request."
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
  "answer-as's answer-message, on the handlers made ready with
   doorman.repl's step asking as the asker in place of the session step,
   no rate limits and no request: a REPL's message comes with none. It is
   counted on the app's meter, as every answered message is."
  [{:keys [make-ready-handlers wire log meter metric-buckets handlers]}]
  (fn [message asker]
    (let [answer (answer-as/make-answer-message
                  {:ready-handlers (make-ready-handlers (doorman-repl/make-step-asking-as (check-asker asker)))
                   :limiter        :no-rate-limits
                   :wire           wire
                   :log            log
                   :meter          meter
                   :metric-buckets metric-buckets})]
      (answer (turn-params-into-json-data wire handlers message) nil))))

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
   place of doorman's; :wire, :log, :meter and :pool the app's;
   :metric-buckets the :server settings'; :handlers, :tables
   and :scope-kinds the app's own, as it handed them in; :settings-shape groundwork's; :docs-of-parts each
   top settings key's shape var, and :settings the whole one's."
  [parts]
  {:ask-as-person   {:doc      (str "(ask-as-person message asker): the message answered as the asker, through every check a "
                                    "browser's message gets: its params' shape, doorman's access, the gates, the answer's shape; "
                                    "a write runs and is saved. message is {name params}, the params as data with their types; "
                                    "asker is {:person-id :visitor-id :session-flags}. No rate limits; counted on the app's meter.")
                     :function (make-ask-as-person parts)}
   :why-refused     {:doc      (str "(why-refused asker handler-name params): why the asker's message would be refused, or "
                                    "not, as data (doorman's explain-refusal): the check's answer, the record, the asker's "
                                    "roles and actions there, each sentence's parts, the denies. Read-only.")
                     :function (make-why-refused parts)}
   :explain-setting {:doc      (str "(explain-setting path): what goes at a place in groundwork's settings, "
                                    "[:server :ping-every-ms]: {:path :shape :doc}, the shape there and the text of "
                                    "the part's shape. [] for the whole.")
                     :function (make-explain-setting parts)}})
