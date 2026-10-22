(ns groundwork.server.main.api
  "The server's one start: puts the server libraries together. Today it
   only lists them; it grows into reading the settings, migrating and
   starting the server. The browser's start is groundwork.browser.main."
  (:require
   [groundwork.server.access.api]
   [groundwork.server.background-chores.api]
   [groundwork.server.database.api]
   [groundwork.server.sessions.api]
   [groundwork.server.steps.api]
   [groundwork.server.transport.api]
   [groundwork.server.wire.api]))
