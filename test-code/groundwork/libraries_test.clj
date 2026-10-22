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
  "groundwork.server.database.tables.plan -> [:server :database], or nil
   for a namespace that isn't Groundwork's."
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
