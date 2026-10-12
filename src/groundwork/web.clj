(ns groundwork.web
  "The router: the app's routes, as reitit data, next to Groundwork's own;
   files from resources/public; the page shell for every other page a browser
   asks for, so the app's own routing in the browser takes over.

   Every answer carries the standard security headers; in production also
   Strict-Transport-Security. Files are revalidated on every load (a 304 when
   unchanged), so a deploy is never hidden behind a stale cache."
  (:require
   [clojure.string :as str]
   [reitit.ring :as ring]
   [ring.middleware.not-modified :refer [wrap-not-modified]]))

(def ^:private security-headers
  {"X-Content-Type-Options"  "nosniff"
   "X-Frame-Options"         "DENY"
   "Referrer-Policy"         "strict-origin-when-cross-origin"
   ;; what can't break an app's own scripts and styles
   "Content-Security-Policy" "frame-ancestors 'none'; base-uri 'self'; object-src 'none'"})

(defn- wrap-security-headers [handler environment]
  (let [headers (cond-> security-headers
                  (= :production environment)
                  (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains"))]
    (fn [request]
      (when-let [response (handler request)]
        (update response :headers #(merge headers %))))))

(defn- wrap-file-caching [handler]
  (fn [request]
    (when-let [response (handler request)]
      (assoc-in response [:headers "Cache-Control"] "no-cache"))))

(defn- wants-page?
  "A browser asking for a page: GET or HEAD that accepts HTML."
  [{:keys [request-method headers]}]
  (and (#{:get :head} request-method)
       (str/includes? (get headers "accept" "") "text/html")))

(defn- not-found [_request]
  {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not found"})

(defn groundwork-routes
  "Groundwork's own routes, mounted next to the app's."
  []
  [])

(defn handler
  "The app's Ring handler."
  [{:keys [routes page environment]}]
  (when-not (vector? routes)
    (throw (ex-info ":routes must be a vector of reitit routes"
                    {:type ::bad-setting :setting :routes :value routes})))
  (let [files (-> (ring/create-resource-handler {:path "/" :root "public"})
                  wrap-file-caching
                  wrap-not-modified)]
    (-> (ring/ring-handler
         (ring/router (into (groundwork-routes) routes))
         (ring/routes files
                      (fn [request] (when (wants-page? request) (page request)))
                      not-found))
        (wrap-security-headers environment))))

(defn start
  "The router part."
  [{:keys [settings environment page]}]
  (handler {:routes settings :page page :environment environment}))
