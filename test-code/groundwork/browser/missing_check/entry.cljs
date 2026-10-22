(ns groundwork.browser.missing-check.entry
  "Names a stylesheet that isn't there: the build must stop."
  (:require [groundwork.browser.styles.api :as styles]))

(def ^:private nowhere (styles/use-stylesheet "groundwork/browser/missing_check/nowhere.css"))

(defn start! [] nowhere)
