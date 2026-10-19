(ns groundwork.endpoints.decimal
  "Exact decimals in the browser: a :decimal becomes a big.js value. The
   build loads this namespace before the app's code when the app's
   :browser :decimals? is true, and the app's package.json then has big.js;
   with :decimals? false the browser keeps a decimal as its text and ships
   no decimal library."
  (:require
   ["big.js" :default Big]
   [groundwork.endpoints.wire :as wire]))

(def big
  "big.js, as groundwork.endpoints.wire/use-exact-decimals! takes it."
  {:decimal? #(instance? Big %)
   :read     #(Big. %)
   :text     #(.toString ^js %)})

(wire/use-exact-decimals! big)
