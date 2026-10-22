(ns groundwork.endpoints.decimal
  "Exact decimals in the browser live in the ground-wire library,
   ground.wire.decimal; the build still names this namespace, so it loads
   that one."
  (:require
   [ground.wire.decimal :as decimal]))

(def big
  "ground.wire.decimal's big.js, under its old name."
  decimal/big)
