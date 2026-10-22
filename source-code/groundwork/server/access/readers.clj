(ns groundwork.server.access.readers
  "Who may read a table's rows, from its :access sentences, as data a
   reader of changes can use without SQL (groundwork.server.sync):

     {:orders [{:to :member :column \"order__shop_id\" :kind :shop :actions #{:orders/see :orders/edit}}
               {:to :me :column \"order__customer_id\"}]
      :news   [{:to :anyone}]}

   Only sentences allowing an action that reads count. A sentence's :when
   is left out: those it names may read some of the rows, never more."
  (:require
   [groundwork.server.access.sentences :as sentences]
   [groundwork.server.database.api :as database]))

(defn- find-reading-actions [{:keys [allow]} actions-do]
  (set (filter #(contains? (get actions-do %) :read) (if (set? allow) allow #{allow}))))

(defn- turn-sentence-into-reader [{:keys [to] :as sentence} table place actions-do]
  (let [reading (find-reading-actions sentence actions-do)]
    (when (seq reading)
      (cond
        (#{:anyone :signed-in} to) {:to to}
        (= :me (first to))         {:to :me :column (database/turn-key-into-column-name (second to))}
        (= :member (first to))     {:to      :member
                                    :column  (database/turn-key-into-column-name (second to))
                                    :kind    (sentences/find-scope-kind-of-column table (second to) (:tables-by-key place) (:scope-kinds place))
                                    :actions reading}))))

(defn list-readers-of-tables
  "{table-key [reader …]} for every table with :access: :public is
   [{:to :anyone}]; a table without :access isn't in it."
  [tables scope-kinds]
  (let [place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds}
        actions-do (apply merge (map :actions (vals scope-kinds)))]
    (into {}
          (for [{:keys [access] :as table} tables
                :when access]
            [(:table table)
             (if (= :public access)
               [{:to :anyone}]
               (vec (distinct (keep #(turn-sentence-into-reader % table place actions-do) access))))]))))
