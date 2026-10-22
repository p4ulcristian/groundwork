(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire, groundwork.steps and
   groundwork.web (on the server);
   main grows as libraries are added."
  (:require
   [groundwork.steps.api]
   #?(:clj [groundwork.web.api])
   [groundwork.wire.api]))
