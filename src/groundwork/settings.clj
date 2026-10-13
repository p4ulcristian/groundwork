(ns groundwork.settings
  "Everything an app gives Groundwork, as malli schemas: the settings for
   gw/start, the permission description, each endpoint's entry and each
   route's data. Every key carries a short description and an example, so
   the same schemas check an app at start, write the settings reference in
   the README and the example settings file (docs/example-settings.edn), and
   answer the REPL's explain-setting.

   At start every problem is reported at once, each with its exact place:

     :sessions :visitor-hours must be a positive whole number, got \"24\"

   What needs more than the shape (an action the description doesn't have,
   a column the database lacks, a route the permissions can't check) is
   checked by the part itself, after this."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [groundwork.parquery.wire :as wire]
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

(def ^:private actions
  [:set {:error/message "must be a set of namespaced keywords, like #{:orders/see}"
         :doc/type "set of actions"}
   qualified])

(def ^:private arrow
  (pred "[kind column]" "must be [kind column], like [:shop :order/shop-id]"
        #(and (vector? %) (= 2 (count %)) (keyword? (first %)) (qualified-keyword? (second %)))))

(defn- condition?
  "[:= col v] [:not= col v] [:in col [v …]] [:is-null col] [:not-null col]
   [:and …] [:or …], on the record's own columns."
  [c]
  (and (vector? c)
       (let [[op & args] c]
         (case op
           (:and :or)          (and (seq args) (every? condition? args))
           (:= :not=)          (and (= 2 (count args)) (qualified-keyword? (first args)))
           :in                 (and (= 2 (count args)) (qualified-keyword? (first args)) (sequential? (second args)))
           (:is-null :not-null) (and (= 1 (count args)) (qualified-keyword? (first args)))
           false))))

(def ^:private condition
  (pred "condition" "must be a condition like [:= :order/status \"open\"] (see groundwork.permissions.rules)"
        condition?))

;; =============================================================================
;; The permission description
;; =============================================================================

(def scope-kind
  [:map {:closed true :doc/key "<scope kind>"}
   (entry :actions "The actions of this kind of scope" #{:orders/see :orders/edit} actions)
   (entry :roles "The starting roles every new scope of this kind gets: name → actions"
          {"Manager" #{:orders/see :orders/edit}}
          [:map-of {:error/message "must map role names (text) to sets of actions" :doc/type "role name → actions"}
           text actions])
   (entry :parent "The kind of scope above this one, or nil" nil
          (pred "keyword or nil" "must be a scope kind keyword, or nil" #(or (nil? %) (keyword? %))))])

(def record-kind
  [:map {:closed true :doc/key "<record kind>"}
   (entry :table "Its table" :orders kind-name)
   (entry :id "Its id column" :order/id column)
   (entry :in "Its scope column: [scope-kind column], or a list of them for a record shared by scopes"
          [:shop :order/shop-id]
          (pred "[kind column] or a list of them" "must be [scope-kind column], or a list of them"
                #(or (and (vector? %) (keyword? (first %)) (= 2 (count %)) (qualified-keyword? (second %)))
                     (and (vector? %) (seq %) (every? (fn [a] (and (vector? a) (= 2 (count a))
                                                                   (keyword? (first a)) (qualified-keyword? (second a))))
                                                      %)))))
   (optional :parent "The record it hangs under: [record-kind its-column]" [:order :job/order-id] arrow)
   (optional :people "An action a person on the record holds: action → their column"
             {:orders/see :order/customer-id}
             [:map-of {:error/message "must map actions to person columns" :doc/type "action → column"}
              qualified column])
   (optional :only-when "An action allowed only when the record matches: action → condition"
             {:orders/edit [:= :order/status "open"]}
             [:map-of {:error/message "must map actions to conditions" :doc/type "action → condition"}
              qualified condition])])

(def link
  [:map {:closed true :doc/key "[…]"}
   (entry :table "The linking record's table" :agreements kind-name)
   (entry :from "The scope the person is in: [kind column]" [:partner :agreement/partner-id] arrow)
   (entry :to "The scope the link reaches: [kind column]" [:shop :agreement/shop-id] arrow)
   (entry :when "While this holds" [:= :agreement/status "active"] condition)
   (entry :grants "What it gives in :to: given action → the action needed in :from"
          {:orders/see :agreements/see}
          [:map-of {:error/message "must map actions to actions" :doc/type "action → action"} qualified qualified])])

(def permission-description
  [:map {:closed true}
   (entry :scopes "The kinds of scope: where data belongs and people are members"
          {:shop {:actions #{:orders/see :orders/edit} :roles {"Manager" #{:orders/see :orders/edit}} :parent nil}}
          [:map-of {:error/message "must map scope kinds to their actions, roles and parent" :doc/type "kind → scope kind"}
           kind-name scope-kind])
   (entry :records "The kinds of record, each in a scope"
          {:order {:table :orders :id :order/id :in [:shop :order/shop-id]}}
          [:map-of {:error/message "must map record kinds to their table, id and scope" :doc/type "kind → record kind"}
           kind-name record-kind])
   (entry :links "Scopes reaching other scopes through a linking record" []
          [:vector {:error/message "must be a list of links" :doc/type "list of links"} link])
   (entry :session-blocks "A session flag blocks actions: flag → actions" {:impersonated #{:money/move}}
          [:map-of {:error/message "must map session flags to sets of actions" :doc/type "flag → actions"}
           kind-name actions])])

;; =============================================================================
;; Endpoints and routes
;; =============================================================================

(def ^:private permission
  (pred ":public, :signed-in or {:action … :record|:scope|:list …}"
        "must be :public, :signed-in, {:action … :record [kind param]}, {:action … :scope [kind param-or-id]} or {:action … :list kind}"
        (fn [p]
          (or (#{:public :signed-in} p)
              (and (map? p) (qualified-keyword? (:action p)) (= 2 (count p))
                   (let [[k v] (first (dissoc p :action))]
                     (case k
                       :record (and (vector? v) (= 2 (count v)) (every? keyword? v))
                       :scope  (and (vector? v) (= 2 (count v)) (keyword? (first v))
                                    (or (keyword? (second v)) (uuid? (second v))))
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
    (entry :permission "Who may ask it" {:action :jobs/see :scope [:shop :shop/id]} permission)
    (entry :input "What may be sent, a malli :map; id fields say their record kind"
           [:map [:shop/id :uuid]] malli-shape)
    (entry :answer "What may be sent back, a malli shape" [:vector [:map [:job/id :uuid]]] malli-shape)
    (optional :reads "Tables a read uses that Postgres can't see in its SQL" [:jobs]
              [:vector {:error/message "must be a list of tables, like [:jobs]" :doc/type "list of tables"} :keyword])
    (optional :rate-limit "How often a caller may ask it (see groundwork.limits)" {:per-day 20 :by :person} rate-limit)]
   (pred "one handler" "needs exactly one of :read or :write, its handler"
         #(= 1 (count (filter (fn [k] (contains? % k)) [:read :write]))))])

(def job
  [:map {:closed true :doc/key "<job name>"}
   (entry :run "Its handler: (fn [ctx params] …); ctx has :db (its transaction), :job, :attempt, :jobs"
          'app.mail/send-confirmation function)
   (entry :params "What it is given, a malli shape" [:map [:order/id :uuid]] malli-shape)
   (entry :timeout-ms "How long one run may take" 60000 positive)
   (entry :attempts "How many times it runs before it is dead" 5 positive)
   (optional :scope "The wall shows only this scope: [kind param], a scope kind or a record kind whose id is in params"
             [:order :order/id]
             (pred "[kind param]" "must be [kind param], like [:order :order/id]"
                   #(and (vector? %) (= 2 (count %)) (every? keyword? %))))])

(def ^:private access
  (pred ":public, :signed-in or {:action … :record [kind param]}"
        "must be :public, :signed-in, or {:action … :record [kind param]}"
        (fn [a]
          (or (#{:public :signed-in} a)
              (and (map? a) (= #{:action :record} (set (keys a))) (qualified-keyword? (:action a))
                   (vector? (:record a)) (= 2 (count (:record a))) (every? keyword? (:record a)))))))

(def ^:private route-page-keys
  #{:lang :title :description :canonical :alternates :stylesheets :scripts :head :body :module})

(def route
  [:and {:doc/key "<path>"}
   [:map
    (optional :access "Who may use it (or :server-to-server true)" :public access)
    (optional :server-to-server "Called by another server (a webhook): no session, no Origin check; the handler checks the call"
              true [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (optional :embeddable "Other sites may show it in a frame: true (any https site), :same-origin, or a list of origins"
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
   (pred "declares access" "must declare :access, or be :server-to-server true (not both)"
         #(if (:server-to-server %) (not (contains? % :access)) (contains? % :access)))])

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
    (entry :user "The user Groundwork connects as" "app" text)
    (entry :password "Its password, or nil for none" nil text-or-nil)
    (entry :pool-size "Connections per server, a fixed number" 10 positive)
    (entry :statement-timeout-ms "Postgres cancels a statement that runs longer; :none for no limit" 30000
           (pred "positive whole number or :none" "must be a positive whole number or :none"
                 #(or (= :none %) (pos-int? %))))
    (entry :time-zone "The session's time zone: how times without an offset are read" "UTC"
           [:re {:error/message "must be a time zone name like \"UTC\" or \"Europe/Budapest\"" :doc/type "time zone name"}
            #"^[A-Za-z0-9_+\-/]+$"])
    (optional :on-transaction "Runs first in every with-transaction: (fn [tx] …)" 'app.db/on-transaction function)
    (optional :on-query "After every query: (fn [{:keys [file sql ms error]}] …)" 'app.db/on-query function)]

   :migrations
   [:map {:closed true}
    (entry :schema-snapshot "The file development writes the whole schema to, or nil for none" "schema.sql" text-or-nil)]

   :page
   [:map {:closed true}
    (entry :lang "The page's language" "en" text)
    (entry :title "The browser tab's title" "Acme Coating" text)
    (entry :description "A one-sentence summary for search results and link previews, or nil"
           "Powder coating, ordered online" text-or-nil)
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
    (optional :modules "The build's modules, for an app with more than one: module → {:init-fn … or :entries […], :depends-on #{…}}. Without it there is one, :main, starting :entry"
              {:base {:init-fn 'app.browser/start} :admin {:entries ['app.admin.view] :depends-on #{:base}}}
              [:map-of {:error/message "must be a map of module → {:init-fn or :entries, :depends-on}" :doc/type "map of modules"}
               :keyword
               [:map {:closed true}
                [:init-fn {:optional true} qualified-symbol?]
                [:entries {:optional true} [:vector symbol?]]
                [:depends-on {:optional true} [:set :keyword]]
                [:web-worker {:optional true} :boolean]]])
    (optional :loader? "Whether modules no page names load on demand (shadow-cljs's module loader)" true
              [:boolean {:error/message "must be true or false" :doc/type "true or false"}])
    (optional :options "Extra shadow-cljs build options, merged over Groundwork's: JS resolution, build hooks, compiler options"
              {:js-options {:js-provider :shadow}}
              [:map {:error/message "must be a map of shadow-cljs build options" :doc/type "map"}])]

   :web
   [:map {:closed true}
    (entry :routes "The app's routes, reitit data; each route's data is checked like below"
           '[["/" {:get groundwork.web/page :access :public}]
             ["/orders/:order-id" {:get groundwork.web/page :access {:action :orders/see :record [:order :order-id]}}]
             ["/webhooks/payments" {:post app.payments/webhook :server-to-server true}]]
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
    (entry :languages "The languages the app speaks, or nil for one"
           {:supported ["en" "de"] :default "en" :cookie "lang"}
           [:maybe
            [:and
             [:map {:closed true}
              (entry :supported "Language codes" ["en" "de"] [:vector {:min 1 :doc/type "list of language codes"} lang-code])
              (entry :default "The language when nothing else says" "en" lang-code)
              (entry :cookie "A cookie holding the person's choice, or nil" "lang" name-or-nil)]
             (pred "default among supported" ":default must be one of :supported"
                   #(some #{(:default %)} (:supported %)))]])
    (entry :test-sign-in "Lets automated tests sign in, given the secret; nil to switch off, never in production"
           nil
           [:maybe [:map {:closed true}
                    (entry :secret "At least 16 characters" "a-secret-only-tests-know"
                           [:string {:min 16 :error/message "must be at least 16 characters" :doc/type "text, 16+ characters"}])]])
    (entry :eval-bridge "Evaluates code from outside, given the secret; nil to switch off, never in production"
           nil
           [:maybe [:map {:closed true}
                    (entry :secret "At least 16 characters" "a-secret-only-the-previews-know"
                           [:string {:min 16 :error/message "must be at least 16 characters" :doc/type "text, 16+ characters"}])
                    (entry :timeout-ms "How long one evaluation may run" 30000 positive)
                    (entry :max-chars "The longest result sent back" 20000 positive)]])
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

   :parquery
   [:map {:closed true}
    (entry :endpoints "The app's endpoint maps, gathered from its areas" '[app.jobs/endpoints app.orders/endpoints]
           endpoint-list)
    (entry :max-asks "The most asks one request may carry" 50 positive)
    (entry :max-running "The most asks running at once across the server; keep it below the pool size" 16 positive)
    (entry :bearer "Whose token a tool calls with: (fn [token] person-id-or-nil), or nil" 'app.tools/token->person
           function-or-nil)
    (entry :on-answer "After every ask over HTTP: (fn [{:keys [endpoint status ms person request-id]}] …), or nil"
           'app.metrics/on-answer function-or-nil)
    (entry :bells "Tables outside the permission description that ring too"
           {:global #{:powder_colours} :personal {:notifications :notification/person-id}}
           [:map {:closed true}
            (entry :global "Tables every watcher of them hears about" #{:powder_colours}
                   [:set {:error/message "must be a set of table keywords" :doc/type "set of tables"} :keyword])
            (entry :personal "Tables only their person hears about: table → person column"
                   {:notifications :notification/person-id}
                   [:map-of {:error/message "must map tables to person columns" :doc/type "table → column"}
                    :keyword column])])]))

(def settings
  "gw/start's settings: one key per part, every one required."
  (into [:map {:closed true}]
        (map (fn [[k s]] [k {} s]))
        parts))

(def ^:private part-descriptions
  {:environment "Where the app runs"
   :db          "The database"
   :migrations  "The schema's changes, run at start"
   :page        "The page shell every screen loads into"
   :browser     "The app's browser code and its build"
   :web         "Requests: the app's routes, limits, hooks, languages"
   :server      "The web server"
   :sessions    "Who is signed in"
   :permissions "Who may do what, where: the permission description"
   :limits      "How often a caller may ask"
   :jobs        "Background jobs and schedules, or nil for none"
   :parquery    "The endpoints and how they're asked"})

;; =============================================================================
;; Problems
;; =============================================================================

(defn- shown [v]
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

(defn- environment-problems [settings]
  (when (= :production (:environment settings))
    (for [k [:test-sign-in :eval-bridge]
          :when (some? (get-in settings [:web k]))]
      {:in [:web k] :message "is never on in production"})))

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

;; =============================================================================
;; Reading the schemas: the REPL, the README, the example file
;; =============================================================================

(defn- props [s] (m/properties (m/schema s)))

(defn- map-schema
  "The :map inside a schema, through :maybe and :and, or nil."
  [s]
  (let [s (m/schema s)]
    (case (m/type s)
      :map   s
      :maybe (map-schema (first (m/children s)))
      :and   (some map-schema (m/children s))
      nil)))

(defn- type-text [s]
  (let [s (m/schema s)
        p (m/properties s)]
    (or (:doc/type p)
        (case (m/type s)
          :map     "map"
          :maybe   (str (type-text (first (m/children s))) " or nil")
          :and     (type-text (first (m/children s)))
          :enum    (str/join ", " (map pr-str (m/children s)))
          :string  "text"
          :boolean "true or false"
          :int     "whole number"
          :keyword "keyword"
          :vector  "list"
          "value"))))

(defn- rows
  "Every documented key under a schema: {:path :type :description :example}."
  [s path]
  (when-let [ms (map-schema s)]
    (mapcat (fn [[k p child]]
              (let [here   (conj path k)
                    nested (map-schema child)
                    values (let [c (m/schema child)]
                             (when (#{:map-of :vector :sequential} (m/type c))
                               (map-schema (last (m/children c)))))]
                (cons {:path here :type (type-text child) :description (:description p) :example (:example p)
                       :optional? (boolean (:optional p))}
                      (cond
                        nested (rows nested here)
                        values (rows values (conj here (or (:doc/key (props (last (m/children (m/schema child))))) "…")))))))
            (m/children ms))))

(defn all
  "Every documented key of the settings, in order."
  []
  (mapcat (fn [[part s]]
            (cons {:path [part] :type (type-text s) :description (part-descriptions part)
                   :example (when-not (map-schema s) (:example (props s)))}
                  (rows s [part])))
          parts))

(defn explain
  "What goes at a path of the settings: (explain [:sessions :visitor-hours])."
  [path]
  (or (some #(when (= path (:path %)) %) (all))
      (throw (ex-info (str "No setting " (pr-str path)) {:type ::no-setting :path path}))))

(defn- cell [s] (str/replace (str s) "|" "\\|"))

(defn- table [entries]
  (str "| Key | Type | What it is | Example |\n"
       "|-----|------|------------|---------|\n"
       (apply str (for [{:keys [path type description example optional?]} entries]
                    (str "| `" (str/join " " (map #(if (keyword? %) (pr-str %) %) path)) "`"
                         (when optional? " (optional)")
                         " | " (cell type) " | " (cell description) " | "
                         (if (some? example) (str "`" (cell (shown example)) "`") "") " |\n")))))

(defn reference
  "The README's settings reference, as Markdown."
  []
  (str "Generated from `groundwork.settings`; `(groundwork.repl/explain-setting [:sessions :visitor-hours])`\n"
       "answers the same in the REPL, and `docs/example-settings.edn` holds every key with an example.\n\n"
       (apply str
              (for [[part s] parts]
                (str "#### `" (pr-str part) "`: " (part-descriptions part) "\n\n"
                     (if-let [entries (seq (rows s [part]))]
                       (table entries)
                       (str "`" (type-text s) "`\n"))
                     "\n")))
       "#### A route's data (in `:web :routes`)\n\n" (table (rows route ["<path>"])) "\n"
       "#### An endpoint's entry (in `:parquery :endpoints`)\n\n" (table (rows endpoint ["<endpoint name>"]))))

(defn- pretty [v indent]
  (let [lines (str/split-lines (str/trimr (with-out-str (pprint/pprint v))))]
    (str/join (str "\n" (apply str (repeat indent " "))) lines)))

(defn- example-map [s indent]
  (let [pad (apply str (repeat indent " "))]
    (str "{"
         (str/join (str "\n\n" pad " ")
                   (for [[k p child] (m/children (map-schema s))]
                     (let [nested (map-schema child)
                           key-s  (pr-str k)]
                       (str ";; " (:description p) (when (:optional p) " (optional)") "\n" pad " "
                            (if (and nested (not (some? (:example p))) (not= :maybe (m/type (m/schema child))))
                              (str key-s "\n" pad " " (example-map nested (inc indent)))
                              (if (and nested (= :maybe (m/type (m/schema child))))
                                (str key-s " " (pretty (:example p) (+ indent 2 (count key-s))))
                                (if nested
                                  (str key-s "\n" pad " " (example-map nested (inc indent)))
                                  (str key-s " " (pretty (:example p) (+ indent 2 (count key-s)))))))))))
         "}")))

(defn example
  "The example settings file: every key, an example value and a comment."
  []
  (str ";; Every setting gw/start takes, with an example and what it is.\n"
       ";; Generated from groundwork.settings; don't edit by hand.\n"
       "{"
       (str/join "\n\n "
                 (for [[part s] parts]
                   (let [key-s (pr-str part)]
                     (str ";; " (part-descriptions part) "\n "
                          (cond
                            (map-schema s)     (str key-s "\n " (example-map s 1))
                            :else              (str key-s " " (pr-str (first (m/children (m/schema s))))))))))
       "}\n"))
