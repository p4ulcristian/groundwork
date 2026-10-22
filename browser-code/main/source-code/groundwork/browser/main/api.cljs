(ns groundwork.browser.main.api
  "The browser's one start: puts the browser libraries together. Today it
   only lists them; it grows into reading the settings from the page and
   starting the rest. The server's start is groundwork.server.main."
  (:require
   [groundwork.browser.transport.api]
   [groundwork.browser.wire.api]))
