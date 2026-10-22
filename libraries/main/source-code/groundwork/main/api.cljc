(ns groundwork.main.api
  "Groundwork's main library: the one an app requires first, which puts the
   other libraries together. Today: groundwork.wire and groundwork.steps;
   main grows as libraries are added."
  (:require
   [groundwork.steps.api]
   [groundwork.wire.api]))
