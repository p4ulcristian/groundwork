(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire, groundwork.steps, and on
   the server groundwork.transport, groundwork.routes, groundwork.sql, groundwork.background-chores,
   groundwork.schema and groundwork.sessions;
   main grows as libraries are added."
  (:require
   [groundwork.steps.api]
   #?(:clj [groundwork.background-chores.api])
   #?(:clj [groundwork.schema.api])
   #?(:clj [groundwork.sessions.api])
   #?(:clj [groundwork.sql.api])
   [groundwork.routes.api]
   [groundwork.transport.api]
   [groundwork.wire.api]))
