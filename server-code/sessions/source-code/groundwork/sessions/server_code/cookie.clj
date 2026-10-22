(ns groundwork.sessions.server-code.cookie
  "The session cookie: 32 random bytes the browser keeps, and their
   fingerprint (SHA-256) the table keeps."
  (:require
   [clojure.string :as str])
  (:import
   (java.security MessageDigest SecureRandom)
   (java.util Base64)))

(def cookie-name "groundwork_session")

(def ^:private random (SecureRandom.))

(defn make-cookie-value
  "32 random bytes as URL-safe text."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn turn-cookie-value-into-fingerprint
  "The SHA-256 of the cookie's text, as hex."
  [cookie-value]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String cookie-value "UTF-8"))
       (map #(format "%02x" %))
       (apply str)))

(defn find-cookie-value
  "The session cookie's value in a Ring request, or nil."
  [request]
  (some->> (get-in request [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" cookie-name "=([^;]+)")))
           second))

(defn make-set-cookie-text
  "The Set-Cookie header's text: the cookie for max-age seconds (0 deletes
   it). HttpOnly (no JavaScript reads it), SameSite=Lax (other sites' forms
   don't send it), Secure unless secure? is false (local development)."
  [cookie-value max-age-seconds secure?]
  (str/join "; " (cond-> [(str cookie-name "=" cookie-value) "Path=/" "HttpOnly" "SameSite=Lax"
                          (str "Max-Age=" max-age-seconds)]
                   secure? (conj "Secure"))))
