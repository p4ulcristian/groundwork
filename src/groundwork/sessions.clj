(ns groundwork.sessions
  "Who is signed in. A session is a row in groundwork.sessions; the browser's
   cookie holds 32 random bytes and the table keeps only their SHA-256, so a
   copy of the table can't sign anyone in.

     :cookie-name    \"acme_session\"
     :max-age-days   30        a signed-in session's lifetime
     :visitor-hours  24        a visitor's session ends after this long unseen
     :secure?        true      false only for plain-http development
     :origin         \"https://app.acme.example\"   where the app is served from

   The app's login step (an identity provider, a gate, a password) ends with
   sign-in; sign-out, sign-out-everywhere! and expiry end sessions.

   A visitor (someone not signed in) gets a session the first time the app
   keeps something for them with put-data, like a cart; a request that keeps
   nothing makes nothing, so crawlers cost no rows. Each visit pushes the end
   out again, at most once an hour. At sign-in, a visitor's data moves into
   the signed-in session, under a new key.

   A request that changes something (anything but GET, HEAD, OPTIONS) must
   come from the app's own pages: its Origin header must be :origin, or it is
   refused. With the cookie's SameSite=Lax, another site can't act as the
   person who is signed in.

   A page shown in another site's frame (an :embeddable route) gets no Lax
   cookie from the browser. A visitor session made there uses a second
   cookie, <cookie-name>_embed (SameSite=None; Secure; Partitioned), which
   the framed page's own requests carry. It only ever holds a visitor: sign-in
   never writes it, and a signed-in session behind it is ignored. Signing in
   and paying open on the app's own site, where the Lax cookie lives."
  (:require
   [clojure.string :as str]
   [groundwork.postgres :as pg])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.time OffsetDateTime)
   (java.util Base64)))

(pg/defsql insert-session "groundwork/sessions/insert.sql")
(pg/defsql session-by-key "groundwork/sessions/by_key.sql")
(pg/defsql delete-by-key "groundwork/sessions/delete_by_key.sql")
(pg/defsql delete-for-person "groundwork/sessions/delete_for_person.sql")
(pg/defsql delete-expired "groundwork/sessions/delete_expired.sql")
(pg/defsql update-data "groundwork/sessions/update_data.sql")
(pg/defsql extend-visitor "groundwork/sessions/extend_visitor.sql")

(def ^:private settings [:cookie-name :max-age-days :visitor-hours :secure? :origin])

(defn- check-settings [opts]
  (when-not (map? opts)
    (throw (ex-info ":sessions must be a map" {:type ::bad-setting :setting :sessions :value opts})))
  (when-let [missing (seq (remove #(contains? opts %) settings))]
    (throw (ex-info (str ":sessions needs " (str/join " " missing))
                    {:type ::missing-settings :missing (vec missing)})))
  (let [{:keys [cookie-name max-age-days visitor-hours secure? origin]} opts]
    (when-not (and (string? cookie-name) (re-matches #"[A-Za-z0-9_\-]+" cookie-name))
      (throw (ex-info ":cookie-name must be letters, digits, _ or -"
                      {:type ::bad-setting :setting :cookie-name :value cookie-name})))
    (when-not (pos-int? max-age-days)
      (throw (ex-info ":max-age-days must be a positive integer"
                      {:type ::bad-setting :setting :max-age-days :value max-age-days})))
    (when-not (pos-int? visitor-hours)
      (throw (ex-info ":visitor-hours must be a positive integer"
                      {:type ::bad-setting :setting :visitor-hours :value visitor-hours})))
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

(defn- embed-cookie-name [cookie-name]
  (str cookie-name "_embed"))

(defn- cookie [{:keys [cookie-name secure?]} value max-age-seconds]
  (str cookie-name "=" value "; Path=/; Max-Age=" max-age-seconds "; HttpOnly; SameSite=Lax"
       (when secure? "; Secure")))

(defn- embed-cookie
  "The cookie a framed page keeps a visitor in. SameSite=None needs Secure;
   browsers accept Secure cookies on localhost too."
  [{:keys [cookie-name]} value max-age-seconds]
  (str (embed-cookie-name cookie-name) "=" value "; Path=/; Max-Age=" max-age-seconds
       "; HttpOnly; Secure; SameSite=None; Partitioned"))

(defn- add-cookie
  "The response with one more Set-Cookie header."
  [response value]
  (update-in response [:headers "Set-Cookie"]
             (fn [existing]
               (cond (nil? existing)    value
                     (string? existing) [existing value]
                     :else              (conj (vec existing) value)))))

;; =============================================================================
;; Reading the session
;; =============================================================================

(defn- data-in [{:keys [flags data]}]
  (assoc (or data {}) :session/flags (sort (map name flags))))

(defn- session-by
  "The live session for a cookie's key, or nil. A visitor's has no
   :person-id; seeing it pushes its end out again, at most once an hour."
  [{:keys [db settings]} key cookie]
  (when-let [row (pg/row db session-by-key {:session/key-hash (key-hash key)})]
    (let [data  (:session/data row)
          hours (:visitor-hours settings)]
      (when (and (nil? (:session/person-id row))
                 (.isBefore ^OffsetDateTime (:session/expires-at row)
                            (.plusHours (OffsetDateTime/now) (dec hours))))
        (pg/execute! db extend-visitor {:session/key-hash (key-hash key) :hours hours}))
      {:key       key
       :cookie    cookie
       :person-id (:session/person-id row)
       :flags     (set (map keyword (:session/flags data)))
       :data      (dissoc data :session/flags)})))

(defn- session-of
  "The request's live session: {:key … :cookie :lax|:embed :person-id …
   :flags #{…} :data {…}}, or nil. The Lax cookie first; the embed cookie
   only for a visitor."
  [{:keys [settings] :as sessions} request]
  (or (some-> (cookie-key request (:cookie-name settings)) (#(session-by sessions % :lax)))
      (some-> (cookie-key request (embed-cookie-name (:cookie-name settings)))
              (#(session-by sessions % :embed))
              (#(when (nil? (:person-id %)) %)))))

(defn person
  "The id of the person signed in on this request, or nil."
  [request]
  (get-in request [::session :person-id]))

(defn flags
  "The session's flags, like #{:impersonated}."
  [request]
  (get-in request [::session :flags] #{}))

(defn data
  "What the session keeps (keys shaped :entity/field): the login step's, and
   what put-data added."
  [request]
  (get-in request [::session :data] {}))

(defn- check-data [m]
  (when-not (and (map? m) (every? qualified-keyword? (keys m)))
    (throw (ex-info "Session data is a map with keys shaped :entity/field"
                    {:type ::bad-data :data m}))))

(defn put-data
  "The response, keeping m in the session, merged into what it has; a nil
   value forgets its key. A visitor without a session gets one now, with its
   cookie. Within one request, the response of the last call is the one to
   return.

     (put-data request response {:cart/items items})"
  [request response m]
  (check-data m)
  (let [{:keys [db settings]} (::sessions request)
        session               (::session request)
        merged                (into {} (remove (comp nil? val)) (merge (:data session) m))
        stored                (assoc merged :session/flags (sort (map name (:flags session))))]
    (if session
      (do (pg/execute! db update-data {:session/key-hash (key-hash (:key session))
                                       :session/data     stored})
          response)
      (let [key     (new-key)
            max-age (* 86400 (:max-age-days settings))]
        (pg/execute! db insert-session {:session/key-hash  (key-hash key)
                                        :session/person-id nil
                                        :session/data      stored
                                        :hours             (:visitor-hours settings)})
        (add-cookie response (if (::embedded? request)
                               (embed-cookie settings key max-age)
                               (cookie settings key max-age)))))))

;; =============================================================================
;; Signing in and out
;; =============================================================================

(defn sign-in
  "The response, signing the person in: a new session row and its cookie.
   A session the request already had is ended first, so a key someone else
   planted before the login is worthless after it. A visitor's data comes
   along into the new session; a signed-in session's doesn't. A page in
   another site's frame can't sign in: the session it would make lives in
   the app's own site's cookie.

     (sign-in request response person-id)
     (sign-in request response person-id {:flags #{:impersonated} :data {…}})"
  ([request response person-id] (sign-in request response person-id {}))
  ([request response person-id opts]
   (when (::embedded? request)
     (throw (ex-info "Signing in happens on the app's own site, not in another site's frame"
                     {:type ::embedded-sign-in})))
   (let [{:keys [db settings] :as sessions} (::sessions request)
         key                                (new-key)
         visitor-data                       (let [s (::session request)]
                                              (when (and s (nil? (:person-id s))) (:data s)))]
     (doseq [old [(cookie-key request (:cookie-name settings))
                  (some-> (::session request) (#(when (= :embed (:cookie %)) (:key %))))]
             :when old]
       (pg/execute! db delete-by-key {:session/key-hash (key-hash old)}))
     (pg/execute! db delete-expired)
     (pg/execute! db insert-session {:session/key-hash  (key-hash key)
                                     :session/person-id person-id
                                     :session/data      (data-in (update opts :data #(merge visitor-data %)))
                                     :hours             (* 24 (:max-age-days settings))})
     (add-cookie response (cookie (:settings sessions) key (* 86400 (:max-age-days settings)))))))

(defn sign-out
  "The response, ending this request's session and clearing its cookie."
  [request response]
  (let [{:keys [db settings]} (::sessions request)]
    (when-let [key (cookie-key request (:cookie-name settings))]
      (pg/execute! db delete-by-key {:session/key-hash (key-hash key)}))
    (if (= :embed (:cookie (::session request)))
      (do (pg/execute! db delete-by-key {:session/key-hash (key-hash (:key (::session request)))})
          (add-cookie response (embed-cookie settings "" 0)))
      (add-cookie response (cookie settings "" 0)))))

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
   refuse a changing request that doesn't come from the app's own origin.
   On an :embeddable route (embedded? true), a new visitor session uses the
   embed cookie."
  [handler {:keys [settings] :as sessions} embedded?]
  (fn [request]
    (if (and (not (safe-methods (:request-method request)))
             (not= (:origin settings) (get-in request [:headers "origin"])))
      {:status  403
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    "Refused: the request didn't come from this app's pages"}
      (let [request (cond-> (assoc request ::sessions sessions)
                      embedded? (assoc ::embedded? true))]
        (handler (if-let [session (session-of sessions request)]
                   (assoc request ::session session)
                   request))))))

(defn start
  "The sessions part. Forgets sessions that ended."
  [{:keys [settings db]}]
  (check-settings settings)
  (pg/execute! db delete-expired)
  {:db db :settings settings})
