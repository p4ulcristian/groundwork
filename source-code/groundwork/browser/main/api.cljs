(ns groundwork.browser.main.api
  "The browser's one start: the browser libraries put together. The
   server's start is groundwork.server.main, whose page shell carries what
   this reads: the version, the three addresses and the pages.

     (def app (main/start-app! {:shapes-by-name shapes-by-name     ; the app's .cljc, by message name
                                :sse-handlers   {}                 ; the app's own sse messages, by name
                                :on-page        show-page!}))      ; (on-page {:name … :path-params …})

     (sync/watch-question! (:store app) [:orders/of-shop {:shop/id id}] show-orders!)

   start-app! makes the question store, opens the sse line (sync's messages,
   the app's own, and the version: a page older than the server reloads),
   and follows the address bar. Nothing can ask before it: the store is made here."
  (:require
   [groundwork.browser.sync.api :as sync]
   [groundwork.browser.transport.api :as transport]
   [wire.api :as wire]))

(defn read-settings-from-page
  "What the server put in the page: {:version :addresses :pages}."
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
  (let [{:keys [addresses pages] :as settings} (read-settings-from-page)
        store    (sync/make-question-store #(transport/send-message! (:http addresses) shapes-by-name %))
        sse-line (transport/open-sse-line! (:sse addresses) shapes-by-name
                                           (merge sse-handlers
                                                  (sync/make-sse-handlers store)
                                                  {:groundwork/version #(reload-when-older! settings %)})
                                           {:on-open (fn [again?] (when again? (sync/hear-missed-changes! store)))})]
    (transport/start-following-address! pages on-page)
    {:settings settings :store store :sse-line sse-line}))
