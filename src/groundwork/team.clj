(ns groundwork.team
  "A scope's people and roles, as endpoints for the app's own screens
   (Groundwork ships no UI). The app adds them to its list:

     :parquery {:endpoints [groundwork.team/endpoints app.jobs/endpoints …] …}

     :groundwork.team/members          read   who is in the scope: role, owner?, extra actions
     :groundwork.team/roles            read   the scope's roles and their actions (the roles grid)
     :groundwork.team/invite           write  invite an email with a role → {:invitation/token …}
     :groundwork.team/accept           write  the signed-in person joins with an invitation's token
     :groundwork.team/change-role      write  give a member another role
     :groundwork.team/set-role-actions write  set a role's actions (ticks in the grid)
     :groundwork.team/create-role      write  a new role with its actions
     :groundwork.team/remove-member    write  take a person out of the scope
     :groundwork.team/grant-extra      write  one action for a person, on top of their role
     :groundwork.team/revoke-extra     write  take it back

   Every one but accept needs :groundwork/team in the scope named by
   :team/scope-id, and every action handed out or taken back must be one the
   person holds there: a manager can't give more than they have, and only an
   owner makes or removes owners (see groundwork.permissions). Another
   scope's id answers not-found, like everywhere.

   Sending the invitation is the app's: invite answers the token for the
   link; the app mails it. Accepting needs the person's email, which the
   app's login keeps in the session as :person/email (see
   groundwork.sessions/put-data); the invitation was for that email.

   These run outside the database wall: Groundwork's own team functions open
   their own transactions as the system, after the same checks."
  (:require
   [clojure.string :as str]
   [groundwork.permissions :as permissions]
   [groundwork.postgres :as pg]))

(pg/defsql members-sql "groundwork/team/members.sql")
(pg/defsql roles-sql "groundwork/team/roles.sql")

(def ^:private scope-param [:team/scope-id :uuid])
(def ^:private action :qualified-keyword)

(defn- team [action-input]
  {:permission {:action :groundwork/team :scope [:groundwork/any :team/scope-id]}
   :input      action-input})

(defn- by [{:keys [person]} params]
  {:by person :scope (:team/scope-id params)})

(defn- ok [] {:team/done true})

(defn- in-system [{:keys [perms]} f]
  (permissions/as-system perms f))

(def endpoints
  "The team endpoints, for the app's :endpoints list."
  (update-vals
   {:groundwork.team/members
    (merge (team [:map scope-param])
           {:read   (fn [ctx params]
                      (in-system ctx (fn [tx]
                                       (mapv #(update % :member/extra-actions (partial mapv keyword))
                                             (pg/rows tx members-sql params)))))
            :answer [:vector [:map [:member/person-id :uuid] [:member/role :string] [:member/owner :boolean]
                              [:member/extra-actions [:vector :keyword]]]]})

    :groundwork.team/roles
    (merge (team [:map scope-param])
           {:read   (fn [ctx params]
                      (in-system ctx (fn [tx]
                                       (mapv #(update % :role/actions (partial mapv keyword))
                                             (pg/rows tx roles-sql params)))))
            :answer [:vector [:map [:role/name :string] [:role/owner :boolean] [:role/actions [:vector :keyword]]]]})

    :groundwork.team/invite
    (merge (team [:map scope-param [:invitation/email :string] [:invitation/role :string]
                  [:invitation/days [:int {:min 1 :max 90}]]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      {:invitation/token (permissions/invite! perms (assoc (by ctx params)
                                                                         :email (str/lower-case (:invitation/email params))
                                                                         :role  (:invitation/role params)
                                                                         :days  (:invitation/days params)))})
            :answer [:map [:invitation/token :string]]})

    :groundwork.team/accept
    {:permission :signed-in
     :input      [:map [:invitation/token :string]]
     :write      (fn [{:keys [perms person session-data]} params]
                   (let [email (:person/email session-data)]
                     (when-not (string? email)
                       (throw (ex-info "Accepting an invitation needs the person's email in the session (:person/email)"
                                       {:type :groundwork.endpoints/refused})))
                     {:team/scope-id (permissions/accept-invitation! perms {:token  (:invitation/token params)
                                                                            :person person
                                                                            :email  email})}))
     :answer     [:map [:team/scope-id :uuid]]}

    :groundwork.team/change-role
    (merge (team [:map scope-param [:member/person-id {:groundwork/record false} :uuid] [:member/role :string]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/change-role! perms (assoc (by ctx params) :person (:member/person-id params)
                                                             :role (:member/role params)))
                      (ok))
            :answer [:map [:team/done :boolean]]})

    :groundwork.team/set-role-actions
    (merge (team [:map scope-param [:role/name :string] [:role/actions [:vector action]]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/set-role-actions! perms (assoc (by ctx params) :role (:role/name params)
                                                                  :actions (set (:role/actions params))))
                      (ok))
            :answer [:map [:team/done :boolean]]})

    :groundwork.team/create-role
    (merge (team [:map scope-param [:role/name :string] [:role/actions [:vector action]]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/create-role! perms (assoc (by ctx params) :role (:role/name params)
                                                             :actions (set (:role/actions params))))
                      (ok))
            :answer [:map [:team/done :boolean]]})

    :groundwork.team/remove-member
    (merge (team [:map scope-param [:member/person-id {:groundwork/record false} :uuid]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/remove-member! perms (assoc (by ctx params) :person (:member/person-id params)))
                      (ok))
            :answer [:map [:team/done :boolean]]})

    :groundwork.team/grant-extra
    (merge (team [:map scope-param [:member/person-id {:groundwork/record false} :uuid] [:extra-action/action action]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/grant-extra! perms (assoc (by ctx params) :person (:member/person-id params)
                                                             :action (:extra-action/action params)))
                      (ok))
            :answer [:map [:team/done :boolean]]})

    :groundwork.team/revoke-extra
    (merge (team [:map scope-param [:member/person-id {:groundwork/record false} :uuid] [:extra-action/action action]])
           {:write  (fn [{:keys [perms] :as ctx} params]
                      (permissions/revoke-extra! perms (assoc (by ctx params) :person (:member/person-id params)
                                                              :action (:extra-action/action params)))
                      (ok))
            :answer [:map [:team/done :boolean]]})}
   ;; Groundwork's own team functions open their own transactions after their
   ;; own checks, so these run outside the database wall
   #(vary-meta % assoc :groundwork.endpoints/own-transaction true)))
