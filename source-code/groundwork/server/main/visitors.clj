(ns groundwork.server.main.visitors
  "The route that gives a visitor (someone not signed in) a session of
   their own: doorman's start-visitor-session!, on a Ring route, since a
   message's answer sets no cookie. A POST at the :visitors settings'
   :path, outside the messages' route:

     POST /groundwork/visitor
     Origin: https://shop.example        (the :sessions settings' :allowed-origin)
     200 {\"visitor-id\":\"…\"}  Set-Cookie: app_session=…; HttpOnly; …

   The browser calls it (groundwork.browser.main's start-visitor-session!) the
   first time the app keeps something for a visitor, never on a page load:
   a crawler that loads every page makes no session. A request that has a
   live session already makes none and sets no cookie; its visitor id (nil
   for a person's) comes back. A request from another site (its Origin
   not the app's) is refused, 403, and makes nothing. Never cached.

   One asker, told apart by their address (mailroom's find-client-address,
   the :server settings' :client-address-header behind a proxy), may POST
   it the :visitors settings' :rate-limit times, {:max 10 :per-ms 60000};
   more are answered 429 and make nothing (mailroom's
   make-rate-limited-ring-handler, its buckets dropped above the :server
   settings' :rate-limit-buckets-before-cleanup)."
  (:require
   [doorman.api :as doorman]
   [mailroom.api :as mailroom]
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
  "The visitor route, at the :visitors settings' :path, as reitit data,
   behind its rate limit."
  [wire pool {{:keys [path rate-limit]} :visitors sessions :sessions server :server}]
  [[path {:post {:handler (mailroom/make-rate-limited-ring-handler
                           (fn [request]
                             (if (= (:allowed-origin sessions) (get-in request [:headers "origin"]))
                               (answer-visitor-session wire (doorman/start-visitor-session! pool request sessions))
                               not-from-the-app))
                           {:rate-limit             rate-limit
                            :find-rate-limit-key    #(mailroom/find-client-address % (:client-address-header server))
                            :buckets-before-cleanup (:rate-limit-buckets-before-cleanup server)})}}]])
