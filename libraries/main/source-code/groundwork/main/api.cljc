(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire, groundwork.steps, and on
   the server groundwork.web, groundwork.sql and groundwork.background-jobs;
   main grows as libraries are added."
  (:require
   [groundwork.steps.api]
   #?(:clj [groundwork.background-jobs.api])
   #?(:clj [groundwork.sql.api])
   #?(:clj [groundwork.web.api])
   [groundwork.wire.api]))
