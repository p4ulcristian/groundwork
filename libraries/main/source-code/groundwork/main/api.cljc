(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire, groundwork.steps and
   groundwork.transport on both sides; groundwork.database, groundwork.auth
   and groundwork.background-chores on the server. main grows as libraries
   are added."
  (:require
   [groundwork.steps.api]
   [groundwork.transport.api]
   [groundwork.wire.api]
   #?(:clj [groundwork.auth.api])
   #?(:clj [groundwork.background-chores.api])
   #?(:clj [groundwork.database.api])))
