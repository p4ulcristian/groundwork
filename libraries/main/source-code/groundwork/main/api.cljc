(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire, groundwork.steps, and on
   the server groundwork.web, groundwork.sql, groundwork.background-chores and
   groundwork.schema;
   main grows as libraries are added."
  (:require
   [groundwork.steps.api]
   #?(:clj [groundwork.background-chores.api])
   #?(:clj [groundwork.schema.api])
   #?(:clj [groundwork.sql.api])
   [groundwork.web.api]
   [groundwork.wire.api]))
