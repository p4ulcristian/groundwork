(ns groundwork.browser.main.api
  "The browser's one start: the browser libraries put together. The
   server's start is groundwork.server.main, whose page shell carries what
   this reads: the version, the three addresses, the sse and ws reopen
   delays and the pages.

     (def app (main/start-app! {:shapes-by-name shapes-by-name     ; the app's .cljc, by message name
                                :sse-handlers   {}                 ; the app's own sse messages, by name
                                :on-page        show-page!}))      ; (on-page {:name … :path-params …})

     (sync/watch-question! (:store app) [:orders/of-shop {:shop/id id}] show-orders!)

     ;; a ws line, when the app wants one: the address and the delay from the page
     (let [{:keys [addresses ws-reopen-after-ms]} (:settings app)]
       (mailroom/open-ws-line! (:ws addresses) shapes-by-name {:chat/said show-said!}
                               {:reopen-after-ms ws-reopen-after-ms}))

   start-app! makes the question store, opens the sse line (sync's messages,
   the app's own, and the version: a page older than the server reloads;
   reopened after :sse-reconnect-after-ms when the server answered with an
   error, and every watched question asked again after any break), and
   follows the address bar. Nothing can ask before it: the store is made
   here. The app's shapes-by-name has both shapes for each of its names;
   Groundwork's own (:groundwork/version, sync's) are added here."
  (:require
   [groundwork.browser.sync.api :as sync]
   [mailroom.api :as mailroom]
   [wire.api :as wire]))

(def ^:private version-shapes-by-name
  {:groundwork/version {:params-shape nil :answer-shape :string}})

(defn read-settings-from-page
  "What the server put in the page: {:version :addresses
   :sse-reconnect-after-ms :ws-reopen-after-ms :pages}."
  []
  (if-let [element (js/document.getElementById "groundwork-settings")]
    (wire/turn-json-text-into-data (.-textContent element))
    (throw (ex-info "The page has no groundwork-settings: it didn't come from groundwork.server.main's page shell"
                    {:type :groundwork.browser.main/no-settings}))))

(defn- reload-when-older! [{:keys [version]} server-version]
  ;; once per version: two servers of different versions (a rolling deploy)
  ;; would otherwise reload the page back and forth
  (when (and (not= version server-version)
             (not= server-version (js/sessionStorage.getItem "groundwork-reloaded-for")))
    (js/sessionStorage.setItem "groundwork-reloaded-for" server-version)
    (js/location.reload)))

(defn start-app!
  "Starts the browser half (see the namespace). Returns {:settings :store :sse-line}."
  [{:keys [shapes-by-name sse-handlers on-page]}]
  (let [{:keys [addresses pages sse-reconnect-after-ms] :as settings} (read-settings-from-page)
        shapes-by-name (merge shapes-by-name sync/sse-shapes-by-name version-shapes-by-name)
        store          (sync/make-question-store #(mailroom/send-message! (:http addresses) shapes-by-name %))
        sse-line       (mailroom/open-sse-line! (:sse addresses) shapes-by-name
                                                 (merge sse-handlers
                                                        (sync/make-sse-handlers store)
                                                        {:groundwork/version #(reload-when-older! settings %)})
                                                 {:on-open            (fn [again?] (when again? (sync/hear-missed-changes! store)))
                                                  :reconnect-after-ms sse-reconnect-after-ms})]
    (mailroom/start-following-address! pages on-page)
    {:settings settings :store store :sse-line sse-line}))
