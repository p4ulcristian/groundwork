(ns house.server.main.visitors
  "The route that gives a visitor (someone not signed in) a session of
   their own: doorman's start-visitor-session!, on a Ring route, since a
   message's answer sets no cookie. A POST at the :visitors settings'
   :path, outside the messages' route:

     POST /house/visitor
     Origin: https://shop.example        (the :sessions settings' :allowed-origin)
     200 {\"visitor-id\":\"…\"}  Set-Cookie: app_session=…; HttpOnly; …

   The browser calls it (house.browser.main's start-visitor-session!) the
   first time the app keeps something for a visitor, never on a page load:
   a crawler that loads every page makes no session. A request that has a
   live session already makes none and sets no cookie; its visitor id (nil
   for a person's) comes back. A request from another site (its Origin
   not the app's) is refused, 403, and makes nothing. Never cached."
  (:require
   [doorman.api :as doorman]
   [wire.api :as wire]))

(def ^:private not-from-the-app
  {:status 403 :headers {"content-type" "text/plain" "cache-control" "no-store"}
   :body   "A visitor's session is started only from the app's own pages."})

(defn- answer-visitor-session [wire {:keys [visitor-id set-cookie]}]
  {:status  200
   :headers (cond-> {"content-type" "application/json" "cache-control" "no-store"}
              set-cookie (assoc "set-cookie" set-cookie))
   :body    (wire/turn-data-into-json-text wire {:visitor-id (some-> visitor-id str)})})

(defn make-visitor-routes
  "The visitor route, at the :visitors settings' :path, as reitit data."
  [wire pool {{:keys [path]} :visitors sessions :sessions}]
  [[path {:post {:handler (fn [request]
                            (if (= (:allowed-origin sessions) (get-in request [:headers "origin"]))
                              (answer-visitor-session wire (doorman/start-visitor-session! pool request sessions))
                              not-from-the-app))}}]])
