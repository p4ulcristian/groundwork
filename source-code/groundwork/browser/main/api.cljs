(ns groundwork.browser.main.api
  "The browser's one start: the browser libraries put together. The
   server's start is groundwork.server.main, whose page shell carries what
   this reads: the version, the three addresses, the sse and ws reopen
   delays, the question store's settings, the browser's groundwork-shaped-json settings and
   the pages.

     (def app (main/start-app! {:shapes-by-name     shapes-by-name     ; the app's .cljc, by message name
                                :sse-handlers       {}                 ; the app's own sse messages, by name
                                :questions-of-pages {:orders/page (fn [{:keys [path-params]}]   ; every page's, groundwork-screens's
                                                                    [[:orders/of-shop {:shop/id (parse-uuid (:shop-id path-params))}]])
                                                     :about/page  (fn [_] [])}
                                :on-answer-failed   [:shop/answer-failed]  ; or nil: groundwork-screens's event for a failed answer
                                :on-signed-out      [:shop/to-login]       ; or nil: groundwork-screens's, once, with the page to return to
                                :on-move            nil                    ; or an event, given {:from page :to page}
                                :on-line-change     [:shop/line-changed]   ; or nil: given {:line :open}, :broken or :back
                                :query-shapes-of-pages {:orders/page nil   ; every page's, groundwork-screens's: a malli map, or nil for text
                                                        :about/page  nil}
                                :placeholder        nil                    ; or (fn [page status]) while a page's :module loads
                                :exact-decimals     groundwork-shaped-json.big/big}))        ; or nil: a decimal stays its text
     (screens/mount-root! \"app\" root)   ; the shell's element is #app; again after a save
     ;; root draws [screens/show-page draw-page] where the page goes

     ;; screens read groundwork-screens's subscriptions and dispatch its events
     @(rf/subscribe [:groundwork-screens/answer [:orders/of-shop {:shop/id shop-id}]])
     @(rf/subscribe [:groundwork-screens/can-i? :orders/move [:shop shop-id]])   ; from :groundwork-access/my-actions
     (screens/tr :orders.cart/add {:count 3})   ; a text in the page's language, in a view
     (screens/tr (:groundwork-bridge/refused error) (:groundwork-bridge/data error))   ; a refusal's text: its reason is the key

     ;; a ws line, when the app wants one: the address and the delay from the page, the app's shaper
     (let [{:keys [addresses ws-reopen-after-ms]} (:settings app)]
       (bridge/open-ws-line! (:ws addresses) shapes-by-name {:chat/said show-said!}
                               {:shaper (:shaped-json app) :reopen-after-ms ws-reopen-after-ms}))

   start-app! makes the app's one shaper in the browser, from the page's groundwork-shaped-json
   settings and the :exact-decimals it is handed (a function, so not in the
   page), and passes it to bridge (no library makes its own); makes the
   question store (groundwork-live-updates's, with the :live-updates settings from the page);
   opens the sse line (groundwork-live-updates's messages, the app's own, and the version:
   a page older than the server reloads; reopened after
   :sse-reconnect-after-ms when the server answered with an error, and
   every watched question asked again after any break; the app's
   :on-line-change dispatched with {:line :open} when it first opens,
   {:line :broken} once per break, {:line :back} when it opens again: an
   offline banner's status); then starts
   groundwork-screens, re-frame over the store (dispatch-sync [:groundwork-screens/start …]), with
   the store, the app's :query-shapes-of-pages and :placeholder, the
   page's languages and language, browser-build's load-module! with each
   module's stylesheet bundle from the page, the write's send function (the
   send's options as groundwork-screens's :groundwork-screens/send wrote them, {:once-key
   :keep-alive?}: the one-time key in the idempotency-key header,
   groundwork.server.main.run-once on the server, and :keep-alive? as
   bridge's, fetch's keepalive), the page's :retry-writes, the page's pages, the app's
   :questions-of-pages, :on-answer-failed and :on-move, and
   [:groundwork-access/my-actions {}], the server's handler of groundwork-access's, as its
   :actions-question, and the app's :on-signed-out with groundwork-access's
   :groundwork-access/signed-out as its :signed-out-reason (nil for both when the
   app has none). groundwork-screens follows the address bar. Nothing can ask
   before it: the store is made here. The app draws its root itself, with
   groundwork-screens's mount-root!, into the shell's #app. The app's shapes-by-name
   has the three shapes for each of its names (:params-shape,
   :answer-shape, :refusals: its handler's own reasons, {} for none);
   groundwork-access's reasons are added to each here (groundwork-access's
   put-refusals-into-shapes), as groundwork-access adds them to every handler on the
   server, and groundwork's run-once reasons to each, since a write may
   give them; groundwork's own names (:groundwork/version,
   :groundwork/browser-error, :groundwork-access/my-actions, groundwork-live-updates's) are added
   too.

   It also listens, once for the page, for the uncaught errors and the
   unhandled promise rejections, and sends each to the server as
   {:groundwork/browser-error {:kind :uncaught :version … :message … :stack …
   :url …}}, where it is logged at :error with the server's errors, the one
   list the app's monitor reads (its rate limit is the server's :errors
   settings'); report-browser-error! sends one an app's error boundary
   caught the same way, :kind :render. :version is the page's version
   setting. A failure sending one is dropped: it can't be sent either.

   start-visitor-session! gives a visitor (someone not signed in) a
   session of their own, the first time the app keeps something for them
   (a cart): a POST to the page's :visitor-path, whose answer sets the
   cookie (groundwork.server.main.visitors)."
  (:require
   [groundwork-live-updates.api :as live-updates]
   [groundwork-access.api :as access]
   [groundwork-bridge.api :as bridge]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-screens.api] ; registers groundwork-screens's events and subscriptions, which the start dispatches
   [re-frame.core :as rf]
   [groundwork-browser-build.api :as browser-build]
   [groundwork-shaped-json.api :as shaped-json]))

(def ^:private groundwork-shapes-by-name
  {:groundwork/version       {:params-shape nil :answer-shape :string :refusals {}}
   ;; refused only by groundwork-access's find-session step, as on the server (groundwork.server.main.errors)
   :groundwork/browser-error {:params-shape [:map {:closed true}
                                             [:kind [:enum :render :uncaught]]
                                             [:version :string]
                                             [:message :string]
                                             [:stack [:maybe :string]]
                                             [:url :string]]
                              :answer-shape :boolean
                              :refusals     (select-keys access/refusals [:groundwork-access/not-allowed])}})

(def ^:private my-actions-shapes-by-name
  ;; groundwork-access's, on both sides (groundwork-access.api's browser half); its reasons are groundwork-access's, put in below
  {:groundwork-access/my-actions {:params-shape [:map {:closed true}]
                        :answer-shape access/my-actions-answer-shape
                        :refusals     {}}})

(def ^:private run-once-refusals
  "groundwork's own reasons a write may be refused with, the server's
   groundwork.server.main.run-once's: a one-time key that isn't a UUID, or
   one another asker claimed first."
  {:groundwork/once-key-not-a-uuid      nil
   :groundwork/once-key-of-someone-else nil})

(def ^:private daily-cap-refusals
  "groundwork's reason a capped handler refuses with, the server's
   groundwork.server.main.daily-caps's: one of its :daily-caps is spent
   for today, {:retry-after-ms n} until midnight UTC."
  {:groundwork/daily-cap-reached [:map {:closed true} [:retry-after-ms :int]]})

(defn- make-shapes-by-name
  "Every name's three shapes: the app's with groundwork's run-once
   reasons (a write may give them; a name that only reads never does) and
   its daily-cap reason (a capped handler may give it; the browser doesn't
   know which are capped), and :groundwork-access/my-actions, each with groundwork-access's
   reasons put in, then groundwork-live-updates's and groundwork's own."
  [app-shapes-by-name]
  (merge (access/put-refusals-into-shapes
          (merge (update-vals app-shapes-by-name #(update % :refusals merge run-once-refusals daily-cap-refusals))
                 my-actions-shapes-by-name))
         live-updates/message-shapes-by-name
         groundwork-shapes-by-name))

(def ^:private write-key-header
  "The header a write's one-time key travels in; the server's
   groundwork.server.main.run-once reads it."
  "idempotency-key")

(def ^:private actions-question
  "groundwork-screens's :actions-question: the server's :groundwork-access/my-actions."
  [:groundwork-access/my-actions {}])

(defn read-settings-from-page
  "What the server put in the page: {:version :addresses
   :sse-reconnect-after-ms :ws-reopen-after-ms :live-updates :shaped-json
   :retry-writes :visitor-path :languages :language :module-stylesheets :pages}.
   Read by the browser's JSON.parse, keys as keywords with their namespace
   (\"orders/page\" is :orders/page): the shaper is made from what this
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
   :query-shapes-of-pages: groundwork-screens's, every page's query shape, by page
   name, one for each of the page's pages and no other: a malli map the
   query's text values are read by, or nil to keep them text.
   :placeholder: groundwork-screens's, a component (fn [page status]) drawn by
   groundwork-screens's show-page while a page's :module loads; nil when no page
   names one.
   :shapes-by-name: the app's shapes, by message name (bridge's), each
   with its three keys written out: :params-shape, :answer-shape and
   :refusals, its handler's own reasons ({} for none; groundwork-access's and
   groundwork's are added here, never written).
   :sse-handlers: the app's own sse messages' handlers, by name.
   :questions-of-pages: groundwork-screens's, every page's declaration of the
   questions it needs while open, by page name, one for each of the
   page's pages and no other: (fn [page]) -> questions.
   :on-answer-failed: groundwork-screens's, an event given the question and the
   failed answer, or nil for none.
   :on-signed-out: groundwork-screens's, an event dispatched once, the first time an
   answer is refused :groundwork-access/signed-out, given the page to return to
   after signing in ({:page :params :query}), or nil for none.
   :on-move: groundwork-screens's, an event given {:from page :to page} after every
   move, or nil for none.
   :on-line-change: an event given the sse line's status, {:line :open}
   when it first opens, {:line :broken} once per break (bridge's
   :on-break), {:line :back} when it opens again after one; or nil for
   none.
   :exact-decimals: the browser's groundwork-shaped-json setting the page can't carry, a
   function: groundwork-shaped-json.big/big (big.js, in the app's package.json), or nil to
   keep a decimal as its text.
   groundwork-screens checks its three again, closer, at its start."
  [:map {:closed true}
   [:shapes-by-name [:map-of :keyword [:map {:closed true}
                                       [:params-shape :any]
                                       [:answer-shape :any]
                                       [:refusals [:map-of :qualified-keyword :any]]]]]
   [:sse-handlers map?]
   [:questions-of-pages [:map-of :qualified-keyword fn?]]
   [:on-answer-failed [:maybe vector?]]
   [:on-signed-out [:maybe vector?]]
   [:on-move [:maybe vector?]]
   [:on-line-change [:maybe vector?]]
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

(defn- make-error-sender
  "(fn [kind {:keys [message stack]}]): one error sent as a
   :groundwork/browser-error, with the page's version and address; a
   failure sending it is dropped, as it can't be sent either."
  [send! version]
  (fn [kind {:keys [message stack]}]
    (-> (send! {:groundwork/browser-error {:kind kind :version version :message message :stack stack
                                           :url (str js/location.href)}})
        (.catch (fn [_] nil)))))

(defn- send-errors-to-server!
  "Once for the page (a hot reload starts the app again): every uncaught
   error and unhandled rejection is sent as a :groundwork/browser-error,
   :kind :uncaught."
  [send-error!]
  (when (compare-and-set! sending-errors? false true)
    (js/window.addEventListener "error" #(send-error! :uncaught (describe-error (.-error %) (str (.-message %)))))
    (js/window.addEventListener "unhandledrejection" #(send-error! :uncaught (describe-error (.-reason %) "A promise was rejected")))))

(defn start-app!
  "Starts the browser half (see the namespace), groundwork-screens last. The options
   are checked first, every key required. Returns {:settings :shaped-json :store
   :sse-line}, :shaped-json the app's one shaper in the browser, and the error
   sender report-browser-error! takes it for."
  [options]
  (let [{:keys [shapes-by-name sse-handlers questions-of-pages on-answer-failed on-signed-out on-move on-line-change
                exact-decimals query-shapes-of-pages placeholder]}
        (check-start-options options)
        {:keys [addresses pages sse-reconnect-after-ms languages language module-stylesheets] store-settings :live-updates :as settings}
        (read-settings-from-page)
        module-stylesheets (update-keys module-stylesheets name)
        shaper           (shaped-json/make-shaper-from-settings (assoc (:shaped-json settings) :exact-decimals exact-decimals))
        shapes-by-name (make-shapes-by-name shapes-by-name)
        send!          #(bridge/send-message! (:http addresses) shapes-by-name % {:shaper shaper :headers {} :keep-alive? false})
        send-write!    (fn [message {:keys [once-key keep-alive?]}]
                         (bridge/send-message! (:http addresses) shapes-by-name message
                                                 {:shaper shaper :headers {write-key-header once-key} :keep-alive? keep-alive?}))
        tell-line!     #(when on-line-change (rf/dispatch (conj on-line-change {:line %})))
        send-error!    (make-error-sender send! (:version settings))
        _              (send-errors-to-server! send-error!)
        store          (live-updates/make-question-store send! store-settings)
        sse-line       (bridge/open-sse-line! (:sse addresses) shapes-by-name
                                                 (merge sse-handlers
                                                        (live-updates/make-sse-handlers store)
                                                        {:groundwork/version #(reload-when-older! settings %)})
                                                 {:shaper               shaper
                                                  :on-open            (fn [again?]
                                                                        (when again? (live-updates/hear-missed-changes! store))
                                                                        (tell-line! (if again? :back :open)))
                                                  :on-break           #(tell-line! :broken)
                                                  :reconnect-after-ms sse-reconnect-after-ms})]
    (rf/dispatch-sync [:groundwork-screens/start {:store              store
                                      :send-write!        send-write!
                                      :retry-writes       (:retry-writes settings)
                                      :pages              pages
                                      :questions-of-pages questions-of-pages
                                      :actions-question   actions-question
                                      :actions-answer-shape access/my-actions-answer-shape
                                      :shaper               shaper
                                      :on-answer-failed   on-answer-failed
                                      :on-signed-out      on-signed-out
                                      :signed-out-reason  (when on-signed-out :groundwork-access/signed-out)
                                      :on-move            on-move
                                      :query-shapes-of-pages query-shapes-of-pages
                                      :languages          {:settings languages :language-of-page language}
                                      :load-module!       #(browser-build/load-module! module-stylesheets %)
                                      :placeholder        placeholder}])
    {:settings settings :shaper shaper :store store :sse-line sse-line
     :groundwork.browser.main/send-error! send-error!}))

(def report-options-shape
  "report-browser-error!'s report, every key required: :kind, :render
   for an error an app's error boundary caught (:uncaught is the
   listener's own); :error, what was thrown (an Error, its message and
   stack; anything else, its text)."
  [:map {:closed true}
   [:kind [:enum :render :uncaught]]
   [:error :any]])

(defn report-browser-error!
  "Sends one error to the server's one list (groundwork.server.main.errors),
   for an app's error boundary: {:kind :render :error e}, with the page's
   version and address, as the listener sends the uncaught ones. app is
   what start-app! returned. A failure sending it is dropped. Returns nil.

     (main/report-browser-error! app {:kind :render :error e})"
  [app report]
  (when-let [explanation (malli/explain report-options-shape report)]
    (throw (ex-info (str "The report has problems: " (pr-str (malli-error/humanize explanation)))
                    {:type :groundwork.browser.main/bad-report :errors (malli-error/humanize explanation)})))
  ((:groundwork.browser.main/send-error! app) (:kind report) (describe-error (:error report) "An error was reported"))
  nil)

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
