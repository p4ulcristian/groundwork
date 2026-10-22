(ns groundwork.browser.styles.api
  "The macro half of groundwork.browser.styles: it runs on the JVM while
   shadow-cljs compiles a namespace, where files can be read."
  (:require
   [cljs.analyzer :as analyzer]
   [cljs.env :as env]
   [clojure.java.io :as io]))

(defmacro use-stylesheet
  "Names a stylesheet this namespace needs, by its path on the classpath,
   as a query names its .sql file:

     (def order-card-styles (styles/use-stylesheet \"app/orders/order_card.css\"))

   A missing file stops the build here. The build gathers every named
   stylesheet into the page's one bundle, in the order the namespaces are
   found. Returns the path."
  [path]
  (when-not (string? path)
    (throw (ex-info "use-stylesheet takes the path as text, written out" {:type :groundwork.browser.styles/bad-path :path path})))
  (when-not (io/resource path)
    (throw (ex-info (str "No stylesheet " path " on the classpath (named in " analyzer/*cljs-ns* ")")
                    {:type :groundwork.browser.styles/missing-file :path path :ns analyzer/*cljs-ns*})))
  (swap! env/*compiler* update-in [:cljs.analyzer/namespaces analyzer/*cljs-ns* :groundwork/stylesheets]
         (fn [paths] (if (some #{path} paths) paths (conj (or paths []) path))))
  path)
