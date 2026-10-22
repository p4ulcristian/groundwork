(ns groundwork.server.main.page
  "The page shell: the one HTML page every page address answers, which
   loads the app's code and styles and carries what the browser needs to
   start, as JSON in a script tag:

     <script type=\"application/json\" id=\"groundwork-settings\">
       {\"version\":\"2026-10-22.1\",
        \"addresses\":{\"http\":\"/groundwork/http\",\"sse\":\"/groundwork/sse\",\"ws\":\"/groundwork/ws\"},
        \"sse-reconnect-after-ms\":3000,
        \"ws-reopen-after-ms\":1000,
        \"doorbell\":{\"gather-changes-ms\":50,\"keep-unwatched-answers-ms\":300000},
        \"pages\":{\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}}}
     </script>

   The browser reads it (groundwork.browser.main); the pages, the
   addresses, the sse and ws reopen delays and the question store's
   settings (doorbell's) are said once, on the server.

   Fixed, not settings: the shell is never cached (no-store), so a new
   version's scripts are what the next load gets; nosniff, since every
   file goes out with its type; UTF-8; the scripts deferred; the app's
   element is #app. The rest is the :page settings: the title, the
   language, the viewport, the content security policy, the referrer
   policy and the frame options."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [wire.api :as wire]))

(defn- escape-html [text]
  (-> (str text) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- escape-json-for-script
  "JSON inside <script>: a < can't end the tag or open a comment."
  [json-text]
  (str/replace json-text "<" "\\u003c"))

(defn- list-built-files
  "The files a release build's manifest in <files-folder>/<kind> lists, as
   addresses; none throws: build first."
  [files-folder kind]
  (let [manifest (io/file files-folder kind "manifest.edn")]
    (when-not (.exists manifest)
      (throw (ex-info (str "No browser build in " files-folder ": run the release build first (clojure -X:browser-release)")
                      {:type :groundwork.server.main/not-built :files-folder files-folder})))
    (mapv #(str "/" kind "/" (:output-name %)) (edn/read-string (slurp manifest)))))

(defn list-scripts
  "The page's scripts: while watching (:browser-build :watch) /js/main.js,
   showroom's watched build; built, the files the release build's manifest
   lists, their names hashed."
  [{:keys [browser-build] {{:keys [folder]} :files} :page}]
  (if (= :watch browser-build) ["/js/main.js"] (list-built-files folder "js")))

(defn list-stylesheets
  "The page's stylesheet bundle: while watching /css/main.css; built, the
   hashed one its manifest lists."
  [{:keys [browser-build] {{:keys [folder]} :files} :page}]
  (if (= :watch browser-build) ["/css/main.css"] (list-built-files folder "css")))

(defn make-browser-settings
  "What the browser needs to start: the version, the three addresses and
   the sse and ws reopen delays (from the :server settings), the question
   store's settings (:doorbell), the pages."
  [{:keys [version doorbell] {:keys [addresses sse-reconnect-after-ms ws-reopen-after-ms]} :server} pages]
  {:version version :addresses addresses :sse-reconnect-after-ms sse-reconnect-after-ms
   :ws-reopen-after-ms ws-reopen-after-ms :doorbell doorbell :pages pages})

(defn make-page-html [{{:keys [title language viewport]} :page} stylesheets scripts browser-settings]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape-html language) "\">\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"" (escape-html viewport) "\">\n"
       "<title>" (escape-html title) "</title>\n"
       (str/join (map #(str "<link rel=\"stylesheet\" href=\"" (escape-html %) "\">\n") stylesheets))
       "<script type=\"application/json\" id=\"groundwork-settings\">"
       (escape-json-for-script (wire/turn-data-into-json-text browser-settings))
       "</script>\n"
       (str/join (map #(str "<script src=\"" (escape-html %) "\" defer></script>\n") scripts))
       "</head>\n<body>\n<div id=\"app\"></div>\n</body>\n</html>\n"))

(defn- make-headers [{{:keys [content-security-policy referrer-policy frame-options]} :page}]
  (cond-> {"content-type"           "text/html; charset=utf-8"
           "cache-control"          "no-store"
           "x-content-type-options" "nosniff"
           "referrer-policy"        referrer-policy}
    (string? content-security-policy) (assoc "content-security-policy" content-security-policy)
    (string? frame-options)           (assoc "x-frame-options" frame-options)))

(defn make-page-handler
  "A Ring handler answering every page address with the shell, made once."
  [settings browser-settings]
  (let [response {:status 200 :headers (make-headers settings)
                  :body   (make-page-html settings (list-stylesheets settings) (list-scripts settings) browser-settings)}]
    (fn [_request] response)))
