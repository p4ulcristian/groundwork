(ns groundwork.browser.unnamed-check.entry
  "Names one of the two stylesheets beside it: a release build must stop
   on the other."
  (:require [groundwork.browser.styles.api :as styles]))

(def ^:private named (styles/use-stylesheet "groundwork/browser/unnamed_check/named.css"))

(defn start! [] named)
