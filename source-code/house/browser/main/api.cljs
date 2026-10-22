(ns house.browser.main.api
  "The browser's one start: the browser libraries put together. The
   server's start is house.server.main, whose page shell carries what
   this reads: the version, the three addresses, the sse and ws reopen
   delays, the question store's settings, the browser's wire settings and
   the pages.

     (def app (main/start-app! {:shapes-by-name shapes-by-name     ; the app's .cljc, by message name
                                :sse-handlers   {}                 ; the app's own sse messages, by name
                                :on-page        show-page!         ; (on-page {:name … :path-params …})
                                :exact-decimals wire.big/big}))    ; or nil: a decimal stays its text

     (doorbell/watch-question! (:store app) [:orders/of-shop {:shop/id id}] show-orders!)

     ;; a ws line, when the app wants one: the address and the delay from the page, the app's wire
     (let [{:keys [addresses ws-reopen-after-ms]} (:settings app)]
       (mailroom/open-ws-line! (:ws addresses) shapes-by-name {:chat/said show-said!}
                               {:wire (:wire app) :reopen-after-ms ws-reopen-after-ms}))

   start-app! makes the app's one wire in the browser, from the page's wire
   settings and the :exact-decimals it is handed (a function, so not in the
   page), and passes it to mailroom (no library makes its own); makes the
   question store (doorbell's, with the :doorbell
   settings from the page), opens the sse line (doorbell's messages,
   the app's own, and the version: a page older than the server reloads;
   reopened after :sse-reconnect-after-ms when the server answered with an
   error, and every watched question asked again after any break), and
   follows the address bar. Nothing can ask before it: the store is made
   here. The app's shapes-by-name has both shapes for each of its names;
   house's own (:house/version, doorbell's) are added here."
  (:require
   [doorbell.api :as doorbell]
   [mailroom.api :as mailroom]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [wire.api :as wire]))

(def ^:private version-shapes-by-name
  {:house/version {:params-shape nil :answer-shape :string}})

(defn read-settings-from-page
  "What the server put in the page: {:version :addresses
   :sse-reconnect-after-ms :ws-reopen-after-ms :doorbell :wire :pages}.
   Read by the browser's JSON.parse, keys as keywords with their namespace
   (\"orders/page\" is :orders/page): the wire is made from what this
   reads, so there is none yet."
  []
  (if-let [element (js/document.getElementById "house-settings")]
    (js->clj (js/JSON.parse (.-textContent element)) :keywordize-keys true)
    (throw (ex-info "The page has no house-settings: it didn't come from house.server.main's page shell"
                    {:type :house.browser.main/no-settings}))))

(defn- reload-when-older! [{:keys [version]} server-version]
  ;; once per version: two servers of different versions (a rolling deploy)
  ;; would otherwise reload the page back and forth
  (when (and (not= version server-version)
             (not= server-version (js/sessionStorage.getItem "house-reloaded-for")))
    (js/sessionStorage.setItem "house-reloaded-for" server-version)
    (js/location.reload)))

(def start-options-shape
  "start-app!'s options, every key required.
   :shapes-by-name: the app's shapes, by message name (mailroom's).
   :sse-handlers: the app's own sse messages' handlers, by name.
   :on-page: (on-page {:name … :path-params …}) on every move.
   :exact-decimals: the browser's wire setting the page can't carry, a
   function: wire.big/big (big.js, in the app's package.json), or nil to
   keep a decimal as its text."
  [:map {:closed true}
   [:shapes-by-name map?]
   [:sse-handlers map?]
   [:on-page fn?]
   [:exact-decimals [:maybe map?]]])

(defn- check-start-options [options]
  (when-let [explanation (malli/explain start-options-shape options)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The options have problems: " (pr-str errors))
                      {:type :house.browser.main/bad-options :errors errors}))))
  options)

(defn start-app!
  "Starts the browser half (see the namespace). The options are checked
   first, every key required. Returns {:settings :wire :store :sse-line},
   :wire the app's one wire in the browser."
  [options]
  (let [{:keys [shapes-by-name sse-handlers on-page exact-decimals]} (check-start-options options)
        {:keys [addresses pages sse-reconnect-after-ms] store-settings :doorbell :as settings} (read-settings-from-page)
        wire           (wire/make-wire-from-settings (assoc (:wire settings) :exact-decimals exact-decimals))
        shapes-by-name (merge shapes-by-name doorbell/message-shapes-by-name version-shapes-by-name)
        store          (doorbell/make-question-store #(mailroom/send-message! (:http addresses) shapes-by-name % {:wire wire})
                                                     store-settings)
        sse-line       (mailroom/open-sse-line! (:sse addresses) shapes-by-name
                                                 (merge sse-handlers
                                                        (doorbell/make-sse-handlers store)
                                                        {:house/version #(reload-when-older! settings %)})
                                                 {:wire               wire
                                                  :on-open            (fn [again?] (when again? (doorbell/hear-missed-changes! store)))
                                                  :reconnect-after-ms sse-reconnect-after-ms})]
    (mailroom/start-following-address! pages on-page)
    {:settings settings :wire wire :store store :sse-line sse-line}))
