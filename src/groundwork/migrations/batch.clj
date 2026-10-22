(ns groundwork.migrations.batch
  "Groundwork's own statements, made from the settings (the installs, the
   grants), sent to the database together."
  (:require
   [groundwork.postgres :as pg])
  (:import
   (java.sql BatchUpdateException Connection)))

(defn execute-all!
  "Run statements on tx's connection, in order, sent together as one JDBC
   batch: a few round trips for hundreds of statements, where one at a
   time took a round trip each. Postgres still runs them one after the
   other, so a statement sees what the ones before it made. The first that
   fails stops the rest and throws ::failed, naming it (:statement, nil
   when PgJDBC didn't say which); the transaction rolls back. The statements are Groundwork's own: no params, no JDBC
   escapes."
  [tx statements]
  (let [statements (vec statements)]
    (when (seq statements)
      (with-open [st (.createStatement ^Connection (::pg/conn tx))]
        (.setEscapeProcessing st false)
        (doseq [^String s statements]
          (.addBatch st s))
        (try
          (.executeBatch st)
          nil
          (catch BatchUpdateException e
            ;; PgJDBC names the entry that failed only in its message
            (let [entry  (some-> (re-find #"Batch entry (\d+) " (str (ex-message e))) second parse-long)
                  failed (some->> entry (get statements))
                  cause  (or (.getNextException e) e)]
              (throw (ex-info (str (if failed (str "Statement " (inc entry) " of " (count statements)) "A statement")
                                   " failed: " (ex-message cause)
                                   (when failed (str "\n" failed)))
                              {:type ::failed :statement failed}
                              cause)))))))))
