(ns groundwork-test.sample.shapes
  "The sample app's message shapes, one file for both sides: the server's
   handlers and the browser's shapes-by-name. Each writes out its three:
   :params-shape, :answer-shape and :refusals, its own reasons ({} for
   none; doorman's and groundwork's are added on both sides).")

(def order-shape
  [:map [:order/id :uuid] [:order/note :string]])

(def shapes-by-name
  {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                    :answer-shape [:vector order-shape]
                    :refusals     {}}
   :orders/add     {:params-shape [:map [:order/shop-id :uuid] [:order/note [:string {:min 1}]]]
                    :answer-shape [:map [:order/id :uuid]]
                    :refusals     {}}})
