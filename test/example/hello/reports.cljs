(ns example.hello.reports
  "The example app's reports screen, in a module of its own when the build
   has one: its code and its stylesheet load on arrival.")

(defn screen []
  [:p#screen {:class [:css.panel/frame :css.reports/screen]} "Reports"])
