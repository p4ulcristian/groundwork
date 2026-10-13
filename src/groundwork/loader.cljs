(ns groundwork.loader
  "Code loaded on demand (a build with :loader? true) arrives as a <script>
   tag, never as text that is evaluated: the page's policy allows scripts
   from the app's own address and no eval, so the module loader's default,
   fetch and evaluate, would be refused without a word."
  (:require
   [shadow.loader :as loader]))

(.setUseScriptTags ^js (loader/getModuleLoader) true)
