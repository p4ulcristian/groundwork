(ns groundwork.browser.build-check-test
  "A release build, by showroom, of an entry using every browser library,
   and a module loaded on demand: they all compile, optimized, main into
   one file with a hashed name and the module into another, with their
   manifest, and the stylesheets each names become its bundle."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [showroom.api :as showroom])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(deftest every-browser-library-compiles-in-a-release-build
  (let [folder (str (Files/createTempDirectory "groundwork-build" (make-array FileAttribute 0)))]
    (with-out-str (showroom/build-for-release! {:entry              'groundwork.browser.build-check/start!
                                                :modules            {:admin {:entries    '[groundwork.browser.build-check-admin]
                                                                             :depends-on #{:main}}}
                                                :files-folder       folder
                                                :cache-folder       ".shadow-cljs"
                                                :compile-threads    4
                                                :output-feature-set :es2020
                                                :resolve            {}
                                                :closure-defines    {}
                                                :warnings-as-errors #{}
                                                :source-maps?       false
                                                :gzip-level         9}))
    (let [js-files  (into {} (map (juxt :module-id :output-name)) (edn/read-string (slurp (io/file folder "js" "manifest.edn"))))
          css-files (into {} (map (juxt :module-id :output-name)) (edn/read-string (slurp (io/file folder "css" "manifest.edn"))))]
      (is (= #{:main :admin} (set (keys js-files))) "two modules")
      (is (re-matches #"main\.[0-9A-F]{8,}\.js" (:main js-files)) "the name carries a hash of the content")
      (is (.exists (io/file folder "js" (str (:main js-files) ".gz"))) "and its gzipped copy")
      (is (str/includes? (slurp (io/file folder "js" (:admin js-files))) "build-check-admin") "the module's code in its own file")
      (is (str/includes? (slurp (io/file folder "js" (:main js-files))) (:admin js-files))
          "main knows the module's hashed file, for the loader")
      (is (= "/* groundwork/browser/build_check.css */\n.build-check { color: rebeccapurple; }\n"
             (slurp (io/file folder "css" (:main css-files))))
          "main's named stylesheet, whole, as written")
      (is (= "/* groundwork/browser/build_check_admin.css */\n.build-check-admin { color: tomato; }\n"
             (slurp (io/file folder "css" (:admin css-files))))
          "and the module's, in its own bundle"))))
