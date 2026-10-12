(ns groundwork.team-test
  "The team endpoints: a scope's people and roles, managed through parquery
   with the same checks as the functions behind them."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [groundwork.endpoints :as endpoints]
   [groundwork.leaks :as leaks]
   [groundwork.migrations :as migrations]
   [groundwork.permissions :as perm]
   [groundwork.postgres :as pg]
   [groundwork.team :as team]
   [groundwork.test-support :refer [db-settings sql!]])
  (:import
   (java.util UUID)))

(defn- id [] (UUID/randomUUID))

(def acme (id)) (def zinc (id))
(def paul (id)) (def peter (id)) (def ana (id)) (def mia (id))

(def description
  {:scopes  {:shop {:actions #{:orders/see :orders/edit :money/move}
                    :roles   {"Manager"    #{:orders/see :orders/edit :groundwork/team}
                              "Production" #{:orders/see}}
                    :parent  nil}}
   :records {}
   :links   []
   :session-blocks {}})

(def ^:dynamic *part* nil)

(use-fixtures :once
  (fn [t]
    (let [db (pg/start db-settings)]
      (try
        (migrations/migrate! db)
        (sql! "TRUNCATE groundwork.scopes, groundwork.roles, groundwork.role_actions, groundwork.memberships,
                        groundwork.extra_actions, groundwork.invitations, groundwork.run_once CASCADE")
        (let [perms (perm/start {:settings description :db db})]
          (perm/create-scope! perms {:kind :shop :id acme :parent nil :owner paul})
          (perm/create-scope! perms {:kind :shop :id zinc :parent nil :owner ana})
          (binding [*part* (endpoints/start {:settings     {:endpoints [team/endpoints] :max-asks 50 :max-running 4
                                                            :bearer nil :bells {:global #{} :personal {}}}
                                             :db db :permissions perms :app-settings {}})]
            (t)))
        (finally (pg/stop db))))))

(defn- call [person endpoint params & {:as more}]
  (endpoints/call *part* (merge {:endpoint endpoint :params params :person person :flags #{} :request-id "r1"} more)))

(defn- invite-and-join [by scope person role]
  (let [{:keys [status data]} (call by :groundwork.team/invite {:team/scope-id scope :invitation/email (str person "@x.example")
                                                                :invitation/role role :invitation/days 7})]
    (is (= :ok status))
    (call person :groundwork.team/accept {:invitation/token (:invitation/token data)}
          :session-data {:person/email (str person "@x.example")})))

(deftest a-team-is-managed-through-endpoints
  (testing "the owner invites a manager and a production worker; they accept"
    (is (= {:status :ok :data {:team/scope-id acme}} (invite-and-join paul acme peter "Manager")))
    (is (= {:status :ok :data {:team/scope-id acme}} (invite-and-join paul acme mia "Production"))))
  (testing "the members and the roles grid"
    (let [{:keys [status data]} (call peter :groundwork.team/members {:team/scope-id acme})]
      (is (= :ok status))
      (is (= #{[paul "Owner" true] [peter "Manager" false] [mia "Production" false]}
             (set (map (juxt :member/person-id :member/role :member/owner) data)))))
    (is (= {:role/name "Manager" :role/owner false :role/actions [:groundwork/team :orders/edit :orders/see]}
           (some #(when (= "Manager" (:role/name %)) %)
                 (:data (call peter :groundwork.team/roles {:team/scope-id acme}))))))
  (testing "a manager gives only what they hold"
    (is (= :ok (:status (call peter :groundwork.team/grant-extra {:team/scope-id acme :member/person-id mia
                                                                  :extra-action/action :orders/edit}))))
    (is (= :refused (:status (call peter :groundwork.team/grant-extra {:team/scope-id acme :member/person-id mia
                                                                       :extra-action/action :money/move}))))
    (is (= :refused (:status (call peter :groundwork.team/set-role-actions
                                   {:team/scope-id acme :role/name "Production" :role/actions [:orders/see :money/move]}))))
    (is (= :ok (:status (call peter :groundwork.team/set-role-actions
                              {:team/scope-id acme :role/name "Production" :role/actions [:orders/see :orders/edit]})))))
  (testing "only an owner makes or removes owners"
    (is (= :refused (:status (call peter :groundwork.team/change-role {:team/scope-id acme :member/person-id mia
                                                                       :member/role "Owner"}))))
    (is (= :refused (:status (call peter :groundwork.team/remove-member {:team/scope-id acme :member/person-id paul})))))
  (testing "someone without the team action in the scope gets not-found"
    (is (= {:status :not-found} (call mia :groundwork.team/members {:team/scope-id acme})))
    (is (= {:status :not-found} (call peter :groundwork.team/members {:team/scope-id zinc})) "another shop"))
  (testing "a new role, a member moved to it, a member removed"
    (is (= :ok (:status (call paul :groundwork.team/create-role {:team/scope-id acme :role/name "Front desk"
                                                                 :role/actions [:orders/see]}))))
    (is (= :ok (:status (call paul :groundwork.team/change-role {:team/scope-id acme :member/person-id mia
                                                                 :member/role "Front desk"}))))
    (is (= :ok (:status (call paul :groundwork.team/revoke-extra {:team/scope-id acme :member/person-id mia
                                                                  :extra-action/action :orders/edit}))))
    (is (= :ok (:status (call paul :groundwork.team/remove-member {:team/scope-id acme :member/person-id mia}))))
    (is (not-any? #(= mia (:member/person-id %)) (:data (call paul :groundwork.team/members {:team/scope-id acme}))))))

(deftest accepting-needs-the-email-and-the-right-person
  (let [token (-> (call ana :groundwork.team/invite {:team/scope-id zinc :invitation/email "new@x.example"
                                                     :invitation/role "Production" :invitation/days 7})
                  :data :invitation/token)]
    (is (= :refused (:status (call mia :groundwork.team/accept {:invitation/token token}))) "no email in the session")
    (is (= {:status :not-found} (call mia :groundwork.team/accept {:invitation/token token}
                                      :session-data {:person/email "other@x.example"})) "someone else's invitation")
    (is (= {:status :signed-out} (call nil :groundwork.team/accept {:invitation/token token})))))

(deftest nothing-leaks
  (let [report (leaks/check *part* {:person paul :foreign {:shop zinc}})]
    (is (empty? (:leaked report)) (pr-str (:leaked report)))
    (is (= 9 (count (:checked report))) (pr-str report))))
