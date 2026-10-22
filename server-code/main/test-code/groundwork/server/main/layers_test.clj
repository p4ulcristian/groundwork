(ns groundwork.server.main.layers-test
  "The libraries stand in layers: no library uses another around a circle,
   and each side's main uses every library of its side. Reads every
   library's deps.edn in server-code/ and browser-code/; run from the repo's
   root."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(def ^:private sides ["server-code" "browser-code"])

(defn- name-library
  "server-code/wire -> \"server-code/wire\": a library is named by its side
   and its folder, since both sides have a wire."
  [folder]
  (str (.getName (.getParentFile (.getCanonicalFile folder))) "/" (.getName folder)))

(defn- find-libraries-used [folder]
  (let [deps (edn/read-string (slurp (io/file folder "deps.edn")))]
    (set (for [[_ {:keys [local/root]}] (:deps deps)
               :when root]
           (name-library (.getCanonicalFile (io/file folder root)))))))

(defn- find-uses-of-every-library []
  (into {}
        (for [side   sides
              folder (.listFiles (io/file side))
              :when  (.exists (io/file folder "deps.edn"))]
          [(name-library folder) (find-libraries-used folder)])))

(defn- find-layers
  "Each library's layer: one above the highest it uses, 0 when it uses none.
   Throws when libraries use each other around a circle."
  [uses]
  (letfn [(find-layer [layers library seen]
            (cond
              (contains? layers library) layers
              (contains? seen library)   (throw (ex-info "Libraries use each other around a circle"
                                                         {:circle (conj seen library)}))
              :else
              (let [used   (get uses library)
                    layers (reduce #(find-layer %1 %2 (conj seen library)) layers used)]
                (assoc layers library (if (empty? used) 0 (inc (apply max (map layers used))))))))]
    (reduce #(find-layer %1 %2 #{}) {} (keys uses))))

(defn- list-libraries-of-side [uses side]
  (set (filter #(.startsWith ^String % (str side "/")) (keys uses))))

(deftest libraries-stand-in-layers
  (let [uses (find-uses-of-every-library)]
    (is (= (set (keys uses)) (set (keys (find-layers uses)))) "every library has a layer")
    (doseq [side sides]
      (is (= (disj (list-libraries-of-side uses side) (str side "/main"))
             (get uses (str side "/main")))
          (str side "/main uses every library of its side")))))
