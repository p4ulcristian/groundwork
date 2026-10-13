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
   out again, at most once an hour. Once it holds something worth keeping
   (uploaded designs), put-data with {:keep-days 30} makes it live that long
   from each visit. At sign-in, a visitor's data moves into the signed-in
   session, under a new key.

   A sign-in lives :max-age-days, or its own {:hours …}: an impersonated
   session an hour, say.

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
   [groundwork.permissions :as permissions]
   [groundwork.postgres :as pg]
   [groundwork.settings :as settings])
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
(pg/defsql keep-visitor "groundwork/sessions/keep_visitor.sql")
(pg/defsql live-of "groundwork/sessions/live_of.sql")
(pg/defsql of-person-sql "groundwork/sessions/of_person.sql")

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
          hours (or (:session/keep-hours row) (:visitor-hours settings))]
      (when (and (nil? (:session/person-id row))
                 (.isBefore ^OffsetDateTime (:session/expires-at row)
                            (.plusHours (OffsetDateTime/now) (dec hours))))
        (pg/execute! db extend-visitor {:session/key-hash (key-hash key) :hours hours}))
      {:key       key
       :id        (:session/id row)
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

(defn visitor
  "The id of this request's visitor session, for someone not signed in who
   has one; else nil. It is Groundwork's own id for the session, never a
   value the browser sent: a record that names it belongs to this visitor
   (see :visitors in the permission description)."
  [request]
  (let [session (::session request)]
    (when (and session (nil? (:person-id session)))
      (:id session))))

(defn flags
  "The session's flags, like #{:impersonated}."
  [request]
  (get-in request [::session :flags] #{}))

(defn data
  "What the session keeps (keys shaped :entity/field): the login step's, and
   what put-data added."
  [request]
  (get-in request [::session :data] {}))

(defn session-hash
  "The request's session as a name that isn't its key: the key's SHA-256, in
   hex; nil without a session. An ended session rings with it (see
   groundwork.parquery.websocket)."
  [request]
  (when-let [key (get-in request [::session :key])]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (key-hash key)))))

(defn live
  "Which of these session hashes are still live: not signed out, not
   expired."
  [{:keys [db]} hashes]
  (if (empty? hashes)
    #{}
    (into #{} (map :session/hash) (pg/rows db live-of {:session/hashes (vec hashes)}))))

(defn of-person
  "A person's live sessions, for the REPL: the first characters of each
   session's hash, when it began and ends, its flags."
  [{:keys [db]} person-id]
  (pg/rows db of-person-sql {:session/person-id person-id}))

(defn visitor-session!
  "A visitor session for a request that has none, made now: {:id … :cookie
   \"…\"}, its id and the Set-Cookie value to send. For a write that keeps
   something for someone not signed in (an endpoint's :keeps-for-visitor)."
  [request]
  (let [{:keys [db settings]} (::sessions request)
        key                   (new-key)
        max-age               (* 86400 (:max-age-days settings))
        row                   (pg/row db insert-session {:session/key-hash  (key-hash key)
                                                         :session/person-id nil
                                                         :session/data      (data-in {})
                                                         :hours             (:visitor-hours settings)})]
    {:id     (:session/id row)
     :cookie (if (::embedded? request)
               (embed-cookie settings key max-age)
               (cookie settings key max-age))}))

(defn- check-data [m]
  (when-not (and (map? m) (every? qualified-keyword? (keys m)))
    (throw (ex-info "Session data is a map with keys shaped :entity/field"
                    {:type ::bad-data :data m}))))

(defn put-data
  "The response, keeping m in the session, merged into what it has; a nil
   value forgets its key. A visitor without a session gets one now, with its
   cookie. Within one request, the response of the last call is the one to
   return.

     (put-data request response {:cart/items items})
     (put-data request response {:design/ids ids} {:keep-days 30})   ; a visitor's, kept longer"
  ([request response m] (put-data request response m {}))
  ([request response m {:keys [keep-days]}]
   (check-data m)
   (when-not (or (nil? keep-days) (pos-int? keep-days))
     (throw (ex-info ":keep-days must be a positive integer" {:type ::bad-data :keep-days keep-days})))
   (let [{:keys [db settings]} (::sessions request)
         session               (::session request)
         merged                (into {} (remove (comp nil? val)) (merge (:data session) m))
         stored                (assoc merged :session/flags (sort (map name (:flags session))))
         key                   (or (:key session) (new-key))
         embed?                (if session (= :embed (:cookie session)) (::embedded? request))
         max-age               (* 86400 (max (:max-age-days settings) (or keep-days 0)))
         set-cookie            #(add-cookie % (if embed?
                                                (embed-cookie settings key max-age)
                                                (cookie settings key max-age)))]
     (if session
       (pg/execute! db update-data {:session/key-hash (key-hash key) :session/data stored})
       (pg/execute! db insert-session {:session/key-hash  (key-hash key)
                                       :session/person-id nil
                                       :session/data      stored
                                       :hours             (:visitor-hours settings)}))
     (when (and keep-days (nil? (:person-id session)))
       (pg/execute! db keep-visitor {:session/key-hash (key-hash key) :hours (* 24 keep-days)}))
     (cond-> response
       (or (nil? session) (and keep-days (nil? (:person-id session)))) set-cookie))))

;; =============================================================================
;; Signing in and out
;; =============================================================================

(defn sign-in
  "The response, signing the person in: a new session row and its cookie.
   A session the request already had is ended first, so a key someone else
   planted before the login is worthless after it. A visitor's data comes
   along into the new session, and the records that were the visitor's
   become the person's (every table with a visitor column in the permission
   description: the person is set, the visitor emptied); a signed-in
   session's data doesn't come along. A page in
   another site's frame can't sign in: the session it would make lives in
   the app's own site's cookie.

     (sign-in request response person-id)
     (sign-in request response person-id {:flags #{:impersonated} :data {…} :hours 1})"
  ([request response person-id] (sign-in request response person-id {}))
  ([request response person-id opts]
   (when (::embedded? request)
     (throw (ex-info "Signing in happens on the app's own site, not in another site's frame"
                     {:type ::embedded-sign-in})))
   (when-not (or (nil? (:hours opts)) (pos-int? (:hours opts)))
     (throw (ex-info ":hours must be a positive integer" {:type ::bad-data :hours (:hours opts)})))
   (let [{:keys [db settings] :as sessions} (::sessions request)
         key                                (new-key)
         hours                              (or (:hours opts) (* 24 (:max-age-days settings)))
         visitor-data                       (let [s (::session request)]
                                              (when (and s (nil? (:person-id s))) (:data s)))]
     ;; the visitor's records first: once their session is gone, nobody's
     (when-let [perms (:permissions sessions)]
       (permissions/move-visitor! perms (visitor request) person-id))
     (doseq [old [(cookie-key request (:cookie-name settings))
                  (some-> (::session request) (#(when (= :embed (:cookie %)) (:key %))))]
             :when old]
       (pg/execute! db delete-by-key {:session/key-hash (key-hash old)}))
     (pg/execute! db delete-expired)
     (pg/execute! db insert-session {:session/key-hash  (key-hash key)
                                     :session/person-id person-id
                                     :session/data      (data-in (update (dissoc opts :hours) :data #(merge visitor-data %)))
                                     :hours             hours})
     (add-cookie response (cookie (:settings sessions) key (* 3600 hours))))))

(defn on-request
  "The request with the sessions part on it, for a server-to-server route
   that signs someone in itself (an app's own test login, proven by a secret):
   (sign-in (on-request request (gw/sessions (gw/current))) response person-id). Such a
   route reads no session and checks no Origin, so what it is sent has to
   prove itself."
  [request sessions]
  (assoc request ::sessions sessions))

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
  "The sessions part. Forgets sessions that ended. With the permissions
   part, sign-in moves a visitor's records to the person."
  [{:keys [settings db permissions]}]
  (settings/check-part! :sessions settings)
  (pg/execute! db delete-expired)
  {:db db :settings settings :permissions permissions})
