(ns groundwork.loader-test
  "Code loaded on demand brings its stylesheets first: the module loader's
   own loading waits for the bundles. On node there is no page; the order is
   checked here with a stand-in for linking, and in a real browser by
   groundwork.browser-routing-test."
  (:require
   [cljs.test :refer [async deftest is]]
   [groundwork.loader :as loader]))

(deftest a-module-s-code-loads-after-its-bundles
  (async done
    (let [said    (atom [])
          link    (fn [href]
                    (js/Promise. (fn [resolve _]
                                   (js/setTimeout #(do (swap! said conj [:linked href]) (resolve nil)) 20))))
          ;; the loader's own loadModules, standing in: it would put the code in the page
          own     (fn [ids _infos _options]
                    (this-as this (swap! said conj [:code (vec ids) (.-name this)])))
          styled  (loader/styled-first #js {:name "the module loader"} own link)]
      (loader/start! {"reports" "/css/reports.css" "maps" "/css/maps.css"})
      (-> (styled #js ["reports" "charts"] nil nil)
          (.then (fn [_]
                   (is (= [[:linked "/css/reports.css"]
                           [:code ["reports" "charts"] "the module loader"]]
                          @said)
                       "the bundle of each module that has one, then the code, on the loader")
                   (loader/start! {})
                   (done)))))))

(deftest nothing-to-wait-for-without-bundles
  (async done
    (loader/start! nil)
    (-> (loader/bundles-in! ["reports"] (fn [_] (throw (js/Error. "nothing to link"))))
        (.then (fn [_] (is true "resolved without linking") (done))))))
