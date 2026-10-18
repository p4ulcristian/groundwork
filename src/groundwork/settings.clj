(ns groundwork.settings
  "Everything an app gives Groundwork, as malli schemas: the settings for
   gw/start, the permission description, each endpoint's entry and each
   route's data. Every key carries a short description and an example, so
   the same schemas check an app at start, write the settings reference in
   the README and the example settings file (docs/example-settings.edn), and
   answer the REPL's explain-setting.

   The README's settings reference, the example file and the REPL's
   explain-setting are written from these schemas by
   groundwork.settings.docs.

   At start every problem is reported at once, each with its exact place:

     :sessions :visitor-hours must be a positive whole number, got \"24\"

   What needs more than the shape (an action the description doesn't have,
   a column the database lacks, a route the permissions can't check) is
   checked by the part itself, after this."
  (:require
   [clojure.string :as str]
   [groundwork.endpoints.wire :as wire]
   [malli.core :as m]
   [malli.error :as me]
   [reitit.core :as r])
  (:import
   (java.net InetAddress)))

;; =============================================================================
;; Pieces
;; =============================================================================

(defn- entry
  "A map entry with its description and example."
  ([k description example schema] (entry k description example schema nil))
  ([k description example schema more]
   [k (merge {:description description :example example} more) schema]))

(defn- optional [k description example schema]
  (entry k description example schema {:optional true}))

(defn- pred
  "A predicate with the message a person reads and the type the docs show."
  [doc-type message f]
  [:fn {:error/message message :doc/type doc-type} f])

(def ^:private text
  [:string {:min 1 :error/message "must be text, not empty" :doc/type "text"}])

(def ^:private positive
  [:int {:min 1 :error/message "must be a positive whole number" :doc/type "positive whole number"}])

(def ^:private positive-or-nil
  (pred "positive whole number or nil" "must be a positive whole number or nil" #(or (nil? %) (pos-int? %))))

(def ^:private port-number
  [:int {:min 0 :max 65535 :error/message "must be a port number, 0 to 65535" :doc/type "port number"}])

(def ^:private function-or-nil
  (pred "function or nil" "must be a function or nil" #(or (nil? %) (ifn? %))))

(def ^:private function
  (pred "function" "must be a function" ifn?))

(def ^:private text-or-nil
  (pred "text or nil" "must be text or nil" #(or (nil? %) (and (string? %) (not (str/blank? %))))))

(def ^:private texts
  [:vector {:error/message "must be a list of text, like [\"a\" \"b\"]" :doc/type "list of text"} :string])

(def ^:private name-text
  [:re {:error/message "must be letters, digits, _ or -" :doc/type "name"} #"^[A-Za-z0-9_\-]+$"])

(def ^:private name-or-nil
  (pred "name or nil" "must be letters, digits, _ or -, or nil"
        #(or (nil? %) (and (string? %) (boolean (re-matches #"[A-Za-z0-9_\-]+" %))))))

(def ^:private lang-code
  [:re {:error/message "must be a language code like \"en\" or \"pt-br\"" :doc/type "language code"}
   #"^[a-z]{2}(-[a-z]{2})?$"])

(def ^:private origin
  [:re {:error/message "must be like \"https://app.example.com\", with no path" :doc/type "origin"}
   #"^https?://[^/\s;,]+$"])

(defn- address-or-range?
  "\"10.0.0.0/8\", \"::1\": a numeric address, optionally with its prefix length."
  [s]
  (and (string? s)
       (let [[address bits] (str/split s #"/" 2)
             bytes          (when (re-matches #"[0-9A-Fa-f:.]+" address)
                              (try (.getAddress (InetAddress/getByName address)) (catch Exception _ nil)))
             bits           (if bits (parse-long bits) (some-> bytes alength (* 8)))]
         (boolean (and bytes bits (<= 0 bits (* 8 (alength ^bytes bytes))))))))

(defn- regex-text? [s]
  (and (string? s) (try (some? (re-pattern s)) (catch Exception _ false))))

(defn- shape? [x]
  (try (some? (wire/schema x)) (catch Exception _ false)))

(def ^:private malli-shape
  (pred "malli shape" "must be a malli shape, like [:map [:job/id :uuid]]" shape?))

(def ^:private kind-name
  [:keyword {:error/message "must be a keyword, like :shop" :doc/type "keyword"}])

(def ^:private qualified
  (pred "namespaced keyword" "must be a namespaced keyword, like :orders/edit" qualified-keyword?))

(def ^:private column
  (pred "column keyword" "must be a column keyword, like :order/shop-id" qualified-keyword?))

(def ^:private action-commands
  [:map-of {:error/message "must map namespaced actions to what each does at the wall, like {:orders/see #{:read}}"
            :doc/type      "action → commands"}
   qualified
   [:set {:error/message "must be a set of :read :insert :update :delete" :doc/type "set of commands"}
    [:enum :read :insert :update :delete]]])

(def ^:private actions
  [:set {:error/message "must be a set of namespaced keywords, like #{:orders/see}"
         :doc/type "set of actions"}
   qualified])

(defn- condition?
  "[:= col v] [:not= col v] [:in col [v …]] [:is-null col] [:not-null col]
   [:none col] [:and …] [:or …], on the record's own columns."
  [c]
  (and (vector? c)
       (let [[op & args] c]
         (case op
           (:and :or)                 (and (seq args) (every? condition? args))
           (:= :not=)                 (and (= 2 (count args)) (qualified-keyword? (first args)))
           :in                        (and (= 2 (count args)) (qualified-keyword? (first args)) (sequential? (second args)))
           (:is-null :not-null :none) (and (= 1 (count args)) (qualified-keyword? (first args)))
           false))))

(def ^:private condition
  (pred "condition" "must be a condition like [:= :order/status \"open\"] (see groundwork.permissions.description)"
        condition?))

;; =============================================================================
;; The permission description
;; =============================================================================

(def scope-kind
  [:map {:closed true :doc/key "<scope kind>"}
   (entry :actions "The actions of this kind of scope, each with what it does at the wall: :read, :insert, :update, :delete"
          {:orders/see #{:read} :orders/edit #{:read :update}} action-commands)
   (entry :roles "The starting roles every new scope of this kind gets: name → actions"
          {"Manager" #{:orders/see :orders/edit}}
          [:map-of {:error/message "must map role names (text) to sets of actions" :doc/type "role name → actions"}
           text actions])
   (entry :parent "The kind of scope above this one, or nil" nil
          (pred "keyword or nil" "must be a scope kind keyword, or nil" #(or (nil? %) (keyword? %))))
   (optional :single? "This kind has exactly one scope (a platform's back office): [:member <kind>] names it"
             true [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
   (optional :table "The app's own table of these scopes, where a scope's state lives (with :id)" :shops kind-name)
   (optional :id "Its id column, the scope's id" :shop/id column)])

(def record-kind
  [:map {:closed true :doc/key "<record kind>"}
   (entry :table "Its table" :orders kind-name)
   (entry :id "Its id column" :order/id column)
   (entry :whose "Whose row each column names: column → a scope kind, :person or :visitor (the id of a visitor's session)"
          {:order/shop-id :shop :order/customer-id :person}
          [:map-of {:error/message "must map columns to a scope kind, :person or :visitor"
                    :doc/type      "column → whose"}
           column kind-name])
   (optional :parent "The record it hangs under: [record-kind its-column], and what it carries from it when either has more than one scope, or a person: [record-kind its-column {its column → the parent's}]"
             [:order :job/order-id]
             (pred "[kind column] or [kind column {column column}]"
                   "must be [record-kind column], or [record-kind column {its column → the parent's column}]"
                   #(and (vector? %) (<= 2 (count %) 3) (keyword? (first %)) (qualified-keyword? (second %))
                         (or (= 2 (count %))
                             (let [carried (nth % 2)]
                               (and (map? carried) (seq carried)
                                    (every? qualified-keyword? (keys carried))
                                    (every? qualified-keyword? (vals carried))))))))])

(defn- who?
  ":anyone, :signed-in, [:member x] or [:member x action], [:me column]."
  [w]
  (or (#{:anyone :signed-in} w)
      (and (vector? w) (= :member (first w)) (<= 2 (count w) 3)
           (let [[_ x h] w] (and (or (keyword? x) (uuid? x)) (or (nil? h) (qualified-keyword? h)))))
      (and (vector? w) (= :me (first w)) (= 2 (count w)) (qualified-keyword? (second w)))))

(def ^:private who
  (pred "who" "must be :anyone, :signed-in, [:member <column, single-scope kind or scope id> <action held there, optional>] or [:me <column>]"
        who?))

(def ^:private action-or-actions
  (pred "action or set of actions" "must be an action like :orders/see, or a set of them"
        #(or (qualified-keyword? %) (and (set? %) (seq %) (every? qualified-keyword? %)))))

(def via
  [:map {:closed true :doc/key ":via"}
   (entry :table "The related row's table; its record kind says whose its columns are" :agreements kind-name)
   (entry :match "Which columns match: the row's column → the related row's" {:order/shop-id :agreement/shop-id}
          [:map-of {:error/message "must map the row's columns to the related row's" :doc/type "column → column"}
           column column])
   (entry :to "Who must hold on the related row" [:member :agreement/partner-id :agreements/see] who)
   (optional :when "While this holds on the related row" [:= :agreement/status "active"] condition)])

(def sentence
  [:and {:doc/key "[…]"}
   [:map {:closed true}
    (optional :allow "Allows this action (or each of a set of them): a sentence {:allow … :on … :to-or-:via … :when …}"
              :orders/see action-or-actions)
    (optional :on "The kind of record it is on" :order kind-name)
    (optional :to "To whom: :anyone, :signed-in, [:member <column | single-scope kind | scope id> <action held, optional>], [:me <column>]"
              [:member :order/shop-id] who)
    (optional :via "Or through a related row: one that exists for which its :to and :when hold"
              {:table :agreements :match {:order/shop-id :agreement/shop-id}
               :to [:member :agreement/partner-id :agreements/see] :when [:= :agreement/status "active"]}
              via)
    (optional :when "Only while the row matches; [:none <scope column>] for a row that belongs to nobody"
              [:= :order/status "open"] condition)
    (optional :deny "Or denies this action, these, or :writes (every action that writes), before any yes"
              :money/move (pred "action, set of actions or :writes" "must be an action, a set of them, or :writes"
                                #(or (= :writes %) (qualified-keyword? %)
                                     (and (set? %) (seq %) (every? qualified-keyword? %)))))
    (optional :session "A deny's session flag" :impersonated kind-name)
    (optional :scope "A deny's scope kind, whose state (its :when on the kind's :table) denies" :shop kind-name)]
   (pred "a sentence" "must be {:allow … :on … :to …} (or :via instead of :to), or {:deny … :session flag} or {:deny … :scope kind :when …}"
         #(= 1 (count (filter (fn [k] (contains? % k)) [:allow :deny]))))])

(def permission-description
  [:map {:closed true}
   (entry :scopes "The kinds of scope: where data belongs and people are members"
          {:shop {:actions {:orders/see #{:read} :orders/edit #{:read :update}}
                  :roles {"Manager" #{:orders/see :orders/edit}} :parent nil}}
          [:map-of {:error/message "must map scope kinds to their actions, roles and parent" :doc/type "kind → scope kind"}
           kind-name scope-kind])
   (entry :records "The kinds of record, and whose row each column names"
          {:order {:table :orders :id :order/id :whose {:order/shop-id :shop :order/customer-id :person}}}
          [:map-of {:error/message "must map record kinds to their table, id and :whose" :doc/type "kind → record kind"}
           kind-name record-kind])
   (entry :rules "Who may do what: one list of sentences, read at start (see groundwork.permissions.description)"
          [{:allow :orders/see :on :order :to [:member :order/shop-id]}
           {:deny :money/move :session :impersonated}]
          [:vector {:error/message "must be a list of sentences" :doc/type "list of sentences"} sentence])])

;; =============================================================================
;; Endpoints and routes
;; =============================================================================

(def ^:private access
  (pred ":public, :signed-in or {:action … :record|:scope|:scopes|:list …}"
        "must be :public, :signed-in, {:action … :record [kind param]}, {:action … :scope [kind param-or-id]}, {:action … :scopes [kind param-or-path]} or {:action … :list kind}"
        (fn [p]
          (or (#{:public :signed-in} p)
              (and (map? p) (qualified-keyword? (:action p))
                   (= 2 (count p))
                   (let [[k v] (first (dissoc p :action))]
                     (case k
                       :record (and (vector? v) (= 2 (count v)) (every? keyword? v))
                       :scope  (and (vector? v) (= 2 (count v)) (keyword? (first v))
                                    (or (keyword? (second v)) (uuid? (second v))))
                       :scopes (and (vector? v) (= 2 (count v)) (keyword? (first v))
                                    (or (keyword? (second v))
                                        (and (vector? (second v)) (seq (second v)) (every? keyword? (second v)))))
                       :list   (keyword? v)
                       false)))))))

(def ^:private rate-limit
  (pred "{:per-minute n :per-day n :by …}"
        "must be {:per-minute n} and/or {:per-day n}, optionally :by :person, :scope or :client-ip"
        (fn [l]
          (and (map? l) (seq (select-keys l [:per-minute :per-day]))
               (every? #{:per-minute :per-day :by} (keys l))
               (every? #(or (nil? %) (pos-int? %)) ((juxt :per-minute :per-day) l))
               (contains? #{nil :person :scope :client-ip} (:by l))))))

(def endpoint
  [:and {:doc/key "<endpoint name>"}
   [:map {:closed true}
    (optional :read "Its handler, for a read: (fn [ctx params] answer)" 'app.jobs/list function)
    (optional :write "Its handler, for a write: (fn [ctx params] answer)" 'app.jobs/move function)
    (entry :access "Who may ask it: :public, :signed-in, or an action on a record, a scope, several scopes or a list (see groundwork.endpoints)"
           {:action :jobs/see :scope [:shop :shop/id]} access)
    (entry :input "What may be sent, a malli :map; id fields say their record kind"
           [:map [:shop/id :uuid]] malli-shape)
    (entry :answer "What may be sent back, a malli shape" [:vector [:map [:job/id :uuid]]] malli-shape)
    (optional :reads "Tables a read uses that Postgres can't see in its SQL" [:jobs]
              [:vector {:error/message "must be a list of tables, like [:jobs]" :doc/type "list of tables"} :keyword])
    (optional :rate-limit "How often a caller may ask it (see groundwork.limits)" {:per-day 20 :by :person} rate-limit)
    (optional :keeps-for-visitor? "A write that keeps something for someone not signed in: a visitor without a session gets one, so the row can be theirs"
              true [:boolean {:error/message "must be true or false" :doc/type "true or false"}])]
   (pred "one handler" "needs exactly one of :read or :write, its handler"
         #(= 1 (count (filter (fn [k] (contains? % k)) [:read :write]))))])

(def job
  [:map {:closed true :doc/key "<job name>"}
   (entry :run "Its handler: (fn [ctx params] …); ctx has :db (its transaction), :job, :attempt, :jobs"
          'app.mail/send-confirmation function)
   (entry :params "What it is given, a malli shape" [:map [:order/id :uuid]] malli-shape)
   (entry :timeout-ms "How long one run may take" 60000 positive)
   (entry :attempts "How many times it runs before it is dead" 5 positive)
   (optional :runs-in "Where it runs: the wall shows only this scope, [kind param], a scope kind or a record kind whose id is in params"
             [:order :order/id]
             (pred "[kind param]" "must be [kind param], like [:order :order/id]"
                   #(and (vector? %) (= 2 (count %)) (every? keyword? %))))])

(def ^:private route-page-keys
  #{:title :description :canonical :alternates :stylesheets :scripts :head :body :module})

(def route
  [:and {:doc/key "<path>"}
   [:map
    (optional :access "Who may use it, the shape an endpoint's :access has; a route checks :public, :signed-in or an action on a record (or :server-to-server? true)"
              :public access)
    (optional :server-to-server? "Called by another server (a webhook): no session, no Origin check; the handler checks the call"
              true [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (optional :embeddable? "Other sites may show it in a frame: true (any https site), :same-origin, or a list of origins"
              true (pred "true, :same-origin or origins" "must be true, :same-origin, or a list of origins like \"https://shop.example\""
                         #(or (true? %) (= :same-origin %)
                              (and (vector? %) (seq %) (every? (fn [o] (and (string? o) (re-matches #"https?://[^/\s;,]+" o))) %)))))
    (optional :page "The page shell's own details for this route: a map, or a function of the request"
              {:title "Design your part"}
              (pred "map or function" (str "must be a function of the request or a map of "
                                           (str/join " " (sort route-page-keys)))
                    #(or (fn? %) (var? %) (and (map? %) (every? route-page-keys (keys %))))))
    (optional :max-body-bytes "The largest body it accepts, over the app's :max-body-bytes" 104857600 positive)
    (optional :rate-limit "How often a caller may use it, counted by :person or :client-ip (see groundwork.limits)"
              {:per-minute 10 :by :client-ip} rate-limit)]
   (pred "declares access" "must declare :access, or be :server-to-server? true (not both)"
         #(if (:server-to-server? %) (not (contains? % :access)) (contains? % :access)))])

;; =============================================================================
;; The settings
;; =============================================================================

(def ^:private csp-directive-names
  [:default-src :script-src :style-src :img-src :font-src :connect-src :media-src :frame-src :worker-src
   :form-action])

(def ^:private endpoint-list
  [:sequential {:error/message "must be a list of the app's endpoint maps" :doc/type "list of endpoint maps"}
   [:map-of {:error/message "must map endpoint names (namespaced keywords) to entries" :doc/type "name → endpoint"}
    qualified endpoint]])

(def parts
  "Each part's settings, in the order of gw/start's map."
  (array-map
   :environment
   [:enum {:error/message "must be :development, :test or :production" :doc/type ":development, :test or :production"}
    :development :test :production]

   :db
   [:map {:closed true}
    (entry :host "The database server's address" "localhost" text)
    (entry :port "Its port" 5432 port-number)
    (entry :database "The database's name" "app" text)
    (entry :user "The role the app connects as; in production one that owns nothing and can't pass by row-level security (migrate connects as the owner)" "app" text)
    (entry :password "Its password, or nil for none" nil text-or-nil)
    (entry :pool-size "Connections per server, a fixed number" 10 positive)
    (entry :statement-timeout-ms "Postgres cancels a statement that runs longer; :none for no limit" 30000
           (pred "positive whole number or :none" "must be a positive whole number or :none"
                 #(or (= :none %) (pos-int? %))))
    (entry :time-zone "The session's time zone: how times without an offset are read" "UTC"
           [:re {:error/message "must be a time zone name like \"UTC\" or \"Europe/Budapest\"" :doc/type "time zone name"}
            #"^[A-Za-z0-9_+\-/]+$"])
    (entry :tls (str "How the connection travels: :verified (encrypted; the server's certificate checked against the trusted "
                     "certificates, and its name against :host) or :off (plain; in production only for a database on the "
                     "same machine: localhost, 127.x.x.x or ::1)")
           :verified
           [:enum {:error/message "must be :verified or :off" :doc/type ":verified or :off"} :verified :off])
    (optional :tls-root-cert (str "With :tls :verified, a PEM file of the certificates the server's must lead to, "
                                  "instead of the Java runtime's trusted ones (a private certificate authority)")
              "/etc/app/db-root.pem" text)
    (optional :on-transaction "Runs first in every with-transaction: (fn [tx] …)" 'app.db/on-transaction function)
    (optional :on-query "After every query: (fn [{:keys [file sql ms error]}] …)" 'app.db/on-query function)]

   :migrations
   [:map {:closed true}
    (entry :schema-snapshot "The file development writes the whole schema to, or nil for none" "schema.sql" text-or-nil)]

   :page
   [:map {:closed true}
    (entry :title "The browser tab's title" "Acme Orders" text)
    (entry :description "A one-sentence summary for search results and link previews, or nil"
           "Orders and invoices for small shops" text-or-nil)
    (entry :icon "The tab's icon" "/icon.svg" text)
    (entry :stylesheets "The CSS files every page loads" ["/app.css"] texts)
    (entry :head "Extra tags at the end of <head>, as HTML text" [] texts)]

   :browser
   [:map {:closed true}
    (entry :build "The browser build's id" :app kind-name)
    (entry :entry "The app's browser entry: the function that calls groundwork.browser/start!"
           'app.browser/start
           (pred "namespaced symbol" "must be a namespaced symbol naming a function, like app.browser/start"
                 qualified-symbol?))
    (entry :output-dir "Where the compiled files go, a folder whose public/ is served" "resources/public/js" text)
    (entry :asset-path "Where the browser fetches them" "/js"
           [:re {:error/message "must be a path like \"/js\"" :doc/type "path"} #"^/.*"])
    (entry :watch? "In development, whether the start also watches the build" true
           [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (optional :modules "The build's modules, for an app with more than one: module → {:init-fn … or :entries […], :depends-on #{…}, :web-worker? …}. Without it there is one, :main, starting :entry"
              {:base {:init-fn 'app.browser/start} :admin {:entries ['app.admin.view] :depends-on #{:base}}}
              [:map-of {:error/message "must be a map of module → {:init-fn or :entries, :depends-on}" :doc/type "map of modules"}
               :keyword
               [:map {:closed true}
                [:init-fn {:optional true} qualified-symbol?]
                [:entries {:optional true} [:vector symbol?]]
                [:depends-on {:optional true} [:set :keyword]]
                [:web-worker? {:optional true} :boolean]]])
    (optional :loader? "Whether modules no page names load on demand (shadow-cljs's module loader)" true
              [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (optional :options "Extra shadow-cljs build options, merged over Groundwork's: JS resolution, build hooks, compiler options"
              {:js-options {:js-provider :shadow}}
              [:map {:error/message "must be a map of shadow-cljs build options" :doc/type "map"}])]

   :styles
   [:maybe
    [:map {:closed true}
     (entry :sources "Where the browser code and its stylesheets are: every .css under them is named by a namespace's {:css […]}, and in development a saved one rebuilds the bundles"
            ["src"] texts)
     (entry :output-dir "Where the bundles and their manifest go, a folder whose public/ is served" "resources/public/css" text)
     (entry :asset-path "Where the browser fetches them" "/css"
            [:re {:error/message "must be a path like \"/css\"" :doc/type "path"} #"^/.*"])
     (entry :targets "The browsers the stylesheets are built for, a browserslist query: lightningcss flattens nesting and writes vendor prefixes for them, in development and release; every build stops on a query browserslist doesn't know or that names no browser"
            "safari >= 16.4, chrome >= 119"
            (pred "browserslist query" "must be a browserslist query, like \"safari >= 16.4, chrome >= 119\""
                  #(and (string? %) (not (str/blank? %)))))]]

   :web
   [:map {:closed true}
    (entry :routes "The app's routes, reitit data; each route's data is checked like below"
           '[["/" {:get groundwork.web/page :access :public}]
             ["/orders/:order-id" {:get groundwork.web/page :access {:action :orders/see :record [:order :order-id]}}]
             ["/webhooks/payments" {:post app.payments/webhook :server-to-server? true}]]
           [:vector {:error/message "must be a vector of reitit routes" :doc/type "reitit routes"} any?])
    (entry :max-body-bytes "The largest request body any route accepts (a route may allow more)" 1048576 positive)
    (entry :content-security-policy "Sources the app adds to the strict policy, per directive"
           {:img-src ["https://cdn.example"]}
           [:map-of {:error/message (str "must map directives (" (str/join " " csp-directive-names) ") to lists of sources")
                     :doc/type "directive → sources"}
            (into [:enum] csp-directive-names) texts])
    (entry :login-path "Where a signed-out browser asking for a page is sent" "/login"
           [:re {:error/message "must be a path like \"/login\"" :doc/type "path"} #"^/.*"])
    (entry :on-request "After every answer, for logs and metrics, or nil" 'app.log/request function-or-nil)
    (entry :on-error "An exception no handler caught, or nil" 'app.log/error function-or-nil)
    (entry :proxies "Which addresses are trusted proxies, and the headers believed from them"
           {:trusted ["10.0.0.0/8"] :client-ip-header "cf-connecting-ip" :request-id-header "x-request-id"}
           [:map {:closed true}
            (entry :trusted "Trusted proxy addresses or ranges" ["10.0.0.0/8" "::1"]
                   [:vector {:doc/type "list of addresses or ranges"}
                    (pred "address or range" "must be an address or range like \"10.0.0.0/8\" or \"::1\"" address-or-range?)])
            (entry :client-ip-header "The header holding the visitor's address, or nil" "cf-connecting-ip" text-or-nil)
            (entry :request-id-header "The header holding a request id to keep, or nil" "x-request-id" text-or-nil)])
    (entry :languages "The languages the app speaks, the one place a language is set: the page takes the request's; an app of one language lists just it"
           {:supported ["en" "de"] :default "en" :cookie "lang"}
           [:and
            [:map {:closed true}
             (entry :supported "Language codes" ["en" "de"] [:vector {:min 1 :doc/type "list of language codes"} lang-code])
             (entry :default "The language when nothing else says" "en" lang-code)
             (entry :cookie "A cookie holding the person's choice, or nil" "lang" name-or-nil)]
            (pred "default among supported" ":default must be one of :supported"
                  #(some #{(:default %)} (:supported %)))])
    (entry :test-sign-in "Lets automated tests sign in, given the secret; nil to switch off, never in production"
           nil
           [:maybe [:map {:closed true}
                    (entry :secret "At least 16 characters" "a-secret-only-tests-know"
                           [:string {:min 16 :error/message "must be at least 16 characters" :doc/type "text, 16+ characters"}])]])
    (entry :hashed-files "Files whose names match (a regular expression, as text) are cached a year; nil for none"
           "(?i)\\.[0-9a-f]{8,}\\."
           (pred "regular expression text or nil" "must be a regular expression as text, or nil"
                 #(or (nil? %) (regex-text? %))))
    (entry :gzip-min-bytes "Text-like answers at least this big go gzipped; nil for none" 1024 positive-or-nil)
    (optional :unknown-page "What a browser gets at an address no route knows: nil (or left out) for the page shell, whose browser routing may know it; or a handler, for an app whose pages are all routes (its own 404)"
              'app.pages/not-found function-or-nil)]

   :server
   [:map {:closed true}
    (entry :ip "The address to listen on" "0.0.0.0" text)
    (entry :port "The port to listen on; 0 picks a free one" 8080 port-number)]

   :sessions
   [:map {:closed true}
    (entry :cookie-name "The session cookie's name" "acme_session" name-text)
    (entry :max-age-days "A signed-in session's lifetime" 30 positive)
    (entry :visitor-hours "A visitor's session ends after this long unseen" 24 positive)
    (entry :secure? "Cookies only over https; false only for plain-http development" true
           [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (entry :origin "Where the app is served from; a changing request must come from here" "https://app.acme.example" origin)]

   :permissions
   permission-description

   :limits
   [:map {:closed true}
    (entry :public "What a public endpoint or route without its own limit gets, per caller each minute, or nil"
           {:reads-per-minute 600 :writes-per-minute 60}
           [:maybe [:map {:closed true}
                    (entry :reads-per-minute "Reads, and GET routes" 600 positive)
                    (entry :writes-per-minute "Writes, and changing routes" 60 positive)]])
    (entry :on-limit "Every caller turned away: (fn [{:keys [id limit caller]}] …), or nil"
           'app.log/limited function-or-nil)]

   :jobs
   [:maybe
    [:map {:closed true}
     (entry :kinds "The app's job kinds, gathered from its areas" '[app.mail/jobs]
            [:sequential {:error/message "must be a list of the app's job maps" :doc/type "list of job maps"}
             [:map-of {:error/message "must map job names (namespaced keywords) to entries" :doc/type "name → job"}
              qualified job]])
     (entry :schedules "Jobs run on a clock, cron lines in UTC"
            [{:job :app.reminders/send :params {} :cron "0 18 * * *"}]
            [:vector {:error/message "must be a list of schedules" :doc/type "list of schedules"}
             [:map {:closed true}
              (entry :job "The job kind" :app.reminders/send qualified)
              (entry :params "Its params" {} [:map-of {:doc/type "map"} :keyword any?])
              (entry :cron "When: minute hour day-of-month month day-of-week, in UTC" "0 18 * * *"
                     (pred "cron line" "must be five cron fields, like \"0 18 * * *\""
                           #(and (string? %) (= 5 (count (str/split (str/trim %) #"\s+"))))))]])
     (entry :workers "Jobs running at once on this server" 4 positive)
     (entry :poll-ms "How often to look for due jobs when nothing wakes the workers" 5000 positive)
     (entry :on? "Whether this server runs jobs; false on previews, where they wait" true
            [:boolean {:error/message "must be true or false" :doc/type "true or false"}])]]

   :endpoints
   [:map {:closed true}
    (entry :list "The app's endpoint maps, gathered from its areas" '[app.jobs/endpoints app.orders/endpoints]
           endpoint-list)
    (entry :max-asks "The most asks one request may carry" 50 positive)
    (entry :max-running "The most asks running at once across the server; keep it below the pool size" 16 positive)
    (entry :bearer "Whose token a tool calls with: (fn [token] person-id-or-nil), or nil" 'app.tools/token->person
           function-or-nil)
    (entry :on-answer "After every ask over HTTP: (fn [{:keys [endpoint status ms person request-id]}] …), or nil"
           'app.metrics/on-answer function-or-nil)
    (entry :bells "How the tables outside the permission description ring"
           {:global {:countries "reference data only the platform's staff change"}
            :personal {:notifications :notification/person-id}}
           [:map {:closed true}
            (entry :global
                   (str "Tables every watcher of them hears about: table → the reason. A global table is one "
                        "whose rows belong to nobody, or whose every row is read by everyone; "
                        "a table whose rows belong to a scope goes in the permission description")
                   {:countries "reference data only the platform's staff change"}
                   [:map-of {:error/message "must map tables to the reason they are global" :doc/type "table → reason"}
                    :keyword text])
            (entry :personal "Tables only their person hears about: table → person column"
                   {:notifications :notification/person-id}
                   [:map-of {:error/message "must map tables to person columns" :doc/type "table → column"}
                    :keyword column])
            (optional :silent
                      (str "Tables that ring nothing, on purpose: table → the reason. "
                           "Every table of the app says how it rings, here or in the permission description, "
                           "or the start refuses; :groundwork/every-other-table covers the rest of a database "
                           "the app shares with something else")
                      {:import_log "written once by the nightly import, shown nowhere"}
                      [:map-of {:error/message "must map tables to the reason they are silent" :doc/type "table → reason"}
                       :keyword text])])]))

(def ^:private optional-parts
  "Parts an app may leave out, with what leaving them out means."
  #{:styles})

(def settings
  "gw/start's settings: one key per part, every one required, but :styles."
  (into [:map {:closed true}]
        (map (fn [[k s]] [k (if (optional-parts k) {:optional true} {}) s]))
        parts))

(def part-descriptions
  "What each part of the settings is, for the docs."
  {:environment "Where the app runs"
   :db          "The database"
   :migrations  "The schema's changes: run by the start in development and tests, by migrate before it in production"
   :page        "The page shell every screen loads into"
   :browser     "The app's browser code and its build"
   :styles      "The app's stylesheets, named by its namespaces and bundled per browser module; nil or left out when the app brings its own through :page :stylesheets"
   :web         "Requests: the app's routes, limits, hooks, languages"
   :server      "The web server"
   :sessions    "Who is signed in"
   :permissions "Who may do what, where: the permission description"
   :limits      "How often a caller may ask"
   :jobs        "Background jobs and schedules, or nil for none"
   :endpoints   "The endpoints and how they're asked (at the address /parquery)"})

;; =============================================================================
;; Problems
;; =============================================================================

(defn shown
  "A value as the docs and the messages show it: cut short when long."
  [v]
  (let [s (pr-str v)]
    (if (< 60 (count s)) (str (subs s 0 57) "…") s)))

(defn- message [error]
  (case (:type error)
    ::m/missing-key  "is missing"
    ::m/extra-key    "isn't a setting Groundwork knows"
    ::me/misspelled-key (me/error-message error)
    (str (me/error-message error) ", got " (shown (:value error)))))

(defn- explained
  "The problems malli finds with a value, under the path prefix."
  [schema value prefix]
  (when-let [explanation (m/explain schema value)]
    (->> (:errors (me/with-spell-checking explanation))
         (map (fn [error] {:in (into prefix (:in error)) :message (message error)}))
         distinct)))

(defn route-problems
  "Every route's data checked, with what the routes above it gave it."
  [routes prefix]
  (try
    (mapcat (fn [[path data]] (explained route (dissoc data :conflicting) (conj prefix path)))
            (r/routes (r/router routes {:conflicts nil})))
    (catch Exception e
      [{:in prefix :message (str "aren't reitit routes: " (ex-message e))}])))

(defn same-machine?
  "Whether a database host is this machine: localhost, 127.x.x.x or ::1."
  [host]
  (boolean (and (string? host)
                (or (#{"localhost" "::1" "[::1]"} (str/lower-case host))
                    (re-matches #"127\.\d{1,3}\.\d{1,3}\.\d{1,3}" host)))))

(defn- db-problems
  "What the :db's keys say together: a root certificate goes only with
   :verified, and production's database travels plain only on this machine."
  [{:keys [environment db]}]
  (when (map? db)
    (concat
     (when (and (contains? db :tls-root-cert) (not= :verified (:tls db)))
       [{:in [:db :tls-root-cert] :message "goes only with :tls :verified"}])
     (when (and (= :production environment) (= :off (:tls db)) (string? (:host db))
                (not (same-machine? (:host db))))
       [{:in [:db :tls] :message (str "may be :off in production only for a database on the same machine "
                                      "(localhost, 127.x.x.x or ::1), not " (pr-str (:host db)))}]))))

(defn- environment-problems [settings]
  (concat
   (when (= :production (:environment settings))
     (for [k [:test-sign-in]
           :when (some? (get-in settings [:web k]))]
       {:in [:web k] :message "is never on in production"}))
   (db-problems settings)))

(defn part-problems
  "The problems with one part's settings."
  ([part value] (part-problems part value nil))
  ([part value environment]
   (vec (concat (explained (parts part) value [part])
                (when (and (= :web part) (map? value) (vector? (:routes value)))
                  (route-problems (:routes value) [:web :routes]))
                (environment-problems {:environment environment part value})))))

(defn problems
  "Every problem with an app's settings, [{:in [...] :message \"...\"}]."
  [settings-map]
  (vec (concat (explained settings settings-map [])
               (when (and (map? (:web settings-map)) (vector? (get-in settings-map [:web :routes])))
                 (route-problems (get-in settings-map [:web :routes]) [:web :routes]))
               (environment-problems settings-map))))

(defn- report [found]
  (str "Settings have " (count found) (if (= 1 (count found)) " problem" " problems") ":\n"
       (str/join "\n" (for [{:keys [in message]} found]
                        (str "  " (str/join " " (map pr-str in)) " " message)))))

(defn- fail! [found]
  (when (seq found)
    (throw (ex-info (report found) {:type ::invalid :problems found}))))

(defn check!
  "Throws with every problem at once, or returns the settings."
  [settings-map]
  (fail! (problems settings-map))
  settings-map)

(defn check-part!
  "Throws with every problem in one part's settings, or returns them."
  ([part value] (check-part! part value nil))
  ([part value environment]
   (fail! (part-problems part value environment))
   value))

(defn check-value!
  "Throws with every problem a value has against one of the schemas here
   (route, endpoint, permission-description), under the path prefix."
  [schema value prefix]
  (fail! (vec (explained schema value prefix)))
  value)
