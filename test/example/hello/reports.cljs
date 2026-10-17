(ns example.hello.reports
  "The example app's reports screen, in a module of its own when the build
   has one: its code and its stylesheet load on arrival. It names the shared
   look's stylesheet too, which the main module's bundle carries already."
  {:css ["reports.css" "views.css"]})

(def bundle-at-load
  "What the page's stylesheets said the moment this code ran: \"in\" when
   the module's bundle was in the page before its code."
  (when (exists? js/document)
    (.trim (.getPropertyValue (js/getComputedStyle js/document.documentElement) "--reports-bundle"))))

(defn screen []
  [:p#screen.panel.reports {:data-bundle-at-load bundle-at-load} "Reports"])
