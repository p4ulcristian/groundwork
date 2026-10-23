(ns groundwork.browser.main.api
  "The browser's one start: the browser libraries put together. The
   server's start is groundwork.server.main, whose page shell carries what
   this reads: the version, the three addresses, the sse and ws reopen
   delays, the question store's settings, the browser's wire settings and
   the pages.

     (def app (main/start-app! {:shapes-by-name     shapes-by-name     ; the app's .cljc, by message name
                                :sse-handlers       {}                 ; the app's own sse messages, by name
                                :questions-of-pages {:orders/page (fn [{:keys [path-params]}]   ; every page's, parlor's
                                                                    [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])
                                                     :about/page  (fn [_] [])}
                                :on-answer-failed   [:shop/answer-failed]  ; or nil: parlor's event for a failed answer
                                :on-move            nil                    ; or an event, given {:from page :to page}
                                :query-shapes-of-pages {:orders/page nil   ; every page's, parlor's: a malli map, or nil for text
                                                        :about/page  nil}
                                :placeholder        nil                    ; or (fn [page status]) while a page's :module loads
                                :exact-decimals     wire.big/big}))        ; or nil: a decimal stays its text
     (parlor/mount-root! \"app\" root)   ; the shell's element is #app; again after a save
     ;; root draws [parlor/show-page draw-page] where the page goes

     ;; screens read parlor's subscriptions and dispatch its events
     @(rf/subscribe [:parlor/answer [:orders/of-shop {:shop/id shop-id}]])
     @(rf/subscribe [:parlor/can-i? :orders/move [:shop shop-id]])   ; from :doorman/my-actions

     ;; a ws line, when the app wants one: the address and the delay from the page, the app's wire
     (let [{:keys [addresses ws-reopen-after-ms]} (:settings app)]
       (mailroom/open-ws-line! (:ws addresses) shapes-by-name {:chat/said show-said!}
                               {:wire (:wire app) :reopen-after-ms ws-reopen-after-ms}))

   start-app! makes the app's one wire in the browser, from the page's wire
   settings and the :exact-decimals it is handed (a function, so not in the
   page), and passes it to mailroom (no library makes its own); makes the
   question store (doorbell's, with the :doorbell settings from the page);
   opens the sse line (doorbell's messages, the app's own, and the version:
   a page older than the server reloads; reopened after
   :sse-reconnect-after-ms when the server answered with an error, and
   every watched question asked again after any break); then starts
   parlor, re-frame over the store (dispatch-sync [:parlor/start …]), with
   the store, the app's :query-shapes-of-pages and :placeholder, the
   page's languages and language, showroom's load-module! with each
   module's stylesheet bundle from the page, the write's send function (the one-time key parlor makes for
   each write in the idempotency-key header, groundwork.server.main.run-once on
   the server), the page's :retry-writes, the page's pages, the app's
   :questions-of-pages, :on-answer-failed and :on-move, and
   [:doorman/my-actions {}], the server's handler of doorman's, as its
   :actions-question. parlor follows the address bar. Nothing can ask
   before it: the store is made here. The app draws its root itself, with
   parlor's mount-root!, into the shell's #app. The app's shapes-by-name
   has both shapes for each of its names; groundwork's own (:groundwork/version,
   :groundwork/browser-error, :doorman/my-actions, doorbell's) are added here.

   It also listens, once for the page, for the uncaught errors and the
   unhandled promise rejections, and sends each to the server as
   {:groundwork/browser-error {:message … :stack … :url …}}, where it is logged
   at :error with the server's errors (its rate limit is the server's
   :errors settings'). A failure sending one is dropped: it can't be
   sent either.

   start-visitor-session! gives a visitor (someone not signed in) a
   session of their own, the first time the app keeps something for them
   (a cart): a POST to the page's :visitor-path, whose answer sets the
   cookie (groundwork.server.main.visitors)."
  (:require
   [doorbell.api :as doorbell]
   [doorman.api :as doorman]
   [mailroom.api :as mailroom]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [parlor.api] ; registers parlor's events and subscriptions, which the start dispatches
   [re-frame.core :as rf]
   [showroom.api :as showroom]
   [wire.api :as wire]))

(def ^:private groundwork-shapes-by-name
  {:groundwork/version       {:params-shape nil :answer-shape :string}
   :groundwork/browser-error {:params-shape [:map {:closed true}
                                        [:message :string]
                                        [:stack [:maybe :string]]
                                        [:url :string]]
                         :answer-shape :boolean}
   ;; doorman's, on both sides (doorman.api's browser half)
   :doorman/my-actions  {:params-shape [:map {:closed true}]
                         :answer-shape doorman/my-actions-answer-shape}})

(def ^:private write-key-header
  "The header a write's one-time key travels in; the server's
   groundwork.server.main.run-once reads it."
  "idempotency-key")

(def ^:private actions-question
  "parlor's :actions-question: the server's :doorman/my-actions."
  [:doorman/my-actions {}])

(defn read-settings-from-page
  "What the server put in the page: {:version :addresses
   :sse-reconnect-after-ms :ws-reopen-after-ms :doorbell :wire
   :retry-writes :visitor-path :languages :language :module-stylesheets :pages}.
   Read by the browser's JSON.parse, keys as keywords with their namespace
   (\"orders/page\" is :orders/page): the wire is made from what this
   reads, so there is none yet."
  []
  (if-let [element (js/document.getElementById "groundwork-settings")]
    (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)
    (throw (ex-info "The page has no groundwork-settings: it didn't come from groundwork.server.main's page shell"
                    {:type :groundwork.browser.main/no-settings}))))

(defn- reload-when-older! [{:keys [version]} server-version]
  ;; once per version: two servers of different versions (a rolling deploy)
  ;; would otherwise reload the page back and forth
  (when (and (not= version server-version)
             (not= server-version (js/sessionStorage.getItem "groundwork-reloaded-for")))
    (js/sessionStorage.setItem "groundwork-reloaded-for" server-version)
    (js/location.reload)))

(def start-options-shape
  "start-app!'s options, every key required.
   :query-shapes-of-pages: parlor's, every page's query shape, by page
   name, one for each of the page's pages and no other: a malli map the
   query's text values are read by, or nil to keep them text.
   :placeholder: parlor's, a component (fn [page status]) drawn by
   parlor's show-page while a page's :module loads; nil when no page
   names one.
   :shapes-by-name: the app's shapes, by message name (mailroom's).
   :sse-handlers: the app's own sse messages' handlers, by name.
   :questions-of-pages: parlor's, every page's declaration of the
   questions it needs while open, by page name, one for each of the
   page's pages and no other: (fn [page]) -> questions.
   :on-answer-failed: parlor's, an event given the question and the
   failed answer, or nil for none.
   :on-move: parlor's, an event given {:from page :to page} after every
   move, or nil for none.
   :exact-decimals: the browser's wire setting the page can't carry, a
   function: wire.big/big (big.js, in the app's package.json), or nil to
   keep a decimal as its text.
   parlor checks its three again, closer, at its start."
  [:map {:closed true}
   [:shapes-by-name map?]
   [:sse-handlers map?]
   [:questions-of-pages [:map-of :qualified-keyword fn?]]
   [:on-answer-failed [:maybe vector?]]
   [:on-move [:maybe vector?]]
   [:query-shapes-of-pages [:map-of :qualified-keyword :any]]
   [:placeholder [:maybe fn?]]
   [:exact-decimals [:maybe map?]]])

(defn- check-start-options [options]
  (when-let [explanation (malli/explain start-options-shape options)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The options have problems: " (pr-str errors))
                      {:type :groundwork.browser.main/bad-options :errors errors}))))
  options)

(defonce ^:private sending-errors? (atom false))

(defn- describe-error
  "The message and stack of what was thrown or rejected: an Error's own,
   else its text and no stack."
  [thrown fallback-message]
  (if (instance? js/Error thrown)
    {:message (str (.-message thrown)) :stack (some-> (.-stack thrown) str)}
    {:message (if (some? thrown) (str thrown) fallback-message) :stack nil}))

(defn- send-errors-to-server!
  "Once for the page (a hot reload starts the app again): every uncaught
   error and unhandled rejection is sent as a :groundwork/browser-error."
  [send!]
  (when (compare-and-set! sending-errors? false true)
    (let [send-error! (fn [{:keys [message stack]}]
                        (-> (send! {:groundwork/browser-error {:message message :stack stack :url (str js/location.href)}})
                            (.catch (fn [_] nil))))]
      (js/window.addEventListener "error" #(send-error! (describe-error (.-error %) (str (.-message %)))))
      (js/window.addEventListener "unhandledrejection" #(send-error! (describe-error (.-reason %) "A promise was rejected"))))))

(defn start-app!
  "Starts the browser half (see the namespace), parlor last. The options
   are checked first, every key required. Returns {:settings :wire :store
   :sse-line}, :wire the app's one wire in the browser."
  [options]
  (let [{:keys [shapes-by-name sse-handlers questions-of-pages on-answer-failed on-move exact-decimals
                query-shapes-of-pages placeholder]}
        (check-start-options options)
        {:keys [addresses pages sse-reconnect-after-ms languages language module-stylesheets] store-settings :doorbell :as settings}
        (read-settings-from-page)
        module-stylesheets (update-keys module-stylesheets name)
        wire           (wire/make-wire-from-settings (assoc (:wire settings) :exact-decimals exact-decimals))
        shapes-by-name (merge shapes-by-name doorbell/message-shapes-by-name groundwork-shapes-by-name)
        send!          #(mailroom/send-message! (:http addresses) shapes-by-name % {:wire wire :headers {}})
        send-write!    (fn [message once-key]
                         (mailroom/send-message! (:http addresses) shapes-by-name message
                                                 {:wire wire :headers {write-key-header once-key}}))
        _              (send-errors-to-server! send!)
        store          (doorbell/make-question-store send! store-settings)
        sse-line       (mailroom/open-sse-line! (:sse addresses) shapes-by-name
                                                 (merge sse-handlers
                                                        (doorbell/make-sse-handlers store)
                                                        {:groundwork/version #(reload-when-older! settings %)})
                                                 {:wire               wire
                                                  :on-open            (fn [again?] (when again? (doorbell/hear-missed-changes! store)))
                                                  :reconnect-after-ms sse-reconnect-after-ms})]
    (rf/dispatch-sync [:parlor/start {:store              store
                                      :send-write!        send-write!
                                      :retry-writes       (:retry-writes settings)
                                      :pages              pages
                                      :questions-of-pages questions-of-pages
                                      :actions-question   actions-question
                                      :actions-answer-shape doorman/my-actions-answer-shape
                                      :wire               wire
                                      :on-answer-failed   on-answer-failed
                                      :on-move            on-move
                                      :query-shapes-of-pages query-shapes-of-pages
                                      :languages          {:dictionary languages :language-of-page language}
                                      :load-module!       #(showroom/load-module! module-stylesheets %)
                                      :placeholder        placeholder}])
    {:settings settings :wire wire :store store :sse-line sse-line}))

(defn start-visitor-session!
  "A session for a visitor, the first time the app keeps something for
   them: a POST to the page's :visitor-path (the server's :visitors
   settings), from this page, whose answer sets the session cookie. A
   promise of the visitor's id, a UUID, which the rows kept for them name;
   nil when someone is signed in already. Fails on any answer but 200.
   app is what start-app! returned."
  [{:keys [settings]}]
  (-> (js/fetch (:visitor-path settings) #js {:method "POST" :credentials "same-origin"})
      (.then (fn [response]
               (if (.-ok response)
                 (.json response)
                 (throw (ex-info (str "The visitor's session wasn't started: " (.-status response))
                                 {:type :groundwork.browser.main/no-visitor-session :status (.-status response)})))))
      (.then (fn [answer] (some-> (aget answer "visitor-id") parse-uuid)))))
