(ns groundwork.libraries-test
  "Every library keeps to what libraries.edn says it may use: its files
   require no library it doesn't list. A namespace of today's Groundwork that
   isn't a library yet belongs to main."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk]))

(def libraries (edn/read-string (slurp "libraries.edn")))

(defn- library-of
  "The library a groundwork namespace belongs to: groundwork.wire.api → wire;
   one that isn't a library yet (groundwork.core) → main."
  [ns-sym]
  (let [[top second-part] (str/split (str ns-sym) #"\.")]
    (when (= "groundwork" top)
      (let [lib (symbol second-part)]
        (if (contains? libraries lib) lib 'main)))))

(defn- ns-form
  "The ns form of a source file, as one language reads it."
  [file feature]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (read {:read-cond :allow :features #{feature} :eof nil} r))))

(defn- required
  "Every namespace a ns form requires."
  [form]
  (let [found (atom #{})]
    (clojure.walk/postwalk
     (fn [x]
       (cond
         (and (vector? x) (symbol? (first x))) (swap! found conj (first x))
         (and (seq? x) (= :require (first x))) (doseq [y (rest x)] (when (symbol? y) (swap! found conj y))))
       x)
     (drop 2 form))
    @found))

(deftest every-library-keeps-to-its-uses
  (doseq [file (file-seq (io/file "source-code/groundwork"))
          :when (re-find #"\.clj[cs]?$" (.getName file))
          :let [form  (ns-form file :clj)
                owner (library-of (second form))
                uses  (get-in libraries [owner :uses])]
          :when (not= :all uses)
          dep   (into (required form) (required (ns-form file :cljs)))
          :let  [dep-lib (library-of dep)]
          :when (and dep-lib (not= dep-lib owner))]
    (testing (str (second form) " requires " dep)
      (is (some #{dep-lib} uses)
          (str owner " may not use " dep-lib "; add it to libraries.edn if it should")))))
