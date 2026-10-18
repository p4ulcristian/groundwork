(ns groundwork.web.languages
  "The request's language: a /de/… prefix is taken off before routing; the
   language comes from the address, then the cookie (when named), then the
   browser's Accept-Language, then the default. The page shell's lang and
   its alternate links follow it (groundwork.web/language reads it)."
  (:require
   [clojure.string :as str]
   [groundwork.web.page :as-alias page]))

(defn- cookie-value [request name]
  (some (fn [pair]
          (let [[k v] (str/split (str/trim pair) #"=" 2)]
            (when (= k name) v)))
        (some-> (get-in request [:headers "cookie"]) (str/split #";"))))

(defn- accepted-languages
  "The browser's Accept-Language, most wanted first, as lowercase tags."
  [header]
  (->> (str/split (or header "") #",")
       (keep (fn [entry]
               (let [[tag & params] (map str/trim (str/split entry #";"))
                     q (or (some #(some-> (re-matches #"q=([0-9.]+)" %) second parse-double) params) 1.0)]
                 (when (and (seq tag) (not= "*" tag)) [(str/lower-case tag) q]))))
       (sort-by (comp - second))
       (map first)))

(defn- pick-language [{:keys [supported default cookie]} request]
  (let [supported? (set supported)]
    (or (some-> cookie (#(cookie-value request %)) supported?)
        (some (fn [tag] (or (supported? tag) (supported? (first (str/split tag #"-")))))
              (accepted-languages (get-in request [:headers "accept-language"])))
        default)))

(defn wrap-languages
  "Take a /xx/ prefix off the path and pick the request's language; give the
   page shell the language and the same page's address in each language."
  [handler languages origin]
  (if (nil? languages)
    handler
    (let [{:keys [supported default]} languages
          prefix (re-pattern (str "^/(" (str/join "|" supported) ")(/.*)?$"))]
      (fn [request]
        (let [[_ lang rest] (re-matches prefix (:uri request))
              path          (if lang (or rest "/") (:uri request))
              chosen        (or lang (pick-language languages request))
              href          (fn [l] (str origin (when (not= l default) (str "/" l)) (if (= "/" path) (when (= l default) "/") path)))]
          (handler (assoc request
                          :uri                 path
                          ::page/language      chosen
                          ::page/alternates    (conj (mapv (fn [l] {:lang l :href (href l)}) supported)
                                                     {:lang "x-default" :href (href default)}))))))))
