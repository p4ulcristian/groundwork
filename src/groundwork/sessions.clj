(ns groundwork.sessions
  "Who is signed in. A session is a row in groundwork.sessions; the browser's
   cookie holds 32 random bytes and the table keeps only their SHA-256, so a
   copy of the table can't sign anyone in.

     :cookie-name   \"acme_session\"
     :max-age-days  30
     :secure?       true      false only for plain-http development
     :origin        \"https://app.acme.example\"   where the app is served from

   The app's login step (an identity provider, a gate, a password) ends with
   sign-in; sign-out, sign-out-everywhere! and expiry end sessions.

   A request that changes something (anything but GET, HEAD, OPTIONS) must
   come from the app's own pages: its Origin header must be :origin, or it is
   refused. With the cookie's SameSite=Lax, another site can't act as the
   person who is signed in."
  (:require
   [clojure.string :as str]
   [groundwork.postgres :as pg])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(pg/defsql insert-session "groundwork/sessions/insert.sql")
(pg/defsql session-by-key "groundwork/sessions/by_key.sql")
(pg/defsql delete-by-key "groundwork/sessions/delete_by_key.sql")
(pg/defsql delete-for-person "groundwork/sessions/delete_for_person.sql")
(pg/defsql delete-expired "groundwork/sessions/delete_expired.sql")

(def ^:private settings [:cookie-name :max-age-days :secure? :origin])

(defn- check-settings [opts]
  (when-not (map? opts)
    (throw (ex-info ":sessions must be a map" {:type ::bad-setting :setting :sessions :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str ":sessions needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (let [{:keys [cookie-name max-age-days secure? origin]} opts]
    (when-not (and (string? cookie-name) (re-matches #"[A-Za-z0-9_\-]+" cookie-name))
      (throw (ex-info ":cookie-name must be letters, digits, _ or -"
                      {:type ::bad-setting :setting :cookie-name :value cookie-name})))
    (when-not (pos-int? max-age-days)
      (throw (ex-info ":max-age-days must be a positive integer"
                      {:type ::bad-setting :setting :max-age-days :value max-age-days})))
    (when-not (boolean? secure?)
      (throw (ex-info ":secure? must be true or false"
                      {:type ::bad-setting :setting :secure? :value secure?})))
    (when-not (and (string? origin) (re-matches #"https?://[^/]+" origin))
      (throw (ex-info ":origin must be like \"https://app.example.com\", with no path"
                      {:type ::bad-setting :setting :origin :value origin})))))

;; =============================================================================
;; Keys
;; =============================================================================

(def ^:private random (SecureRandom.))

(defn- new-key []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- key-hash ^bytes [^String key]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes key "UTF-8")))

(defn- cookie-key
  "The session key from a request's Cookie header, or nil."
  [request cookie-name]
  (when-let [header (get-in request [:headers "cookie"])]
    (some (fn [pair]
            (let [[k v] (str/split (str/trim pair) #"=" 2)]
              (when (= k cookie-name) v)))
          (str/split header #";"))))

(defn- cookie [{:keys [cookie-name secure?]} value max-age-seconds]
  (str cookie-name "=" value "; Path=/; Max-Age=" max-age-seconds "; HttpOnly; SameSite=Lax"
       (when secure? "; Secure")))

;; =============================================================================
;; Reading the session
;; =============================================================================

(defn- data-in [{:keys [flags data]}]
  (assoc (or data {}) :session/flags (sort (map name flags))))

(defn- session-of
  "The request's live session: {:person-id … :flags #{…} :data {…}}, or nil."
  [{:keys [db settings]} request]
  (when-let [key (cookie-key request (:cookie-name settings))]
    (when-let [row (pg/row db session-by-key {:session/key-hash (key-hash key)})]
      (let [data (:session/data row)]
        {:key       key
         :person-id (:session/person-id row)
         :flags     (set (map keyword (:session/flags data)))
         :data      (dissoc data :session/flags)}))))

(defn person
  "The id of the person signed in on this request, or nil."
  [request]
  (get-in request [::session :person-id]))

(defn flags
  "The session's flags, like #{:impersonated}."
  [request]
  (get-in request [::session :flags] #{}))

(defn data
  "What the login step kept in the session (keys shaped :entity/field)."
  [request]
  (get-in request [::session :data] {}))

;; =============================================================================
;; Signing in and out
;; =============================================================================

(defn sign-in
  "The response, signing the person in: a new session row and its cookie.
   A session the request already had is ended first, so a key someone else
   planted before the login is worthless after it.

     (sign-in request response person-id)
     (sign-in request response person-id {:flags #{:impersonated} :data {…}})"
  ([request response person-id] (sign-in request response person-id {}))
  ([request response person-id opts]
   (let [{:keys [db settings] :as sessions} (::sessions request)
         key                                (new-key)]
     (when-let [old (cookie-key request (:cookie-name settings))]
       (pg/execute! db delete-by-key {:session/key-hash (key-hash old)}))
     (pg/execute! db delete-expired)
     (pg/execute! db insert-session {:session/key-hash  (key-hash key)
                                     :session/person-id person-id
                                     :session/data      (data-in opts)
                                     :max-age-days      (:max-age-days settings)})
     (assoc-in response [:headers "Set-Cookie"]
               (cookie (:settings sessions) key (* 86400 (:max-age-days settings)))))))

(defn sign-out
  "The response, ending this request's session and clearing its cookie."
  [request response]
  (let [{:keys [db settings]} (::sessions request)]
    (when-let [key (cookie-key request (:cookie-name settings))]
      (pg/execute! db delete-by-key {:session/key-hash (key-hash key)}))
    (assoc-in response [:headers "Set-Cookie"] (cookie settings "" 0))))

(defn sign-out-everywhere!
  "End every session of a person: a removed member, a stolen laptop.
   sessions is the running part, or the request. Returns how many ended."
  [sessions person-id]
  (let [{:keys [db]} (or (::sessions sessions) sessions)]
    (pg/execute! db delete-for-person {:session/person-id person-id})))

;; =============================================================================
;; The middleware
;; =============================================================================

(def ^:private safe-methods #{:get :head :options})

(defn wrap-sessions
  "Put the session (if any) and the sessions part on every request, and
   refuse a changing request that doesn't come from the app's own origin."
  [handler {:keys [settings] :as sessions}]
  (fn [request]
    (if (and (not (safe-methods (:request-method request)))
             (not= (:origin settings) (get-in request [:headers "origin"])))
      {:status  403
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    "Refused: the request didn't come from this app's pages"}
      (let [request (assoc request ::sessions sessions)]
        (handler (if-let [session (session-of sessions request)]
                   (assoc request ::session session)
                   request))))))

(defn start
  "The sessions part. Forgets sessions that ended."
  [{:keys [settings db]}]
  (check-settings settings)
  (pg/execute! db delete-expired)
  {:db db :settings settings})
