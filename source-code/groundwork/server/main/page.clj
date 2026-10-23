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
        \"wire\":{\"most-shapes-kept\":1000,\"most-keys-kept\":10000},
        \"retry-writes\":{\"times\":2,\"wait-ms\":1000},
        \"visitor-path\":\"/groundwork/visitor\",
        \"pages\":{\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}}}
     </script>

   The browser reads it (groundwork.browser.main); the pages, the
   addresses, the sse and ws reopen delays, the question store's settings
   (doorbell's), the browser's wire settings (:browser-wire, all but
   :exact-decimals, which the app's browser start hands in), the write
   retries, the visitor route's path (:visitors), the languages
   (dictionary's settings) and the request's language, and each module's stylesheet bundle (for showroom's
   load-module!) are said once, on the server. The JSON is written by the
   app's wire.

   The page is in the request's language (dictionary's
   find-language-of-request: the address's prefix, the cookie,
   Accept-Language, the default): <html lang>, and with more than one
   language the alternate links, the same page in each, at the app's
   origin (the :sessions settings' :allowed-origin).

   Fixed, not settings: the shell is never cached (no-store), so a new
   version's scripts are what the next load gets; nosniff, since every
   file goes out with its type; UTF-8; the scripts deferred; the app's
   element is #app. The rest is the :page settings: the title, the
   viewport, the content security policy, the referrer policy and the
   frame options."
  (:require
   [clojure.edn :as edn]
   [dictionary.api :as dictionary]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [wire.api :as wire]))

(defn- escape-html [text]
  (-> (str text) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- escape-json-for-script
  "JSON inside <script>: a < can't end the tag or open a comment."
  [json-text]
  (str/replace json-text "<" "\\u003c"))

(defn- read-built-files
  "The files a release build's manifest in <files-folder>/<kind> lists,
   {module-id address}, the names hashed; none throws: build first."
  [files-folder kind]
  (let [manifest (io/file files-folder kind "manifest.edn")]
    (when-not (.exists manifest)
      (throw (ex-info (str "No browser build in " files-folder ": run the release build first (clojure -X:browser-release)")
                      {:type :groundwork.server.main/not-built :files-folder files-folder})))
    (into {} (map (juxt :module-id #(str "/" kind "/" (:output-name %)))) (edn/read-string (slurp manifest)))))

(defn- is-watching? [{:keys [browser-build]}]
  (map? browser-build))

(defn list-scripts
  "The page's scripts, the module main's: while watching (:browser-build
   {:watch …}) /js/main.js, showroom's watched build; built, the file the
   release build's manifest lists, its name hashed. The modules loaded on
   demand come later, by showroom's loader."
  [{{{:keys [folder]} :files} :page :as settings}]
  (if (is-watching? settings) ["/js/main.js"] [(get (read-built-files folder "js") :main)]))

(defn list-stylesheets
  "The page's stylesheet bundle, main's: while watching /css/main.css;
   built, the hashed one its manifest lists."
  [{{{:keys [folder]} :files} :page :as settings}]
  (if (is-watching? settings) ["/css/main.css"] [(get (read-built-files folder "css") :main)]))

(defn list-module-stylesheets
  "Each module loaded on demand's stylesheet bundle, by name, for
   showroom's load-module!: while watching /css/<module>.css, for each of
   the app's :browser-modules; built, the hashed ones the manifest lists."
  [{{{:keys [folder]} :files} :page :as settings} browser-modules]
  (if (is-watching? settings)
    (into {} (map (fn [module-id] [(name module-id) (str "/css/" (name module-id) ".css")])) (keys browser-modules))
    (into {} (keep (fn [[module-id address]] (when-not (= :main module-id) [(name module-id) address])))
          (read-built-files folder "css"))))

(defn make-browser-settings
  "What the browser needs to start, the same for every request: the
   version, the three addresses and the sse and ws reopen delays (from the
   :server settings), the question store's settings (the :doorbell
   settings' :store), the browser's wire settings (:browser-wire), how a
   write that got no answer is retried (the :run-once settings'
   :retry-writes), where a visitor's session is started (the :visitors
   settings' :path), the languages (dictionary's settings), each module's
   stylesheet bundle, the pages. The request's :language is added per
   request."
  [{:keys [version doorbell browser-wire run-once languages visitors]
    {:keys [addresses sse-reconnect-after-ms ws-reopen-after-ms]} :server
    :as settings}
   pages browser-modules]
  {:version version :addresses addresses :sse-reconnect-after-ms sse-reconnect-after-ms
   :ws-reopen-after-ms ws-reopen-after-ms :doorbell (:store doorbell) :wire browser-wire
   :retry-writes (:retry-writes run-once) :visitor-path (:path visitors) :languages languages
   :module-stylesheets (list-module-stylesheets settings browser-modules) :pages pages})

(defn make-page-html
  "The shell, in the request's language, with the alternate links
   (dictionary's, [] with one language)."
  [wire {{:keys [title viewport]} :page} stylesheets scripts browser-settings language alternate-links]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape-html language) "\">\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"" (escape-html viewport) "\">\n"
       "<title>" (escape-html title) "</title>\n"
       (str/join (map #(str "<link rel=\"alternate\" hreflang=\"" (escape-html (:hreflang %))
                            "\" href=\"" (escape-html (:href %)) "\">\n")
                      alternate-links))
       (str/join (map #(str "<link rel=\"stylesheet\" href=\"" (escape-html %) "\">\n") stylesheets))
       "<script type=\"application/json\" id=\"groundwork-settings\">"
       (escape-json-for-script (wire/turn-data-into-json-text wire browser-settings))
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
  "A Ring handler answering every page address with the shell, in the
   request's language (put on the request by wrap-languages), its JSON
   written by the app's wire. The files it lists are read once."
  [wire settings browser-settings]
  (let [headers     (make-headers settings)
        stylesheets (list-stylesheets settings)
        scripts     (list-scripts settings)
        origin      (get-in settings [:sessions :allowed-origin])]
    (fn [{:groundwork.server.main/keys [language path]}]
      {:status  200
       :headers headers
       :body    (make-page-html wire settings stylesheets scripts (assoc browser-settings :language language)
                                language (dictionary/make-alternate-links (:languages settings) origin path))})))

(defn make-catalog-routes
  "A GET for each language's catalog (dictionary's list-catalog-files), at
   the address the browser fetches it from, answered with the classpath
   file the server reads, as it is, read once: JSON, asked again each time
   (no-cache), so a deploy's new texts are what the next fetch gets."
  [languages]
  (vec (for [{:keys [resource address]} (dictionary/list-catalog-files languages)
             :let [response {:status  200
                             :headers {"content-type"           "application/json; charset=utf-8"
                                       "cache-control"          "no-cache"
                                       "x-content-type-options" "nosniff"}
                             :body    (slurp (io/resource resource))}]]
         [address {:get {:handler (fn [_request] response)}}])))

(defn wrap-languages
  "The Ring handler, each request first given its language and its path
   without the language's prefix (dictionary's find-language-of-request):
   :uri becomes that path, for routing, and the request carries
   :groundwork.server.main/language and :groundwork.server.main/path."
  [handler languages]
  (fn [request]
    (let [{:keys [language path]} (dictionary/find-language-of-request languages request)]
      (handler (assoc request
                      :uri path
                      :groundwork.server.main/language language
                      :groundwork.server.main/path path)))))
