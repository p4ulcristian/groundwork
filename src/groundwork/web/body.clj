(ns groundwork.web.body
  "Request bodies: none larger than :max-body-bytes, or a route's own; a
   larger one is refused with 413. A browser route's form body is parsed
   into :form-params, a multipart one into :multipart-params, its files
   kept as temporary files (deleted after an hour) with the type their
   bytes really are under :real-type (groundwork.web.files/sniff-type).
   Other bodies, and every server-to-server route's body, stay untouched."
  (:require
   [clojure.walk :as walk]
   [groundwork.web :as-alias web]
   [groundwork.web.files :as files]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.multipart-params.temp-file :refer [temp-file-store]]
   [ring.middleware.params :refer [wrap-params]])
  (:import
   (java.io File InputStream)))

(defn- with-real-types [params]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (instance? File (:tempfile x)))
                     (assoc x :real-type (files/sniff-type (files/file-head (:tempfile x))))
                     x))
                 params))

(defn- too-large [_request]
  {:status 413 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Too large"})

(defn- body-size
  "How many bytes the body has: Content-Length, or what the server read."
  [{:keys [headers body]}]
  (or (some-> (get headers "content-length") parse-long)
      (when (instance? InputStream body) (.available ^InputStream body))
      0))

(defn body-middleware
  "Every route: no body over its limit. Browser routes: forms and multipart
   parsed, files typed by their bytes."
  [default-limit]
  {:name    ::web/body
   :compile (fn [{:keys [max-body-bytes server-to-server]} {::web/keys [path]}]
              (when-not (or (nil? max-body-bytes) (pos-int? max-body-bytes))
                (throw (ex-info (str "Route " path ": :max-body-bytes must be a positive integer")
                                {:type ::web/bad-route :path path :max-body-bytes max-body-bytes})))
              (let [limit (or max-body-bytes default-limit)]
                (fn [handler]
                  (let [parsed (if server-to-server
                                 handler
                                 (-> (fn [request]
                                       (handler (cond-> request
                                                  (:multipart-params request)
                                                  (update :multipart-params with-real-types))))
                                     (wrap-multipart-params {:store         (temp-file-store {:expires-in 3600})
                                                             :max-file-size limit})
                                     wrap-params))]
                    (fn [request]
                      (if (> (body-size request) limit)
                        (too-large request)
                        (parsed request)))))))})

(defn largest-body
  "The largest body any route accepts: the server reads no more than this."
  [routes default-limit]
  (let [limits (atom [default-limit])]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (pos-int? (:max-body-bytes x)))
                       (swap! limits conj (:max-body-bytes x)))
                     x)
                   routes)
    (apply max @limits)))
