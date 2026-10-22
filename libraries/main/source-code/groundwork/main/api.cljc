(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire on both sides;
   groundwork.transport-browser in the browser; groundwork.steps,
   groundwork.database, groundwork.transport-server,
   groundwork.sessions, groundwork.access and groundwork.background-chores on the
   server. main grows as libraries are added."
  (:require
   [groundwork.wire.api]
   #?(:cljs [groundwork.transport-browser.api])
   #?(:clj [groundwork.background-chores.api])
   #?(:clj [groundwork.database.api])
   #?(:clj [groundwork.access.api])
   #?(:clj [groundwork.sessions.api])
   #?(:clj [groundwork.steps.api])
   #?(:clj [groundwork.transport-server.api])))
