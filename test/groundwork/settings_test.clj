(ns groundwork.settings-test
  "The settings schema, and the docs made from it: docs/example-settings.edn
   and the README's settings reference. When the schema changes, regenerate
   them with (groundwork.settings-test/write-docs!) and commit."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork.repl :as repl]
   [groundwork.settings :as settings]
   [groundwork.settings.docs :as docs]
   [groundwork.test-support :refer [app-settings]]))

(def ^:private example-file "docs/example-settings.edn")
(def ^:private readme "README.md")
(def ^:private start-mark "<!-- settings reference: generated, see groundwork.settings-test -->\n")
(def ^:private end-mark "<!-- end of the settings reference -->\n")

(defn- readme-with [text reference]
  (let [i (str/index-of text start-mark)
        j (str/index-of text end-mark)]
    (when-not (and i j)
      (throw (ex-info "The README has no settings reference markers" {})))
    (str (subs text 0 (+ i (count start-mark))) reference (subs text j))))

(defn write-docs!
  "Regenerate docs/example-settings.edn and the README's settings reference."
  []
  (io/make-parents example-file)
  (spit example-file (docs/example))
  (spit readme (readme-with (slurp readme) (docs/reference))))

(deftest the-docs-are-up-to-date
  (testing "docs/example-settings.edn"
    (is (= (docs/example) (slurp example-file))
        "Out of date: run (groundwork.settings-test/write-docs!)"))
  (testing "the README's settings reference"
    (let [text (slurp readme)]
      (is (= text (readme-with text (docs/reference)))
          "Out of date: run (groundwork.settings-test/write-docs!)"))))

(deftest the-example-is-edn-and-follows-the-schema
  (let [example (edn/read-string (slurp example-file))]
    (is (= (set (keys settings/parts)) (set (keys example))))
    (testing "every value fits, apart from functions written as their names"
      (is (empty? (remove #(re-find #"must be a function|must map endpoint names|must map job names" (:message %))
                          (settings/problems example)))))))

(deftest the-test-app-settings-fit
  (is (= [] (settings/problems app-settings))))

(deftest problems-come-all-at-once-with-their-place
  (let [found (settings/problems (-> app-settings
                                     (assoc-in [:sessions :visitor-hours] "24")
                                     (assoc-in [:server :port] 70000)
                                     (update :web dissoc :login-path)
                                     (assoc-in [:web :languages] {:supported ["en"] :default "de" :cookie nil})
                                     (assoc-in [:web :proxies :trusted] ["not an address"])
                                     (assoc-in [:permissions :scopes :shop] {:actions {:see #{:read}} :roles {} :parent nil})))]
    (is (= #{[:sessions :visitor-hours] [:server :port] [:web :login-path] [:web :languages]
             [:web :proxies :trusted 0] [:permissions :scopes :shop :actions :see]}
           (set (map :in found))))
    (is (some #{{:in [:sessions :visitor-hours] :message "must be a positive whole number, got \"24\""}} found))
    (is (some #{{:in [:web :login-path] :message "is missing"}} found))))

(deftest routes-are-checked-with-what-they-inherit
  (let [routes [["/a" {:access :public :embeddable? true}
                 ["/b" {:embeddable? "yes"}]]
                ["/c" {:get identity}]]]
    (is (= #{["/a/b"] ["/c"]}
           (set (map #(subvec (:in %) 2 3) (settings/route-problems routes [:web :routes])))))))

(deftest the-repl-explains-a-setting
  (is (= {:path [:sessions :visitor-hours] :type "positive whole number"
          :description "A visitor's session ends after this long unseen" :example 24 :optional? false}
         (repl/explain-setting [:sessions :visitor-hours])))
  (is (some #(str/starts-with? % ":db :pool-size  (positive whole number)") (repl/settings))))
