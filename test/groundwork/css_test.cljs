(ns groundwork.css-test
  (:require
   ["react-dom/server" :as server]
   [cljs.test :refer [deftest is testing]]
   [groundwork.css :as css]
   [reagent.core :as r]))

(defn- html [hiccup]
  (server/renderToStaticMarkup (r/as-element hiccup css/compiler)))

(defn- console-errors
  "What f writes to the console's errors."
  [f]
  (let [said     (atom [])
        original js/console.error]
    (set! js/console.error (fn [& args] (swap! said conj (apply str args))))
    (try (f) (finally (set! js/console.error original)))
    @said))

(deftest a-class-keyword-becomes-the-class-name
  (css/start! {})
  (is (= "job-card__title" (css/class-names :css.job-card/title)))
  (is (= "card__frame job-card__card" (css/class-names [:css.card/frame :css.job-card/card])))
  (testing "nil and false are left out, in any depth"
    (is (= "card__frame job-card__selected"
           (css/class-names [:css.card/frame nil (when false :css.job-card/card) false [nil [:css.job-card/selected]]])))
    (is (nil? (css/class-names nil)))
    (is (nil? (css/class-names [nil false []]))))
  (testing "a library's own class is text"
    (is (= "calendar__root vc-day" (css/class-names [:css.calendar/root "vc-day"]))))
  (testing "a class keyword that isn't one is refused"
    (is (thrown-with-msg? ExceptionInfo #"Not a class keyword" (css/class-names :css.Job/title)))))

(deftest every-element-goes-through-the-door
  (css/start! {})
  (letfn [(button [props label] [:button (update props :class #(vector :css.button/button %)) label])
          (screen []
            [:main {:class [:css.panel/frame (when false :css.panel/open)]}
             [:h1 {:class :css.panel/title} "Jobs"]
             [button {:class :css.toolbar/save} "Save"]
             [:<> [:i {:class "vc-day"}] [:b {:id "plain"}]]
             [:> "section" {:class [:css.panel/part]} "x"]])]
    (is (= (str "<main class=\"panel__frame\"><h1 class=\"panel__title\">Jobs</h1>"
                "<button class=\"button__button toolbar__save\">Save</button>"
                "<i class=\"vc-day\"></i><b id=\"plain\"></b>"
                "<section class=\"panel__part\">x</section></main>")
           (html [screen])))))

(deftest a-state-from-data-is-looked-up-in-a-table
  (let [stage-class {:coating :css.job-card/coating :curing :css.job-card/curing nil nil}]
    (is (= :css.job-card/curing (css/lookup stage-class :curing)))
    (is (nil? (css/lookup stage-class nil)) "the table may say: no class for this one")
    (is (thrown-with-msg? ExceptionInfo #"No class for :shipped" (css/lookup stage-class :shipped)))))

(deftest in-development-an-unknown-class-is-reported-once
  (css/start! {:classes ["panel__frame"]})
  (let [said (console-errors #(do (html [:div {:class [:css.panel/frame :css.panel/ghost]}])
                                  (html [:div {:class :css.panel/ghost}])))]
    (is (= [":css.panel/ghost is no class of any stylesheet (.panel__ghost)"] said)))
  (testing "a release knows no list, and says nothing"
    (css/start! {})
    (is (= [] (console-errors #(html [:div {:class :css.panel/other}]))))))

(deftest a-module-on-demand-has-nothing-to-wait-for-without-styles
  (css/start! {:styles {"reports" "/css/reports.css"}})
  (is (instance? js/Promise (css/ensure-module! :main)))
  (is (instance? js/Promise (css/ensure-module! :reports)) "on node there is no page to put a stylesheet in")
  (is (instance? js/Promise (css/ensure-module! nil))))
