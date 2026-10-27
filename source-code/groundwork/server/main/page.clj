(ns groundwork.server.main.page
  "The page shell: the one HTML page every page address answers, which
   loads the app's code and styles and carries what the browser needs to
   start, as JSON in a script tag:

     <script type=\"application/json\" id=\"groundwork-settings\">
       {\"version\":\"2026-10-22.1\",
        \"addresses\":{\"http\":\"/groundwork/http\",\"sse\":\"/groundwork/sse\",\"ws\":\"/groundwork/ws\"},
        \"sse-reconnect-after-ms\":3000,
        \"ws-reopen-after-ms\":1000,
        \"groundwork-live-updates\":{\"gather-changes-ms\":50,\"keep-unwatched-answers-ms\":300000},
        \"groundwork-shaped-json\":{\"most-shapes-kept\":1000,\"most-keys-kept\":10000},
        \"retry-writes\":{\"times\":2,\"wait-ms\":1000},
        \"visitor-path\":\"/groundwork/visitor\",
        \"pages\":{\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}}}
     </script>

   The browser reads it (groundwork.browser.main); the pages, the
   addresses, the sse and ws reopen delays, the question store's settings
   (groundwork-live-updates's), the browser's groundwork-shaped-json settings (:browser-shaped-json, all but
   :exact-decimals, which the app's browser start hands in), the write
   retries, the visitor route's path (:visitors), the languages
   (groundwork-languages's settings) and the request's language, and each module's stylesheet bundle (for browser-build's
   load-module!) are said once, on the server. The JSON is written by the
   app's shaper.

   A page starts with the module it names (:module, one of the app's
   :browser-modules; the not-found page with the app's :not-found-module):
   the shell links its stylesheet bundle and script-tags its code, and
   those of every module it depends on, root first, as browser-build's
   files-for-page reads them from the build's manifests, once per module.
   Any other module is loaded on demand, by browser-build's load-module!.

   The page is in the request's language (groundwork-languages's
   find-language-of-request: the address's prefix, the cookie,
   Accept-Language, the default): <html lang>, and with more than one
   language the alternate links, the same page in each, at the app's
   origin (the :access settings' :allowed-origin).

   What only the app knows, per page and per request, is the app's
   :render-page, (fn [request page {:keys [nonce asker run-as-asker!]}]),
   which answers a page or a redirect, every key written out:

     {:answer      :page
      :status      200                          ; 404 for the not-found page
      :title       \"Orders | Shop\"              ; the <title>, escaped here
      :description \"Every order, by status\"     ; the description meta, escaped here; nil for none
      :head-html   \"<link rel=\\\"canonical\\\" …>\"  ; the app's own head tags, as they are
      :body-html   \"<main>…</main>\"}             ; inside #app, as it is, replaced when the app draws

     {:answer   :redirect                       ; signed out, not a member here, a page moved
      :status   303                             ; 301, 302, 303, 307 or 308
      :location \"/login?next=%2Forders\"}        ; the Location header, as it is; no shell, never cached

   page is the page the address names ({:name :address :title :module}),
   or nil for a GET no route and no file answers (the not-found page).
   nonce is the request's, nil unless the :content-security-policy says
   :nonce? true; the shell puts it on its own scripts, the app on the
   scripts in its head-html. asker is who groundwork-access finds asking (the
   session's cookie, or a token), and run-as-asker! a transaction as the
   request's asker, held by the gates, for a body that reads rows
   (groundwork-access's run-as-asker-of-request!). The app escapes what it puts in head-html and body-html.

   Fixed, not settings: the shell is never cached (no-store), so a new
   version's scripts are what the next load gets; nosniff, since every
   file goes out with its type; UTF-8; the scripts deferred; the app's
   element is #app. The rest is the :page settings: the viewport, the
   content security policy (and whether it carries a nonce), the referrer
   policy and the frame options."
  (:require
   [groundwork-browser-build.page :as browser-page]
   [groundwork-languages.api :as languages]
   [groundwork.server.main.asker :as asker]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.security SecureRandom)
   (java.util Base64)
   (org.owasp.encoder Encode)))

(defn- escape-html
  "Text in HTML, an element's or a quoted attribute's, by OWASP's
   Encode/forHtml: & < > \" ' each as its entity."
  [text]
  (Encode/forHtml (str text)))

(defn- write-json-as-json-string
  "JSON text as a JSON string, by OWASP's Encode/forJson, for the settings'
   <script type=\"application/json\">: its \" and \\ escaped, and < > &
   as \\u003c \\u003e \\u0026, so nothing in it ends the element or opens a
   comment. The browser reads it with JSON.parse twice: the string, then
   the JSON in it."
  [json-text]
  (str "\"" (Encode/forJson json-text) "\""))

(defn find-files-of-page-modules
  "The files a page that starts with each module links, {module
   {:stylesheets [...] :scripts [...]}}, for every module a page names and
   the not-found page's, read once from the build's manifests by
   browser-build's files-for-page (groundwork-browser-build.page, the one
   reader of them): while watching the plain names, released the hashed
   ones, each module with every module it depends on, in dependency order.
   While watching, read after the watch's first compile (start-watching!
   has returned). No build in the files folder, or a module the build
   doesn't have, throws, named."
  [{{{:keys [folder]} :files} :page} modules]
  (into {} (map (fn [module] [module (browser-page/files-for-page {:files-folder folder :module module})]))
        (distinct modules)))

(defn make-browser-settings
  "What the browser needs to start, the same for every request: the
   version, the three addresses and the sse and ws reopen delays (from the
   :bridge settings), the question store's settings (the :live-updates
   settings' :store), the browser's groundwork-shaped-json settings (:browser-shaped-json), how a
   write that got no answer is retried (the :run-once settings'
   :retry-writes), where a visitor's session is started (the :visitors
   settings' :path), the languages (groundwork-languages's settings), every
   module's stylesheet bundle, for browser-build's load-module! (its
   stylesheets-for-load-module, read once from the build's manifests), the
   pages. The request's :language is added per request."
  [{:keys [version live-updates browser-shaped-json run-once languages visitors]
    {:keys [addresses sse-reconnect-after-ms ws-reopen-after-ms]} :bridge
    {{:keys [folder]} :files} :page}
   pages]
  {:version version :addresses addresses :sse-reconnect-after-ms sse-reconnect-after-ms
   :ws-reopen-after-ms ws-reopen-after-ms :live-updates (:store live-updates) :shaped-json browser-shaped-json
   :retry-writes (:retry-writes run-once) :visitor-path (:path visitors) :languages languages
   :module-stylesheets (browser-page/stylesheets-for-load-module {:files-folder folder}) :pages pages})

(def rendered-page-shape
  "What the app's :render-page answers, every key required: a page
   (:answer :page), drawn in the shell, or a redirect (:answer :redirect),
   answered with its status and Location and no shell."
  [:multi {:dispatch :answer}
   [:page [:map {:closed true}
           [:answer [:= :page]]
           [:status [:int {:min 200 :max 599}]]
           [:title :string]
           [:description [:maybe :string]]
           [:head-html :string]
           [:body-html :string]]]
   [:redirect [:map {:closed true}
               [:answer [:= :redirect]]
               [:status [:enum 301 302 303 307 308]]
               [:location :string]]]])

(defn- check-rendered-page [page-name rendered]
  (when-let [explanation (malli/explain rendered-page-shape rendered)]
    (throw (ex-info (str "render-page's answer for " (pr-str page-name) " has problems: "
                         (pr-str (malli-error/humanize explanation)))
                    {:type :groundwork.server.main/bad-rendered-page :page page-name
                     :errors (malli-error/humanize explanation)})))
  rendered)

(defn- make-nonce
  "16 random bytes, base64: one request's CSP nonce."
  [^SecureRandom random]
  (let [bytes (byte-array 16)]
    (.nextBytes random bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- nonce-attribute [nonce]
  (if nonce (str " nonce=\"" (escape-html nonce) "\"") ""))

(defn make-page-html
  "The shell, in the request's language, with the alternate links
   (groundwork-languages's, [] with one language), the page module's files
   (files-for-page's {:stylesheets :scripts}) and the app's rendered page: its
   title and description escaped, its head and body HTML as they are."
  [shaper {{:keys [viewport]} :page} {:keys [stylesheets scripts]} browser-settings language alternate-links
   {:keys [title description head-html body-html]} nonce]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape-html language) "\">\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"" (escape-html viewport) "\">\n"
       "<title>" (escape-html title) "</title>\n"
       (when description (str "<meta name=\"description\" content=\"" (escape-html description) "\">\n"))
       (str/join (map #(str "<link rel=\"alternate\" hreflang=\"" (escape-html (:hreflang %))
                            "\" href=\"" (escape-html (:href %)) "\">\n")
                      alternate-links))
       (str/join (map #(str "<link rel=\"stylesheet\" href=\"" (escape-html %) "\">\n") stylesheets))
       head-html
       "<script type=\"application/json\" id=\"groundwork-settings\"" (nonce-attribute nonce) ">"
       (write-json-as-json-string (shaped-json/turn-data-into-json-text shaper browser-settings))
       "</script>\n"
       (str/join (map #(str "<script src=\"" (escape-html %) "\"" (nonce-attribute nonce) " defer></script>\n") scripts))
       "</head>\n<body>\n<div id=\"app\">" body-html "</div>\n</body>\n</html>\n"))

(defn- make-headers
  "The shell's headers; the content security policy with this request's
   nonce in place of {nonce} when it carries one."
  [{{:keys [content-security-policy referrer-policy frame-options]} :page} nonce]
  (cond-> {"content-type"           "text/html; charset=utf-8"
           "cache-control"          "no-store"
           "x-content-type-options" "nosniff"
           "referrer-policy"        referrer-policy}
    (map? content-security-policy) (assoc "content-security-policy"
                                          (cond-> (:policy content-security-policy)
                                            nonce (str/replace "{nonce}" nonce)))
    (string? frame-options)        (assoc "x-frame-options" frame-options)))

(defn- find-page-of-request [pages request]
  (when-let [page-name (get-in request [:reitit.core/match :data :name])]
    (assoc (get pages page-name) :name page-name)))

(defn make-page-handler
  "A Ring handler answering every page address with the shell, in the
   request's language (put on the request by wrap-languages), its JSON
   written by the app's shaper, the app's render-page's head and body in it,
   the files of the page's :module linked and script-tagged (with every
   module it depends on); a GET of an address no page has (no route, no
   file) is the not-found page (page nil), which starts with the
   not-found-module, anything else there 404 with no body. The files it
   lists are read once per module (find-files-of-page-modules). parts:
   {:shaper :settings :browser-settings :pages :not-found-module
   :render-page :pool :log :scope-kinds :find-session-step}."
  [{:keys [shaper settings browser-settings pages not-found-module render-page pool log scope-kinds find-session-step]}]
  (let [files-of-modules (find-files-of-page-modules settings (conj (mapv :module (vals pages)) not-found-module))
        origin      (get-in settings [:access :allowed-origin])
        nonce?      (true? (get-in settings [:page :content-security-policy :nonce?]))
        random      (SecureRandom.)]
    (fn [{:groundwork.server.main/keys [language path] :as request}]
      (if-not (= :get (:request-method request))
        {:status 404 :headers {} :body ""}
        (let [page     (find-page-of-request pages request)
              nonce    (when nonce? (make-nonce random))
              context  ((:enter find-session-step) {:groundwork-steps/request request :groundwork-steps/log log})
              rendered (check-rendered-page (:name page)
                                            (render-page request page
                                                         {:nonce         nonce
                                                          :asker         (asker/read-asker context)
                                                          :run-as-asker! (asker/make-run-as-asker pool request settings scope-kinds (:groundwork-steps/log context))}))]
          (if (= :redirect (:answer rendered))
            {:status  (:status rendered)
             :headers {"location" (:location rendered) "cache-control" "no-store"}
             :body    ""}
            {:status  (:status rendered)
             :headers (make-headers settings nonce)
             :body    (make-page-html shaper settings (get files-of-modules (if page (:module page) not-found-module))
                                      (assoc browser-settings :language language)
                                      language (languages/make-alternate-links (:languages settings) origin path)
                                      rendered nonce)}))))))

(defn make-catalog-routes
  "A GET for each language's catalog (groundwork-languages's list-catalog-files), at
   the address the browser fetches it from, answered with the classpath
   file the server reads, as it is, read once: JSON, asked again each time
   (no-cache), so a deploy's new texts are what the next fetch gets."
  [languages]
  (vec (for [{:keys [resource address]} (languages/list-catalog-files languages)
             :let [response {:status  200
                             :headers {"content-type"           "application/json; charset=utf-8"
                                       "cache-control"          "no-cache"
                                       "x-content-type-options" "nosniff"}
                             :body    (slurp (io/resource resource))}]]
         [address {:get {:handler (fn [_request] response)}}])))

(defn wrap-languages
  "The Ring handler, each request first given its language and its path
   without the language's prefix (groundwork-languages's find-language-of-request):
   :uri becomes that path, for routing, and the request carries
   :groundwork.server.main/language and :groundwork.server.main/path. The
   handler runs with the texts and that language bound (groundwork-languages's
   with-language), so a (languages/tr key args) in it is in the
   request's language; work it hands to a thread Clojure didn't start
   binds its own."
  [handler languages texts]
  (fn [request]
    (let [{:keys [language path]} (languages/find-language-of-request languages request)]
      (languages/with-language texts language
        (handler (assoc request
                        :uri path
                        :groundwork.server.main/language language
                        :groundwork.server.main/path path))))))
