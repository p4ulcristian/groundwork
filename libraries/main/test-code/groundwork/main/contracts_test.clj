(ns groundwork.main.contracts-test
  "A pair's two halves keep one contract, copied into each. The copies must
   say the same, but for their own names; this fails the day they don't."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- read-contract
  "A contract file's text, with the half's own name made the same."
  [path half-name]
  (str/replace (slurp (io/file path)) half-name "transport-HALF"))

(deftest transport-halves-share-one-contract
  (is (= (read-contract "libraries/transport-server/source-code/groundwork/transport_server/contract.cljc" "transport-server")
         (read-contract "libraries/transport-browser/source-code/groundwork/transport_browser/contract.cljc" "transport-browser"))))
