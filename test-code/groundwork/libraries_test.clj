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
           :when    (and (str/starts-with? (str required) "wire.") (not= 'wire.api required))]
       (str (second ns-form) " requires " required ": only wire's api, wire.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "logbook.") (not= 'logbook.api required))]
       (str (second ns-form) " requires " required ": only logbook's api, logbook.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "logbook.") (= :browser side))]
       (str (second ns-form) " requires " required ": logbook is the server's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "cellar.") (not= 'cellar.api required))]
       (str (second ns-form) " requires " required ": only cellar's api, cellar.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "backroom.") (not= 'backroom.api required))]
       (str (second ns-form) " requires " required ": only backroom's api, backroom.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "showroom.") (not= 'showroom.api required))]
       (str (second ns-form) " requires " required ": only showroom's api, showroom.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "mailroom.") (not= 'mailroom.api required))]
       (str (second ns-form) " requires " required ": only mailroom's api, mailroom.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "doorman.") (not= 'doorman.api required))]
       (str (second ns-form) " requires " required ": only doorman's api, doorman.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "doorbell.") (not= 'doorbell.api required))]
       (str (second ns-form) " requires " required ": only doorbell's api, doorbell.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "dictionary.") (not= 'dictionary.api required))]
       (str (second ns-form) " requires " required ": only dictionary's api, dictionary.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "meter.") (not= 'meter.api required))]
       (str (second ns-form) " requires " required ": only meter's api, meter.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "study.") (not= 'study.api required))]
       (str (second ns-form) " requires " required ": only study's api, study.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "attic.") (not= 'attic.api required))]
       (str (second ns-form) " requires " required ": only attic's api, attic.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "postbox.") (not= 'postbox.api required))]
       (str (second ns-form) " requires " required ": only postbox's api, postbox.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "safe.") (not= 'safe.api required))]
       (str (second ns-form) " requires " required ": only safe's api, safe.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (some #(str/starts-with? (str required) %) ["meter." "study." "attic." "postbox." "safe."])
                         (= :browser side))]
       (str (second ns-form) " requires " required ": meter, study, attic, postbox and safe are the server's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "parlor.") (not= 'parlor.api required))]
       (str (second ns-form) " requires " required ": only parlor's api, parlor.api"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (or (str/starts-with? (str required) "parlor.") (str/starts-with? (str required) "re-frame."))
                         (= :server side))]
       (str (second ns-form) " requires " required ": parlor and re-frame are the browser's"))
     (for [required (list-required-namespaces ns-form)
           :when    (and (str/starts-with? (str required) "showroom.") (= :server side))]
       (str (second ns-form) " requires " required ": the server loads showroom only when it watches (requiring-resolve)"))
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
