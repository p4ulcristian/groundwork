(ns groundwork.browser.build-check-admin
  "A module the build check loads on demand, with its own stylesheet."
  (:require [browser-build.api :as styles]))

(def ^:private admin-styles (styles/use-stylesheet "groundwork/browser/build_check_admin.css"))

(js/console.log "build-check-admin" admin-styles)
