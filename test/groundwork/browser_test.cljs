(ns groundwork.browser-test
  (:require
   [cljs.test :refer [deftest is]]
   [groundwork.browser :as gb]
   [re-frame.core :as rf]
   [re-frame.db :as rf-db]))

(deftest the-page-shell-says-who-is-here
  (is (= {:person   #uuid "6f1c2c40-0000-4000-8000-000000000001"
          :flags    #{:impersonated}
          :language "de"}
         (gb/read-initial "{\"person\":\"6f1c2c40-0000-4000-8000-000000000001\",\"flags\":[\"impersonated\"],\"language\":\"de\"}")))
  (is (= {:person nil :flags #{} :language "en"}
         (gb/read-initial "{\"person\":null,\"flags\":[],\"language\":\"en\"}")))
  (is (= {:person nil :flags #{} :language nil} (gb/read-initial nil))))

(deftest the-session-and-the-route-are-subscriptions
  (reset! rf-db/app-db {})
  (rf/dispatch-sync [::gb/-init {:person nil :flags #{:impersonated} :language "en"}])
  (rf/dispatch-sync [::gb/-navigated {:data {:name :home} :path-params {}}])
  (is (nil? @(rf/subscribe [::gb/person])))
  (is (= #{:impersonated} @(rf/subscribe [::gb/flags])))
  (is (= "en" @(rf/subscribe [::gb/language])))
  (is (= :home (get-in @(rf/subscribe [::gb/route]) [:data :name]))))
