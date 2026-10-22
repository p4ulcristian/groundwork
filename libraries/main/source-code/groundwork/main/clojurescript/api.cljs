(ns groundwork.main.clojurescript.api
  "The main library's api in the browser: an app's one start. For now it
   opens onto today's Groundwork (groundwork.browser)."
  (:require
   [groundwork.browser :as browser]))

(defn start!
  "Start the browser half: {:root :routes :shapes …}, as groundwork.browser/start! takes it."
  [options]
  (browser/start! options))
