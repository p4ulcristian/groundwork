(ns groundwork.server.main.page
  "The page shell: the one HTML page every page address answers, which
   loads the app's code and styles and carries what the browser needs to
   start, as JSON in a script tag:

     <script type=\"application/json\" id=\"groundwork-settings\">
       {\"version\":\"2026-10-22.1\",
        \"addresses\":{\"http\":\"/groundwork/http\",\"sse\":\"/groundwork/sse\",\"ws\":\"/groundwork/ws\"},
        \"pages\":{\"orders/page\":{\"address\":\"/orders/:order-id\",\"title\":\"Order\"}}}
     </script>

   The browser reads it (groundwork.browser.main); the pages and the
   addresses are said once, on the server. The shell is never cached, so a
   new version's scripts are what the next load gets."
  (:require
   [clojure.string :as str]
   [groundwork.server.wire.api :as wire]))

(defn- escape-html [text]
  (-> (str text) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- escape-json-for-script
  "JSON inside <script>: a < can't end the tag or open a comment."
  [json-text]
  (str/replace json-text "<" "\\u003c"))

(defn make-browser-settings
  "What the browser needs to start: the version, the three addresses, the pages."
  [{:keys [version]} addresses pages]
  {:version version :addresses addresses :pages pages})

(defn make-page-html [{{:keys [title language scripts stylesheets]} :page} browser-settings]
  (str "<!doctype html>\n"
       "<html lang=\"" (escape-html language) "\">\n<head>\n"
       "<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
       "<title>" (escape-html title) "</title>\n"
       (str/join (map #(str "<link rel=\"stylesheet\" href=\"" (escape-html %) "\">\n") stylesheets))
       "<script type=\"application/json\" id=\"groundwork-settings\">"
       (escape-json-for-script (wire/turn-data-into-json-text browser-settings))
       "</script>\n"
       (str/join (map #(str "<script src=\"" (escape-html %) "\" defer></script>\n") scripts))
       "</head>\n<body>\n<div id=\"app\"></div>\n</body>\n</html>\n"))

(defn- make-headers [{{:keys [content-security-policy]} :page}]
  (cond-> {"content-type"           "text/html; charset=utf-8"
           "cache-control"          "no-store"
           "x-content-type-options" "nosniff"
           "referrer-policy"        "same-origin"
           "x-frame-options"        "DENY"}
    (string? content-security-policy) (assoc "content-security-policy" content-security-policy)))

(defn make-page-handler
  "A Ring handler answering every page address with the shell, made once."
  [settings browser-settings]
  (let [response {:status 200 :headers (make-headers settings) :body (make-page-html settings browser-settings)}]
    (fn [_request] response)))
