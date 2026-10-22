(ns groundwork.server.sync.telling
  "Who is told of a batch of changes. Each change Postgres sent,

     {\"folder\" \"public\" \"table\" \"orders\" \"ids\" [\"…\"] \"columns\" {\"order__shop_id\" [\"…\"]}}

   goes to the open lines whose person may read one of the rows, by the
   table's readers: :anyone, every line; :signed-in, every line with a
   person; :me, the lines whose person a column names; :member, the people
   who hold a reading action in a scope a column names (one query per
   reader, for the whole batch). A change Postgres sent as \"all\" (too many
   rows to list) goes to every line a reader of its kind could be.

   The message is {:sync/changed {:table \"orders\" :ids [\"…\"]}}; :ids is
   left out when they weren't sent."
  (:require
   [groundwork.server.access.api :as access]
   [groundwork.server.database.api :as database]
   [groundwork.server.wire.api :as wire]))

(defn- turn-key-into-text [k] (subs (str k) 1))

(defn turn-payload-into-change
  "A notification's text as {:table key :ids #{…} or nil :columns {name #{…}} :all? bool}."
  [payload]
  (let [{:keys [folder table ids columns all]} (wire/turn-json-text-into-data payload)]
    {:table   (database/turn-table-name-into-table-key folder table)
     :ids     (some-> ids set)
     :columns (into {} (map (fn [[column values]] [(name column) (set values)])) columns)
     :all?    (boolean all)}))

(defn merge-changes
  "One change per table: ids and column values joined; :all? if any was."
  [changes]
  (vals (reduce (fn [by-table {:keys [table ids columns all?]}]
                  (update by-table table
                          (fn [merged]
                            (if merged
                              {:table   table
                               :ids     (when (and (:ids merged) ids) (into (:ids merged) ids))
                               :columns (merge-with into (:columns merged) columns)
                               :all?    (or (:all? merged) all?)}
                              {:table table :ids ids :columns columns :all? all?}))))
                {} changes)))

(defn- find-people-of-reader [pool {:keys [to column kind actions]} {:keys [columns all?]} people]
  (case to
    :anyone    :everyone
    :signed-in people
    :me        (if all? people (set (filter #(contains? (get columns column) (str %)) people)))
    :member    (if all?
                 people
                 (access/list-people-holding-actions pool {:kind kind :scope-ids (get columns column)
                                                           :actions actions :person-ids people}))))

(defn find-told-lines
  "The lines to tell of one change: every line when anyone may read it,
   else those whose person may."
  [pool rule change lines]
  (let [people (set (keep :person-id lines))
        told   (if (= :everyone rule)
                 :everyone
                 (reduce (fn [told reader]
                           (let [found (find-people-of-reader pool reader change people)]
                             (if (= :everyone found) (reduced :everyone) (into told found))))
                         #{} rule))]
    (if (= :everyone told)
      lines
      (filter #(contains? told (:person-id %)) lines))))

(defn make-message [{:keys [table ids all?]}]
  {:sync/changed (cond-> {:table (turn-key-into-text table)}
                   (and ids (not all?)) (assoc :ids (vec (sort ids))))})
