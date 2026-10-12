(ns groundwork.test-support
  "What every test needs: the local database groundwork_test (or
   GROUNDWORK_TEST_HOST / _PORT / _DB / _USER / _PASSWORD) and a way to run
   plain SQL against it outside Groundwork."
  (:require
   [next.jdbc :as jdbc]))

(defn- env [k default] (or (System/getenv k) default))

(def db-settings
  {:host                 (env "GROUNDWORK_TEST_HOST" "127.0.0.1")
   :port                 (parse-long (env "GROUNDWORK_TEST_PORT" "5432"))
   :database             (env "GROUNDWORK_TEST_DB" "groundwork_test")
   :user                 (env "GROUNDWORK_TEST_USER" "groundwork")
   :password             (env "GROUNDWORK_TEST_PASSWORD" nil)
   :pool-size            2
   :statement-timeout-ms 5000
   :time-zone            "UTC"})

(defn sql!
  "Run SQL text on its own connection, outside Groundwork. Returns the rows of
   a query, as next.jdbc gives them."
  [text & params]
  (with-open [conn (jdbc/get-connection {:dbtype   "postgresql"
                                         :host     (:host db-settings)
                                         :port     (:port db-settings)
                                         :dbname   (:database db-settings)
                                         :user     (:user db-settings)
                                         :password (:password db-settings)})]
    (jdbc/execute! conn (into [text] params))))

(defn free-port
  "A port nothing listens on right now."
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(def port (free-port))

(def app-settings
  "Settings for a whole test app: a free port, no routes, a plain page."
  {:environment :test
   :db          db-settings
   :migrations  {:schema-snapshot nil}
   :page        {:lang "en" :title "Test" :icon "/icon.svg" :stylesheets ["/app.css"]
                 :script "/js/main.js" :head []}
   :routes      []
   :server      {:ip "127.0.0.1" :port port}
   :sessions    {:cookie-name "gw_session" :max-age-days 30 :secure? false
                 :origin (str "http://127.0.0.1:" port)}})
