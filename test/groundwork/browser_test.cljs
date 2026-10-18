(ns groundwork.browser-test
  (:require
   [cljs.test :refer [deftest is]]
   [groundwork.browser :as gb]
   [groundwork.browser.routing :as routing]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(deftest the-page-shell-says-who-is-here
  (is (= {:person    "6f1c2c40-0000-4000-8000-000000000001"
          :flags     #{:impersonated}
          :language  "de"
          :languages {:supported ["en" "de"] :default "en"}
          :styles    {"main" "/css/main.css"}}
         (gb/read-initial (str "{\"person\":\"6f1c2c40-0000-4000-8000-000000000001\",\"flags\":[\"impersonated\"],"
                               "\"language\":\"de\",\"languages\":{\"supported\":[\"en\",\"de\"],\"default\":\"en\"},"
                               "\"styles\":{\"main\":\"/css/main.css\"}}"))))
  (is (= {:person nil :flags #{} :language "en" :languages nil :styles nil}
         (gb/read-initial "{\"person\":null,\"flags\":[],\"language\":\"en\"}")))
  (is (= {:person nil :flags #{} :language nil :languages nil :styles nil} (gb/read-initial nil))))

(deftest the-session-and-the-route-are-subscriptions
  (reset! rf-db/app-db {})
  (rf/dispatch-sync [::gb/-init {:person nil :flags #{:impersonated} :language "en"}])
  (rf/dispatch-sync [::routing/-navigated {:name :home :data {:name :home} :path-params {} :query {:tab "a"}}])
  (is (nil? @(rf/subscribe [::gb/person])))
  (is (= #{:impersonated} @(rf/subscribe [::gb/flags])))
  (is (= "en" @(rf/subscribe [::gb/language])) "the visit's language, while the route names none")
  (is (= :home (get-in @(rf/subscribe [::gb/route]) [:data :name])))
  (is (= {:tab "a"} @(rf/subscribe [::gb/query])))
  (rf/dispatch-sync [::routing/-navigated {:name :home :data {} :language "de"}])
  (is (= "de" @(rf/subscribe [::gb/language])) "the address's language"))
