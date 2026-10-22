(ns groundwork.server.sync.telling
  "What a batch of Postgres's notifications tells. Each one names a table,

     {\"folder\" \"public\" \"table\" \"orders\"}

   and every open line hears each changed table once:
   {:sync/changed {:table \"orders\"}}."
  (:require
   [cellar.api :as database]
   [jsonista.core :as json]))

(defn turn-payload-into-table
  "A notification's text as the table key it names."
  [payload]
  (let [{:strs [folder table]} (json/read-value payload)]
    (database/turn-table-name-into-table-key folder table)))

(defn list-changed-tables
  "The distinct tables a batch of payloads names, in the order first named."
  [payloads]
  (distinct (map turn-payload-into-table payloads)))

(defn make-message [table]
  {:sync/changed {:table (subs (str table) 1)}})
