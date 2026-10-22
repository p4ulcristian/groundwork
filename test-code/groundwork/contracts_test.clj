(ns groundwork.contracts-test
  "A pair's two halves keep one contract, copied into each. The copies must
   say the same, but for their own names; this fails the day they don't."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- read-contract
  "A contract file's text, with the half's own name made the same."
  [path half-name]
  (str/replace (slurp (io/file path)) half-name "SIDE"))

(deftest transport-halves-share-one-contract
  (is (= (read-contract "source-code/groundwork/server/transport/contract.cljc" "server")
         (read-contract "source-code/groundwork/browser/transport/contract.cljc" "browser"))))
