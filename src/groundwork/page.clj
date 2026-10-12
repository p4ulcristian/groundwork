(ns groundwork.page
  "The page shell: the one HTML page that loads an app. The app gives its
   details as settings, every one required:

     :lang         \"en\"
     :title        \"Acme Coating\"
     :icon         \"/icon.svg\"
     :stylesheets  [\"/app.css\"]
     :script       \"/js/main.js\"    the compiled ClojureScript
     :head         []                extra tags, as HTML text, placed at the end of <head>

   The page has one element for the app to render into, <div id=\"app\">, and
   is never cached: a deploy is seen on the next load."
  (:require
   [clojure.string :as str]))

(def ^:private settings [:lang :title :icon :stylesheets :script :head])

(defn- check-settings [opts]
  (when-not (map? opts)
    (throw (ex-info ":page must be a map" {:type ::bad-setting :setting :page :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str ":page needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (doseq [k [:lang :title :icon :script]
          :let [v (get opts k)]]
    (when-not (and (string? v) (not (str/blank? v)))
      (throw (ex-info (str ":page " k " must be a string")
                      {:type ::bad-setting :setting k :value v}))))
  (doseq [k [:stylesheets :head]
          :let [v (get opts k)]]
    (when-not (and (sequential? v) (every? string? v))
      (throw (ex-info (str ":page " k " must be a list of strings")
                      {:type ::bad-setting :setting k :value v})))))

(defn- escape
  "Text made safe inside HTML and inside a quoted attribute."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn html
  "The page as HTML text."
  [{:keys [lang title icon stylesheets script head]}]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape lang) "\">\n"
       "<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
       "<title>" (escape title) "</title>\n"
       "<link rel=\"icon\" href=\"" (escape icon) "\">\n"
       (apply str (for [href stylesheets]
                    (str "<link rel=\"stylesheet\" href=\"" (escape href) "\">\n")))
       (apply str (for [tag head] (str tag "\n")))
       "</head>\n"
       "<body>\n"
       "<div id=\"app\"></div>\n"
       "<script src=\"" (escape script) "\" defer></script>\n"
       "</body>\n"
       "</html>\n"))

(defn start
  "The page part: a Ring handler that answers with the page."
  [{:keys [settings]}]
  (check-settings settings)
  (let [body (html settings)]
    (fn [_request]
      {:status  200
       :headers {"Content-Type"  "text/html; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body    body})))
