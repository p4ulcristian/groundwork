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
  "Run SQL text on its own connection, outside Groundwork, with the search
   path on public as Groundwork's own connections have it. Returns the rows
   of a query, as next.jdbc gives them."
  [text & params]
  (with-open [conn (jdbc/get-connection {:dbtype   "postgresql"
                                         :host     (:host db-settings)
                                         :port     (:port db-settings)
                                         :dbname   (:database db-settings)
                                         :user     (:user db-settings)
                                         :password (:password db-settings)})]
    (jdbc/execute! conn ["SET search_path = public"])
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
   :page        {:lang "en" :title "Test" :description nil :icon "/icon.svg" :stylesheets ["/app.css"]
                 :head []}
   :browser     {:build :test-app :entry 'example.hello.browser/start :output-dir "test/public/js"
                 :asset-path "/js" :watch? false}
   :web         {:routes         []
                 :max-body-bytes 1048576
                 :content-security-policy {:connect-src ["https://api.example"]}
                 :login-path     "/login"
                 :on-request     nil
                 :on-error       nil
                 :proxies        {:trusted [] :client-ip-header nil :request-id-header nil}
                 :languages      nil
                 :test-sign-in   {:secret "a-secret-only-tests-know"}
                 :hashed-files   "\\.[0-9a-f]{8,}\\."
                 :gzip-min-bytes 1024}
   :server      {:ip "127.0.0.1" :port port}
   :sessions    {:cookie-name "gw_session" :max-age-days 30 :visitor-hours 24 :secure? false
                 :origin (str "http://127.0.0.1:" port)}
   :permissions {:scopes {} :records {} :rules []}
   :limits      {:public nil :on-limit nil}
   :jobs        nil
   :parquery    {:endpoints [] :max-asks 50 :max-running 4 :bearer nil
                 ;; every test namespace leaves its tables in the one test database
                 :bells {:global {} :personal {}
                         :silent {:groundwork/every-other-table "the test database is shared by every test"}}
                 :on-answer nil}})
