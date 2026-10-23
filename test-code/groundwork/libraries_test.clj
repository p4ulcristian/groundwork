(ns groundwork.libraries-test
  "Every library uses only what libraries.edn lets it, and only through the
   other library's api; no two use each other around a circle. Reads every
   namespace form under source-code/groundwork/; run from the repo's root."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   (java.io PushbackReader)))

(def ^:private libraries (edn/read-string (slurp "libraries.edn")))

(defn- read-ns-form [file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (binding [*read-eval* false] (read reader))))

(defn- list-required-namespaces [ns-form]
  (for [clause (filter seq? (drop 2 ns-form))
        :when  (= :require (first clause))
        spec   (rest clause)]
    (if (sequential? spec) (first spec) spec)))

(defn- find-library-of
  "groundwork.server.main.page -> [:server :main], or nil
   for a namespace that isn't groundwork's."
  [ns-symbol]
  (let [[top side library] (str/split (str ns-symbol) #"\.")]
    (when (and (= "groundwork" top) (#{"server" "browser"} side) library)
      [(keyword side) (keyword library)])))

(defn- list-source-files []
  (filter #(re-find #"\.clj[sc]?$" (.getName %)) (file-seq (io/file "source-code/groundwork"))))

(defn- find-problems-of-file [file]
  (let [ns-form           (read-ns-form file)
        [side library]    (find-library-of (second ns-form))
        allowed           (get-in libraries [side library])]
    (concat
     (when (nil? allowed) [(str (second ns-form) ": its library isn't in libraries.edn")])
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-shaped-json.") (not= 'groundwork-shaped-json.api required))]
       (str (second ns-form) " requires " required ": only groundwork-shaped-json's api, groundwork-shaped-json.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-logs.") (not= 'groundwork-logs.api required))]
       (str (second ns-form) " requires " required ": only groundwork-logs's api, groundwork-logs.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-logs.") (= :browser side))]
       (str (second ns-form) " requires " required ": groundwork-logs is the server's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-postgres.") (not= 'groundwork-postgres.api required))]
       (str (second ns-form) " requires " required ": only groundwork-postgres's api, groundwork-postgres.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-background-jobs.") (not= 'groundwork-background-jobs.api required))]
       (str (second ns-form) " requires " required ": only groundwork-background-jobs's api, groundwork-background-jobs.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-browser-build.") (not= 'groundwork-browser-build.api required))]
       (str (second ns-form) " requires " required ": only browser-build's api, groundwork-browser-build.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-bridge.") (not= 'groundwork-bridge.api required))]
       (str (second ns-form) " requires " required ": only bridge's api, groundwork-bridge.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-access.") (not= 'groundwork-access.api required)
                         ;; asking as a named person: the REPL's tools only, never the app's code
                         (not (and (= 'groundwork-access.repl required) (= 'groundwork.server.main.tools (second ns-form)))))]
       (str (second ns-form) " requires " required ": only groundwork-access's api, groundwork-access.api (groundwork-access.repl: only the REPL's tools)"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-live-updates.") (not= 'groundwork-live-updates.api required))]
       (str (second ns-form) " requires " required ": only groundwork-live-updates's api, groundwork-live-updates.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-languages.") (not= 'groundwork-languages.api required))]
       (str (second ns-form) " requires " required ": only groundwork-languages's api, groundwork-languages.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-metrics.") (not= 'groundwork-metrics.api required))]
       (str (second ns-form) " requires " required ": only groundwork-metrics's api, groundwork-metrics.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-live-repl.") (not= 'groundwork-live-repl.api required))]
       (str (second ns-form) " requires " required ": only groundwork-live-repl's api, groundwork-live-repl.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-file-storage.") (not= 'groundwork-file-storage.api required))]
       (str (second ns-form) " requires " required ": only groundwork-file-storage's api, groundwork-file-storage.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-email.") (not= 'groundwork-email.api required))]
       (str (second ns-form) " requires " required ": only groundwork-email's api, groundwork-email.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-settings.") (not= 'groundwork-settings.api required))]
       (str (second ns-form) " requires " required ": only groundwork-settings's api, groundwork-settings.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (some #(str/starts-with? (str required) %) ["groundwork-metrics." "groundwork-live-repl." "groundwork-file-storage." "groundwork-email." "groundwork-settings."])
                         (= :browser side))]
       (str (second ns-form) " requires " required ": groundwork-metrics, groundwork-live-repl, groundwork-file-storage, groundwork-email and groundwork-settings are the server's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-screens.") (not= 'groundwork-screens.api required))]
       (str (second ns-form) " requires " required ": only groundwork-screens's api, groundwork-screens.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (or (str/starts-with? (str required) "groundwork-screens.") (str/starts-with? (str required) "re-frame."))
                         (= :server side))]
       (str (second ns-form) " requires " required ": groundwork-screens and re-frame are the browser's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "groundwork-browser-build.") (= :server side))]
       (str (second ns-form) " requires " required ": the server loads browser-build only when it watches (requiring-resolve)"))
     (for [required (list-required-namespaces ns-form)
           :let     [[required-side required-library] (find-library-of required)]
           :when    (and required-library (not= [side library] [required-side required-library]))
           problem  [(when (not= side required-side)
                       (str (second ns-form) " requires " required ": the other side's code"))
                     (when-not (contains? allowed required-library)
                       (str (second ns-form) " requires " required ": " library " doesn't list " required-library))
                     (when-not (str/ends-with? (str required) ".api")
                       (str (second ns-form) " requires " required ": only another library's api"))]
           :when    problem]
       problem))))

(defn- find-circle [uses]
  (letfn [(visit [library path]
            (cond (some #{library} path) (conj path library)
                  :else (some #(visit % (conj path library)) (get uses library))))]
    (some #(visit % []) (keys uses))))

(deftest every-library-uses-only-what-it-lists
  (is (= [] (vec (mapcat find-problems-of-file (list-source-files))))))

(deftest no-library-uses-another-around-a-circle
  (doseq [[side uses] libraries]
    (is (nil? (find-circle uses)) (str side ": a circle"))))
