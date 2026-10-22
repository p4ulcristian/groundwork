(ns groundwork.server.wire.api-test
  "The wire format, the same on both sides: values keep their types; an id
   is a UUID on the server and text in the browser."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.server.wire.api :as wire]
   [groundwork.server.wire.time :as time]
   [groundwork.server.wire.types :as types]
   )
  )

(def shape
  [:map [:job/id :uuid] [:job/due :time/local-date] [:job/at :time/instant]
   [:job/area :decimal] [:job/stage :keyword] [:job/cents :int]])

(def id #uuid "7f3c1e2a-0000-4000-8000-000000000001")

(deftest shaped-data-travels-as-text-and-comes-back
  
  (let [shaped-data {:job/id    id
                     :job/due   (time/parse-date "2026-11-15")
                     :job/at    (time/parse-instant "2026-10-12T10:00:00Z")
                     :job/area  (types/decimal "12.30")
                     :job/stage :curing
                     :job/cents 4200}
        text        (wire/turn-data-into-json-text (wire/turn-shaped-data-into-json-data shape shaped-data))
        parsed      (wire/turn-json-text-into-data text)]
    (testing "on the wire: plain JSON, keys with their namespace"
      (is (= {:job/id "7f3c1e2a-0000-4000-8000-000000000001" :job/due "2026-11-15"
              :job/at "2026-10-12T10:00:00Z" :job/area "12.30" :job/stage "curing" :job/cents 4200}
             parsed)))
    (testing "back by the shape: the same values"
      (let [{back :shaped-data} (wire/turn-json-data-into-shaped-data-or-errors shape parsed)]
        (is (= id (:job/id back))
            "an id: a UUID on the server, its text in the browser")
        (is (= "2026-11-15" (str (:job/due back))) "a day stays that day")
        (is (= "2026-10-12T10:00:00Z" (str (:job/at back))) "a moment prints as the text it came as")
        (is (= (:job/at shaped-data) (:job/at back)) "and equals the one sent")
        (is (= "12.30" (types/decimal-text (:job/area back)))
            "exact; the browser doesn't keep trailing zeros")
        (is (= :curing (:job/stage back)))
        (is (= 4200 (:job/cents back)))))
    (testing "what doesn't fit says why"
      (is (contains? (:errors (wire/turn-json-data-into-shaped-data-or-errors shape (assoc parsed :job/id "nope"))) :job/id)))))








