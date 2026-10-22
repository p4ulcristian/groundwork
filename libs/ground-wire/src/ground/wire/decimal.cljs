(ns ground.wire.decimal
  "Exact decimals in the browser: once this namespace is loaded, a :decimal
   is a big.js value (big.js must be in the project's package.json). Not
   loaded, a decimal stays its text and no decimal library ships."
  (:require
   ["big.js" :default Big]
   [ground.wire :as wire]))

(def big
  "big.js, as ground.wire/use-exact-decimals! takes it."
  {:decimal? #(instance? Big %)
   :read     #(Big. %)
   :text     #(.toString ^js %)})

(wire/use-exact-decimals! big)
