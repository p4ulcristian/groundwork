(ns groundwork.browser.build-check-test
  "A release build, by showroom, of an entry using every browser library:
   they all compile, optimized, into one file with a hashed name and its
   manifest, and the stylesheet it names becomes the bundle."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [showroom.api :as showroom])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(deftest every-browser-library-compiles-in-a-release-build
  (let [folder (str (Files/createTempDirectory "groundwork-build" (make-array FileAttribute 0)))]
    (with-out-str (showroom/build-for-release! {:entry              'groundwork.browser.build-check/start!
                                                :files-folder       folder
                                                :cache-folder       ".shadow-cljs"
                                                :compile-threads    4
                                                :output-feature-set :es2020
                                                :source-maps?       false
                                                :gzip-level         9}))
    (let [[{:keys [module-id output-name]}] (edn/read-string (slurp (io/file folder "js" "manifest.edn")))]
      (is (= :main module-id))
      (is (re-matches #"main\.[0-9A-F]{8,}\.js" output-name) "the name carries a hash of the content")
      (is (.exists (io/file folder "js" output-name)))
      (is (.exists (io/file folder "js" (str output-name ".gz"))) "and its gzipped copy"))
    (let [[{:keys [output-name]}] (edn/read-string (slurp (io/file folder "css" "manifest.edn")))]
      (is (= "/* groundwork/browser/build_check.css */\n.build-check { color: rebeccapurple; }\n"
             (slurp (io/file folder "css" output-name)))
          "the named stylesheet, whole, as written"))))
