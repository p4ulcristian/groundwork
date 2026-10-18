(ns example.access-language.app
  "An example app for the access language: shops with a catalogue the
   public reads, universal packages the platform keeps for every shop,
   orders that stay the shop's own, handoffs that show one order to another
   shop, agreements between two shops, conversations between fixed sides,
   designs made before signing in, and requests that name several shops.

   Its tables are made by `tables` (a test's fixture runs it; an app's own
   would be migrations), its permission description is `description`, its
   endpoints `endpoints`."
  (:require
   [clojure.string :as str]
   [groundwork.endpoints :as endpoints]
   [groundwork.postgres :as pg]))

(def platform
  "The platform's one scope: its back office, which keeps the universal
   packages and takes part in conversations."
  #uuid "00000000-0000-4000-8000-000000000001")

(def tables
  "DROP TABLE IF EXISTS al_packages, al_price_sheets, al_agreements, al_order_services, al_handoffs, al_jobs,
                        al_orders, al_messages, al_conversations, al_designs;
   -- made before signing in: a design is its visitor's, then its person's; its shop may wait
   CREATE TABLE al_designs (design__id uuid PRIMARY KEY DEFAULT gen_random_uuid(), design__shop_id uuid,
                            design__customer_id uuid, design__visitor_id uuid, design__name text NOT NULL);
   -- a shop's packages, and universal ones that belong to nobody (no shop)
   CREATE TABLE al_packages (package__id uuid PRIMARY KEY, package__shop_id uuid,
                             package__name text NOT NULL, package__published boolean NOT NULL);
   CREATE TABLE al_orders (order__id uuid PRIMARY KEY, order__shop_id uuid NOT NULL,
                           order__customer_id uuid, order__note text NOT NULL,
                           UNIQUE (order__id, order__shop_id));
   CREATE TABLE al_jobs (job__id uuid PRIMARY KEY, job__order_id uuid NOT NULL, job__shop_id uuid NOT NULL,
                         job__name text NOT NULL,
                         FOREIGN KEY (job__order_id, job__shop_id)
                           REFERENCES al_orders (order__id, order__shop_id) ON UPDATE CASCADE);
   CREATE INDEX al_jobs_order ON al_jobs (job__order_id);
   -- a handoff: while it is open, the receiving shop (or the driver) sees the one order it names.
   -- Its giver is tied to the order's own shop, so nobody stores one for an order that isn't theirs
   CREATE TABLE al_handoffs (handoff__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                             handoff__order_id uuid NOT NULL,
                             handoff__from_shop_id uuid NOT NULL, handoff__to_shop_id uuid,
                             handoff__driver_id uuid, handoff__status text NOT NULL DEFAULT 'open',
                             FOREIGN KEY (handoff__order_id, handoff__from_shop_id)
                               REFERENCES al_orders (order__id, order__shop_id));
   CREATE INDEX al_handoffs_order ON al_handoffs (handoff__order_id);
   CREATE INDEX al_handoffs_to ON al_handoffs (handoff__to_shop_id);
   CREATE INDEX al_handoffs_driver ON al_handoffs (handoff__driver_id);
   -- between two scopes: a shop and the partner it works with
   CREATE TABLE al_agreements (agreement__id uuid PRIMARY KEY, agreement__shop_id uuid NOT NULL,
                               agreement__partner_id uuid NOT NULL,
                               UNIQUE (agreement__id, agreement__shop_id, agreement__partner_id));
   -- under it: both scopes are the agreement's, tied by the matching link
   CREATE TABLE al_price_sheets (price_sheet__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                 price_sheet__agreement_id uuid NOT NULL,
                                 price_sheet__shop_id uuid NOT NULL, price_sheet__partner_id uuid NOT NULL,
                                 price_sheet__cents bigint NOT NULL,
                                 FOREIGN KEY (price_sheet__agreement_id, price_sheet__shop_id, price_sheet__partner_id)
                                   REFERENCES al_agreements (agreement__id, agreement__shop_id, agreement__partner_id)
                                   ON UPDATE CASCADE);
   -- under an order: the order's shop, and a partner of its own, which may wait empty
   CREATE TABLE al_order_services (order_service__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                   order_service__order_id uuid NOT NULL,
                                   order_service__shop_id uuid NOT NULL, order_service__partner_id uuid,
                                   order_service__name text NOT NULL,
                                   FOREIGN KEY (order_service__order_id, order_service__shop_id)
                                     REFERENCES al_orders (order__id, order__shop_id) ON UPDATE CASCADE);
   -- between fixed sides, which all read and write: a shop, a partner, the platform's back office, a customer
   CREATE TABLE al_conversations (conversation__id uuid PRIMARY KEY, conversation__shop_id uuid NOT NULL,
                                  conversation__partner_id uuid NOT NULL, conversation__staff_id uuid NOT NULL,
                                  conversation__customer_id uuid,
                                  UNIQUE (conversation__id, conversation__shop_id, conversation__partner_id,
                                          conversation__staff_id),
                                  UNIQUE (conversation__id, conversation__customer_id));
   -- a message carries its conversation's sides: the scopes in one key, the person in a key of its own
   CREATE TABLE al_messages (message__id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                             message__conversation_id uuid NOT NULL,
                             message__shop_id uuid NOT NULL, message__partner_id uuid NOT NULL,
                             message__staff_id uuid NOT NULL, message__customer_id uuid,
                             message__text text NOT NULL,
                             FOREIGN KEY (message__conversation_id, message__shop_id, message__partner_id, message__staff_id)
                               REFERENCES al_conversations (conversation__id, conversation__shop_id,
                                                            conversation__partner_id, conversation__staff_id)
                               ON UPDATE CASCADE,
                             FOREIGN KEY (message__conversation_id, message__customer_id)
                               REFERENCES al_conversations (conversation__id, conversation__customer_id)
                               ON UPDATE CASCADE);")

(def drop-tables
  "DROP TABLE IF EXISTS al_packages, al_price_sheets, al_agreements, al_order_services, al_handoffs, al_jobs,
                        al_orders, al_messages, al_conversations, al_designs")

(def ^:private open-handoff
  "An open handoff of the row's order: the receiving shop's people who work handoffs there."
  {:table :al_handoffs :match {:order/id :handoff/order-id}
   :to [:member :handoff/to-shop-id :handoffs/work] :when [:= :handoff/status "open"]})

(def description
  {:scopes  {:shop     {:actions {:catalogue/see  #{:read}
                                  :catalogue/edit #{:read :update}
                                  :shop/card      #{:read}
                                  :orders/see     #{:read}
                                  :orders/edit    #{:read :insert :update}
                                  :prices/see     #{:read}
                                  :prices/edit    #{:read :insert}
                                  :services/see   #{:read}
                                  :talk/read      #{:read}
                                  :talk/write     #{:read :insert}
                                  :handoffs/give  #{:read :insert :update}
                                  :handoffs/work  #{:read :update}
                                  :designs/mine   #{:read :insert :update}}
                        :roles   {"Staff"      #{:catalogue/edit :orders/see :orders/edit :prices/see :prices/edit
                                                 :services/see :talk/read :talk/write :handoffs/give :handoffs/work}
                                  ;; who in a receiving shop may use a handoff is that shop's own roles' matter
                                  "Production" #{:handoffs/work}
                                  "Bookkeeper" #{:prices/see}}
                        :parent  nil}
             ;; one scope, the back office: it keeps the universal packages and talks with everyone
             :platform {:actions {:platform/catalogue #{:read}
                                  :platform/talk      #{:read}}
                        :roles   {}
                        :parent  nil
                        :single? true}}
   :records {:package       {:table :al_packages :id :package/id :whose {:package/shop-id :shop}}
             :order         {:table :al_orders :id :order/id
                             :whose {:order/shop-id :shop :order/customer-id :person}}
             :design        {:table :al_designs :id :design/id
                             :whose {:design/shop-id :shop :design/customer-id :person :design/visitor-id :visitor}}
             :job           {:table :al_jobs :id :job/id :whose {:job/shop-id :shop} :parent [:order :job/order-id]}
             :handoff       {:table :al_handoffs :id :handoff/id
                             :whose {:handoff/from-shop-id :shop :handoff/to-shop-id :shop :handoff/driver-id :person}}
             :agreement     {:table :al_agreements :id :agreement/id
                             :whose {:agreement/shop-id :shop :agreement/partner-id :shop}}
             :price-sheet   {:table :al_price_sheets :id :price-sheet/id
                             :whose {:price-sheet/shop-id :shop :price-sheet/partner-id :shop}
                             :parent [:agreement :price-sheet/agreement-id
                                      {:price-sheet/shop-id    :agreement/shop-id
                                       :price-sheet/partner-id :agreement/partner-id}]}
             :order-service {:table :al_order_services :id :order-service/id
                             :whose {:order-service/shop-id :shop :order-service/partner-id :shop}
                             :parent [:order :order-service/order-id {:order-service/shop-id :order/shop-id}]}
             :conversation  {:table :al_conversations :id :conversation/id
                             :whose {:conversation/shop-id :shop :conversation/partner-id :shop
                                     :conversation/staff-id :platform :conversation/customer-id :person}}
             :message       {:table :al_messages :id :message/id
                             :whose {:message/shop-id :shop :message/partner-id :shop
                                     :message/staff-id :platform :message/customer-id :person}
                             :parent [:conversation :message/conversation-id
                                      {:message/shop-id     :conversation/shop-id
                                       :message/partner-id  :conversation/partner-id
                                       :message/staff-id    :conversation/staff-id
                                       :message/customer-id :conversation/customer-id}]}}
   :rules
   [;; the public reads a shop's published packages, and the universal ones
    {:allow :catalogue/see :on :package :to :anyone :when [:= :package/published true]}
    {:allow :catalogue/see :on :package :to :anyone :when [:and [:none :package/shop-id] [:= :package/published true]]}
    {:allow :shop/card :on :package :to :signed-in :when [:= :package/published true]}
    ;; a shop's staff edit its packages; the back office the universal ones
    {:allow :catalogue/edit :on :package :to [:member :package/shop-id]}
    {:allow :catalogue/edit :on :package :to [:member :platform :platform/catalogue] :when [:none :package/shop-id]}
    ;; orders are the shop's, and the customer's own
    {:allow #{:orders/see :orders/edit :handoffs/give} :on :order :to [:member :order/shop-id]}
    {:allow :orders/see :on :order :to [:me :order/customer-id]}
    ;; an open handoff gives the receiving shop's people who work handoffs the one order it
    ;; names, with its jobs; or a driver, one person, the order alone
    {:allow :orders/see :on :order :via open-handoff}
    {:allow :orders/see :on :order :via (assoc open-handoff :to [:me :handoff/driver-id])}
    {:allow :orders/see :on :job :to [:member :job/shop-id]}
    {:allow :orders/see :on :job :via (assoc open-handoff :match {:job/order-id :handoff/order-id})}
    ;; the giving shop makes a handoff; both sides read it and report on it
    {:allow :handoffs/give :on :handoff :to [:member :handoff/from-shop-id]}
    {:allow :handoffs/work :on :handoff :to [:member :handoff/from-shop-id]}
    {:allow :handoffs/work :on :handoff :to [:member :handoff/to-shop-id]}
    ;; a record of two scopes, and the records under it: either side reads and writes
    {:allow #{:prices/see :prices/edit} :on :agreement :to [:member :agreement/shop-id]}
    {:allow #{:prices/see :prices/edit} :on :agreement :to [:member :agreement/partner-id]}
    {:allow #{:prices/see :prices/edit} :on :price-sheet :to [:member :price-sheet/shop-id]}
    {:allow #{:prices/see :prices/edit} :on :price-sheet :to [:member :price-sheet/partner-id]}
    ;; a service on an order: the order's shop, and the partner it names
    {:allow #{:services/see :orders/edit} :on :order-service :to [:member :order-service/shop-id]}
    {:allow :services/see :on :order-service :to [:member :order-service/partner-id]}
    ;; every side of a conversation reads it and writes into it
    {:allow #{:talk/read :talk/write} :on :conversation :to [:member :conversation/shop-id]}
    {:allow #{:talk/read :talk/write} :on :conversation :to [:member :conversation/partner-id]}
    {:allow #{:talk/read :talk/write} :on :conversation :to [:member :conversation/staff-id :platform/talk]}
    {:allow #{:talk/read :talk/write} :on :conversation :to [:me :conversation/customer-id]}
    {:allow #{:talk/read :talk/write} :on :message :to [:member :message/shop-id]}
    {:allow #{:talk/read :talk/write} :on :message :to [:member :message/partner-id]}
    {:allow #{:talk/read :talk/write} :on :message :to [:member :message/staff-id :platform/talk]}
    {:allow #{:talk/read :talk/write} :on :message :to [:me :message/customer-id]}
    ;; a visitor's designs are theirs, and their person's once they sign in
    {:allow :designs/mine :on :design :to [:me :design/visitor-id]}
    {:allow :designs/mine :on :design :to [:me :design/customer-id]}]})

(pg/defsql packages-of-shop)
(pg/defsql package)
(pg/defsql rename-package)
(pg/defsql orders-of-shop)
(pg/defsql count-orders)

(pg/defsql prices-of-agreement)
(pg/defsql add-price)
(pg/defsql services)
(pg/defsql add-service)
(pg/defsql order-note)
(pg/defsql give-handoff)
(pg/defsql set-handoff-status)
(pg/defsql order-notes)
(pg/defsql jobs-of-order)
(pg/defsql job)
(pg/defsql stamp-order)
(pg/defsql save-design)
(pg/defsql designs)
(pg/defsql design)
(pg/defsql rename-design)
(pg/defsql messages)
(pg/defsql say)
(pg/defsql orders-of-shops)
(pg/defsql place-order)

(def package-shape [:map [:package/id :uuid] [:package/name :string]])

(def endpoints
  {;; open to anyone: a shop's published packages
   :catalogue/of-shop {:read       (fn [{:keys [db]} params] (pg/rows db packages-of-shop params))
                       :access     {:action :catalogue/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     [:vector package-shape]}
   :catalogue/package {:read       (fn [{:keys [db]} params] (pg/row db package params))
                       :access     {:action :catalogue/see :record [:package :package/id]}
                       :input      [:map [:package/id :uuid]]
                       :answer     [:maybe package-shape]}
   ;; the same open action, with SQL that reaches for the shop's orders: the
   ;; wall shows a stranger none
   :catalogue/busy?   {:read       (fn [{:keys [db]} params] (:orders/count (pg/row db count-orders params)))
                       :access     {:action :catalogue/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     :int}
   ;; open to anyone signed in
   :shop/card         {:read       (fn [{:keys [db]} params] (count (pg/rows db packages-of-shop params)))
                       :access     {:action :shop/card :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     :int}
   ;; members only
   :catalogue/rename  {:write      (fn [{:keys [db]} params] (pg/row db rename-package params))
                       :access     {:action :catalogue/edit :record [:package :package/id]}
                       :input      [:map [:package/id :uuid] [:package/name :string]]
                       :answer     [:maybe package-shape]}
   ;; a record of two scopes and what hangs under it: either side reads and writes
   :prices/of-agreement {:read       (fn [{:keys [db]} params] (mapv :price-sheet/cents (pg/rows db prices-of-agreement params)))
                         :access     {:action :prices/see :record [:agreement :agreement/id]}
                         :input      [:map [:agreement/id :uuid]]
                         :answer     [:vector :int]}
   ;; the SQL names no scope: Groundwork fills both from the agreement
   :prices/add          {:write      (fn [{:keys [db]} params] (:price-sheet/id (pg/row db add-price params)))
                         :access     {:action :prices/edit :record [:agreement :agreement/id]}
                         :input      [:map [:agreement/id :uuid] [:price-sheet/cents :int]]
                         :answer     :uuid}
   ;; a service on an order: the order's shop sees it, and the partner it names
   :services/mine       {:read       (fn [{:keys [db]} _] (mapv :order-service/name (pg/rows db services {})))
                         :access     {:action :services/see :list :order-service}
                         :input      [:map]
                         :answer     [:vector :string]}
   :services/add        {:write      (fn [{:keys [db]} params] (:order-service/id (pg/row db add-service params)))
                         :access     {:action :orders/edit :record [:order :order/id]}
                         :input      [:map [:order/id :uuid] [:order-service/name :string]
                                      ;; a scope's id, not a record's
                                      [:order-service/partner-id {:groundwork/record false} [:maybe :uuid]]]
                         :answer     :uuid}
   :orders/note         {:read       (fn [{:keys [db]} params] (:order/note (pg/row db order-note params)))
                         :access     {:action :orders/see :record [:order :order/id]}
                         :input      [:map [:order/id :uuid]]
                         :answer     [:maybe :string]}
   ;; a handoff shows one order to another shop
   :handoffs/give       {:write      (fn [{:keys [db]} params] (:handoff/id (pg/row db give-handoff params)))
                         :access     {:action :handoffs/give :record [:order :order/id]}
                         :input      [:map [:order/id :uuid]
                                      [:handoff/to-shop-id {:groundwork/record false} [:maybe :uuid]]
                                      [:handoff/driver-id {:groundwork/record false} [:maybe :uuid]]]
                         :answer     :uuid}
   ;; either side reports on the handoff itself
   :handoffs/set-status {:write      (fn [{:keys [db]} params] (:handoff/status (pg/row db set-handoff-status params)))
                         :access     {:action :handoffs/work :record [:handoff :handoff/id]}
                         :input      [:map [:handoff/id :uuid] [:handoff/status [:enum "open" "done"]]]
                         :answer     :string}
   :orders/incoming     {:read       (fn [{:keys [db]} _] (mapv :order/note (pg/rows db order-notes {})))
                         :access     {:action :orders/see :list :order}
                         :input      [:map]
                         :answer     [:vector :string]}
   ;; about the order: the jobs under it come with the handoff's sentence on jobs
   :jobs/of-order       {:read       (fn [{:keys [db]} params] (mapv :job/name (pg/rows db jobs-of-order params)))
                         :access     {:action :orders/see :record [:order :order/id]}
                         :input      [:map [:order/id :uuid]]
                         :answer     [:vector :string]}
   :jobs/one            {:read       (fn [{:keys [db]} params] (:job/name (pg/row db job params)))
                         :access     {:action :orders/see :record [:job :job/id]}
                         :input      [:map [:job/id :uuid]]
                         :answer     [:maybe :string]}
   ;; writing an order needs an action that writes: a handoff gives only :orders/see
   :orders/stamp        {:write      (fn [{:keys [db]} params] (:order/note (pg/row db stamp-order params)))
                         :access     {:action :orders/edit :record [:order :order/id]}
                         :input      [:map [:order/id :uuid] [:order/note :string]]
                         :answer     [:maybe :string]}
   ;; a visitor's records. Saving keeps something for someone not signed in, who
   ;; gets a visitor session for it; whose the row is comes from ctx, never the params
   :designs/save        {:write      (fn [{:keys [db person visitor]} params]
                                       (:design/id (pg/row db save-design
                                                           (assoc params :design/customer-id person
                                                                  :design/visitor-id visitor))))
                         :access     :public
                         :keeps-for-visitor? true
                         :input      [:map [:design/name :string]]
                         :answer     :uuid}
   :designs/mine        {:read       (fn [{:keys [db]} _] (mapv :design/name (pg/rows db designs {})))
                         :access     {:action :designs/mine :list :design}
                         :input      [:map]
                         :answer     [:vector :string]}
   :designs/one         {:read       (fn [{:keys [db]} params] (:design/name (pg/row db design params)))
                         :access     {:action :designs/mine :record [:design :design/id]}
                         :input      [:map [:design/id :uuid]]
                         :answer     [:maybe :string]}
   :designs/rename      {:write      (fn [{:keys [db]} params] (:design/name (pg/row db rename-design params)))
                         :access     {:action :designs/mine :record [:design :design/id]}
                         :input      [:map [:design/id :uuid] [:design/name :string]]
                         :answer     [:maybe :string]}
   ;; every side of a conversation reads it and writes into it
   :talk/messages       {:read       (fn [{:keys [db]} params] (mapv :message/text (pg/rows db messages params)))
                         :access     {:action :talk/read :record [:conversation :conversation/id]}
                         :input      [:map [:conversation/id :uuid]]
                         :answer     [:vector :string]}
   :talk/say            {:write      (fn [{:keys [db]} params] (:message/id (pg/row db say params)))
                         :access     {:action :talk/write :record [:conversation :conversation/id]}
                         :input      [:map [:conversation/id :uuid] [:message/text :string]]
                         :answer     :uuid}
   :orders/of-shop    {:read       (fn [{:keys [db]} params] (mapv :order/id (pg/rows db orders-of-shop params)))
                       :access     {:action :orders/see :scope [:shop :shop/id]}
                       :input      [:map [:shop/id :uuid]]
                       :answer     [:vector :uuid]}
   ;; several shops in one request: the action is checked in each, the wall
   ;; opens exactly those
   :orders/of-shops   {:read       (fn [{:keys [db]} _] (mapv :order/id (pg/rows db orders-of-shops {})))
                       :access     {:action :orders/see :scopes [:shop :shop/ids]}
                       :input      [:map [:shop/ids [:vector :uuid]]]
                       :answer     [:vector :uuid]}
   ;; open to anyone, about several shops
   :catalogue/of-shops {:read       (fn [{:keys [db]} _] (pg/rows db packages-of-shop {}))
                        :access     {:action :catalogue/see :scopes [:shop :shop/ids]}
                        :input      [:map [:shop/ids [:vector {:min 1} :uuid]]]
                        :answer     [:vector package-shape]}
   ;; one order in each shop a line names, in one transaction: a line
   ;; without a note refuses the whole request, the orders before it too
   :orders/place-in-shops {:write      (fn [{:keys [db]} {:order/keys [lines]}]
                                         (mapv (fn [{:order/keys [note] :as line}]
                                                 (when (str/blank? note)
                                                   (throw (endpoints/refuse "Every line needs a note")))
                                                 (:order/id (pg/row db place-order line)))
                                               lines))
                           :access     {:action :orders/edit :scopes [:shop [:order/lines :order/shop-id]]}
                           :input      [:map [:order/lines [:vector {:min 1}
                                                            [:map [:order/shop-id :uuid] [:order/note :string]]]]]
                           :answer     [:vector :uuid]}})
