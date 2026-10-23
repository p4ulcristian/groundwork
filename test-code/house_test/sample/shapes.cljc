(ns house-test.sample.shapes
  "The sample app's message shapes, one file for both sides: the server's
   handlers and the browser's shapes-by-name.")

(def order-shape
  [:map [:order/id :uuid] [:order/note :string]])

(def shapes-by-name
  {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                    :answer-shape [:vector order-shape]}
   :orders/add     {:params-shape [:map [:order/shop-id :uuid] [:order/note [:string {:min 1}]]]
                    :answer-shape [:map [:order/id :uuid]]}})
