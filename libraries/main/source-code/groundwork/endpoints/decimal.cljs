(ns groundwork.endpoints.decimal
  "Exact decimals in the browser live in the groundwork.wire library,
   groundwork.wire.clojurescript.big; the build still names this namespace,
   so it loads that one."
  (:require
   [groundwork.wire.clojurescript.big :as big]))

(def big
  "groundwork.wire.clojurescript.big's big.js, under its old name."
  big/big)
