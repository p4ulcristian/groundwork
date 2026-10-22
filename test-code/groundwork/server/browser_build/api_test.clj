(ns groundwork.server.browser-build.api-test
  "A release build of an entry using every browser library: they all
   compile, optimized, into one file with a hashed name and its manifest."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [groundwork.server.browser-build.api :as browser-build])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(deftest the-names-of-the-api
  (is (= '#{make-build start-watching! stop-watching! build-for-release!}
         (set (keys (ns-publics 'groundwork.server.browser-build.api))))))

(deftest a-release-build
  (let [folder (str (Files/createTempDirectory "groundwork-build" (make-array FileAttribute 0)))]
    (with-out-str (browser-build/build-for-release! {:entry 'groundwork.browser.build-check/start! :files-folder folder}))
    (let [[{:keys [module-id output-name]}] (edn/read-string (slurp (io/file folder "js" "manifest.edn")))]
      (is (= :main module-id))
      (is (re-matches #"main\.[0-9A-F]{8,}\.js" output-name) "the name carries a hash of the content")
      (is (.exists (io/file folder "js" output-name))))))
