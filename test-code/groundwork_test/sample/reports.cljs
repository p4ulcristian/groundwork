(ns groundwork-test.sample.reports
  "The reports page, in a module loaded on demand, with its own
   stylesheet. When its code runs it notes the colour its stylesheet gives
   .reports-probe, so the test sees the stylesheet was in first."
  (:require
   [groundwork-test.sample.browser :as browser]
   [groundwork-screens.api :as screens]
   [groundwork-browser-build.api :as styles]))

(def ^:private reports-styles (styles/use-stylesheet "groundwork_test/sample/reports.css"))

(defn- find-probe-colour []
  (let [probe (js/document.createElement "div")]
    (set! (.-className probe) "reports-probe")
    (.appendChild js/document.body probe)
    (let [colour (.-color (js/getComputedStyle probe))]
      (.remove probe)
      colour)))

(set! (.-sampleReportsColourAtLoad js/window) (find-probe-colour))

(defn- reports-page [_]
  [:section#reports.reports [:h1 (screens/tr :reports/title {})]])

(browser/add-drawer! :reports/page reports-page)
