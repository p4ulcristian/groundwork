(ns groundwork.main.layers-test
  "The libraries stand in layers: no library uses another around a circle.
   Reads every library's deps.edn in server-code/, browser-code/ and
   libraries/; run from the repo's root."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- find-libraries-used
  "The names of the libraries a library's deps.edn lists by :local/root."
  [library-folder]
  (let [deps (edn/read-string (slurp (io/file library-folder "deps.edn")))]
    (set (for [[_ {:keys [local/root]}] (:deps deps)
               :when root]
           (.getName (io/file root))))))

(defn- find-uses-of-every-library []
  (into {}
        (for [side   ["server-code" "browser-code" "libraries"]
              folder (.listFiles (io/file side))
              :when  (.exists (io/file folder "deps.edn"))]
          [(.getName folder) (find-libraries-used folder)])))

(defn- find-layers
  "Each library's layer: one above the highest it uses, 0 when it uses none.
   Throws when libraries use each other around a circle."
  [uses]
  (letfn [(find-layer [layers name seen]
            (cond
              (contains? layers name) layers
              (contains? seen name)   (throw (ex-info "Libraries use each other around a circle"
                                                      {:circle (conj seen name)}))
              :else
              (let [used   (get uses name)
                    layers (reduce #(find-layer %1 %2 (conj seen name)) layers used)]
                (assoc layers name (if (empty? used)
                                     0
                                     (inc (apply max (map layers used))))))))]
    (reduce #(find-layer %1 %2 #{}) {} (keys uses))))

(deftest libraries-stand-in-layers
  (let [uses   (find-uses-of-every-library)
        layers (find-layers uses)]
    (is (= (set (keys uses)) (set (keys layers)))
        "every library has a layer")
    (is (= (set (keys (dissoc uses "main"))) (get uses "main"))
        "main uses every library")))
