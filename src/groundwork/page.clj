(ns groundwork.page
  "The page shell: the one HTML page that loads an app. The app gives its
   details as settings, every one required:

     :lang         \"en\"
     :title        \"Acme Coating\"
     :description  \"Powder coating, ordered online\"   or nil
     :icon         \"/icon.svg\"
     :stylesheets  [\"/app.css\"]
     :head         []                extra tags, as HTML text, placed at the end of <head>

   The app's compiled browser code is the build's own (see groundwork.build):
   after a release, its fingerprinted file from the build's manifest.

   A route can give its own page, a map or a function of the request
   returning one, merged over these:

     [\"/coaters/:slug\" {:get web/page
                         :page (fn [request] {:title … :description … :body …})}]

   A handler that has to look first (a record that may not exist) gives the
   page itself and may change the answer:

     (fn [request]
       (if-let [coater (find-coater request)]
         (web/page request {:title (:name coater) :body …})
         (assoc (web/page request {:title \"No such coater\"}) :status 404)))

     :lang :title :description   replace the app's
     :canonical  \"https://…\"     <link rel=canonical>
     :alternates [{:lang \"de\" :href \"https://…/de/…\"}]   <link rel=alternate hreflang>
     :stylesheets :scripts :head added after the app's
     :module     the build module this page starts, loaded after the modules
                 it depends on (see groundwork.build); without it the
                 module(s) that depend on no other
     :body       HTML text the server writes inside <div id=\"app\">, for
                 search engines and the first paint; the app replaces it

   With languages (see groundwork.web), the page's lang is the request's
   language unless the route gives its own, and the alternate links name the
   same page in every language unless the route gives its own.

   The page has one element for the app to render into, <div id=\"app\">,
   and what the browser start reads first, as JSON in
   <script type=\"application/json\" id=\"groundwork\">: the person signed in
   (or null), their session's flags, the page's language and, with
   languages, the ones the app speaks (the browser's routing reads them). It is never
   cached: a deploy is seen on the next load. Every <script> and
   <style> on it, the app's :head and :body included, carries the request's
   nonce, so the strict Content-Security-Policy lets them run."
  (:require
   [clojure.string :as str]
   [groundwork.build :as build]
   [groundwork.sessions :as sessions]
   [groundwork.settings :as settings]
   [jsonista.core :as json]))

(def ^:private route-keys
  #{:lang :title :description :canonical :alternates :stylesheets :scripts :head :body :module})

(defn check-route-page
  "A route's :page: a map of route-keys or a function of the request."
  [path page]
  (when-not (or (fn? page) (var? page)
                (and (map? page) (every? route-keys (keys page))))
    (throw (ex-info (str "Route " path ": :page must be a function of the request or a map of "
                         (str/join " " (sort route-keys)))
                    {:type ::bad-route :path path :page page}))))

(defn- escape
  "Text made safe inside HTML and inside a quoted attribute."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn merged
  "The app's page settings with a route's page over them."
  [app route]
  (-> (merge app (select-keys route [:lang :title :description :canonical :alternates :body :module]))
      (update :stylesheets into (:stylesheets route))
      (update :head into (:head route))
      (assoc :scripts (vec (:scripts route)))))

(defn- stamp-nonce
  "HTML text with the nonce on every <script and <style tag that has none."
  [html nonce]
  (if (and html nonce)
    (str/replace html #"(?i)<(script|style)(?![^>]*\bnonce=)" (str "<$1 nonce=\"" nonce "\""))
    html))

(defn- initial-data
  "What the browser start reads first, as JSON safe inside a <script>."
  [{:keys [person flags lang languages]}]
  (str/replace (json/write-value-as-string (cond-> {"person"   (some-> person str)
                                                    "flags"    (mapv name (sort flags))
                                                    "language" lang}
                                             languages (assoc "languages" {"supported" (:supported languages)
                                                                           "default"   (:default languages)})))
               "</" "<\\/"))

(defn html
  "The page as HTML text; nonce, when given, goes on its scripts and styles."
  [{:keys [lang title description icon canonical alternates stylesheets head module-scripts scripts body] :as page} nonce]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape lang) "\">\n"
       "<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
       "<title>" (escape title) "</title>\n"
       (when description (str "<meta name=\"description\" content=\"" (escape description) "\">\n"))
       "<link rel=\"icon\" href=\"" (escape icon) "\">\n"
       (when canonical (str "<link rel=\"canonical\" href=\"" (escape canonical) "\">\n"))
       (apply str (for [{:keys [lang href]} alternates]
                    (str "<link rel=\"alternate\" hreflang=\"" (escape lang) "\" href=\"" (escape href) "\">\n")))
       (apply str (for [href stylesheets]
                    (str "<link rel=\"stylesheet\" href=\"" (escape href) "\">\n")))
       (apply str (for [tag head] (str (stamp-nonce tag nonce) "\n")))
       "</head>\n"
       "<body>\n"
       "<div id=\"app\">" (stamp-nonce body nonce) "</div>\n"
       (stamp-nonce (str "<script type=\"application/json\" id=\"groundwork\">" (initial-data page) "</script>\n") nonce)
       (apply str (for [src (concat module-scripts scripts)]
                    (stamp-nonce (str "<script src=\"" (escape src) "\" defer></script>\n") nonce)))
       "</body>\n"
       "</html>\n"))

(defn- route-page
  "The page for this request: the route's map (or a handler's own, see
   groundwork.web/page), or what the route's function returns."
  [request]
  (let [page (::route-page request)]
    (if (nil? page)
      {}
      (let [result (if (map? page) page (page request))]
        (when-not (and (map? result) (every? route-keys (keys result)))
          (throw (ex-info (str "A page must be a map of " (str/join " " (sort route-keys)))
                          {:type ::bad-page :page result})))
        result))))

(defn start
  "The page part: a Ring handler that answers with the page, the route's
   own page (::route-page on the request) merged over the app's. The build's
   script is looked up on every page in development, where it changes, and
   once otherwise."
  [{:keys [settings app-settings environment]}]
  (settings/check-part! :page settings)
  (let [browser (:browser app-settings)
        for*    (fn [module]
                  (if module
                    (build/scripts browser module)
                    (into [] (comp (mapcat #(build/scripts browser %)) (distinct)) (build/roots browser))))
        cached  (memoize for*)
        ;; in development the build changes while the app runs
        scripts (if (= :development environment) for* cached)]
    (fn [request]
      (let [page (merged (cond-> (assoc settings
                                        :person    (sessions/person request)
                                        :flags     (sessions/flags request)
                                        :languages (get-in app-settings [:web :languages]))
                           (::language request)   (assoc :lang (::language request))
                           (::alternates request) (assoc :alternates (::alternates request)))
                         (route-page request))]
        {:status  200
         :headers {"Content-Type"  "text/html; charset=utf-8"
                   "Cache-Control" "no-store"}
         :body    (html (assoc page :module-scripts (scripts (:module page)))
                        (::nonce request))}))))
