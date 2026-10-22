(ns groundwork.browser.wire.api-test
  "The wire format, the same on both sides: values keep their types; an id
   is a UUID on the server and text in the browser."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.browser.wire.api :as wire]
   [groundwork.browser.wire.time :as time]
   [groundwork.browser.wire.types :as types]
   [groundwork.browser.wire.big :as big])
  (:import (goog.date Date UtcDateTime)))

(def shape
  [:map [:job/id :uuid] [:job/due :time/local-date] [:job/at :time/instant]
   [:job/area :decimal] [:job/stage :keyword] [:job/cents :int]])

(def id #uuid "7f3c1e2a-0000-4000-8000-000000000001")

(deftest shaped-data-travels-as-text-and-comes-back
  (types/use-exact-decimals! big/big)
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
              :job/at "2026-10-12T10:00:00Z" :job/area "12.3" :job/stage "curing" :job/cents 4200}
             parsed)))
    (testing "back by the shape: the same values"
      (let [{back :shaped-data} (wire/turn-json-data-into-shaped-data-or-errors shape parsed)]
        (is (= (str id) (:job/id back))
            "an id: a UUID on the server, its text in the browser")
        (is (= "2026-11-15" (str (:job/due back))) "a day stays that day")
        (is (= "2026-10-12T10:00:00Z" (str (:job/at back))) "a moment prints as the text it came as")
        (is (= (:job/at shaped-data) (:job/at back)) "and equals the one sent")
        (is (= "12.3" (types/decimal-text (:job/area back)))
            "exact; the browser doesn't keep trailing zeros")
        (is (= :curing (:job/stage back)))
        (is (= 4200 (:job/cents back)))))
    (testing "what doesn't fit says why"
      (is (contains? (:errors (wire/turn-json-data-into-shaped-data-or-errors shape (assoc parsed :job/id "nope"))) :job/id)))))

(deftest an-id-is-text-in-the-browser
     (let [text "7f3c1e2a-0000-4000-8000-000000000001"]
       (testing "text that is an id fits, anything else doesn't"
         (is (wire/does-data-fit-shape? :uuid text))
         (is (not (wire/does-data-fit-shape? :uuid "nope")))
         (is (not (wire/does-data-fit-shape? :uuid id)) "a UUID value isn't what the browser keeps"))
       (testing "a UUID value given goes out, and is read, as its text"
         (is (= {:shop/id text} (wire/turn-shaped-data-into-json-data [:map [:shop/id :uuid]] {:shop/id id})))
         (is (= {:shaped-data [text]} (wire/turn-json-data-into-shaped-data-or-errors [:vector :uuid] [id]))))))

(deftest a-moment-is-an-instant-built-on-goog-date
     (testing "the decoder: ISO text becomes an Instant, a goog.date.UtcDateTime"
       (let [{at :shaped-data} (wire/turn-json-data-into-shaped-data-or-errors :time/instant "2026-10-12T10:00:00Z")]
         (is (instance? UtcDateTime at))
         (is (= (js/Date.parse "2026-10-12T10:00:00Z") (.getTime at)))
         (is (= 10 (.getUTCHours at)))))
     (testing "it prints, and goes out, as java.time prints it"
       (doseq [text ["2026-10-12T10:00:00Z" "2026-10-12T10:00:00.120Z"
                     "2026-10-12T10:00:00.123456Z" "2026-10-12T10:00:00.123456789Z"
                     "1969-12-31T23:59:59.999Z"]]
         (let [at (time/parse-instant text)]
           (is (= text (str at)))
           (is (= text (wire/turn-shaped-data-into-json-data :time/instant at)))
           (is (= (str "{\"at\":\"" text "\"}") (wire/turn-data-into-json-text {:at at})) "JSON text too"))))
     (testing "the microseconds JavaScript's date drops are kept"
       (let [at (time/parse-instant "2026-10-12T10:00:00.123456Z")]
         (is (= (js/Date.parse "2026-10-12T10:00:00.123Z") (.getTime at)))
         (is (not= at (time/parse-instant "2026-10-12T10:00:00.123Z")))))
     (testing "an offset is read as the moment it names"
       (is (= "2026-10-12T10:00:00Z" (str (time/parse-instant "2026-10-12T12:00:00+02:00"))))
       (is (= "2026-10-12T10:30:00Z" (str (time/parse-instant "2026-10-12T09:00:00-01:30")))))
     (testing "= and compare, and a clone stays an Instant"
       (let [a (time/parse-instant "2026-10-12T10:00:00Z")
             b (time/parse-instant "2026-10-12T10:00:00.000001Z")]
         (is (= a (time/parse-instant "2026-10-12T10:00:00Z")))
         (is (= (hash a) (hash (time/parse-instant "2026-10-12T10:00:00Z"))))
         (is (= [a b] (sort [b a])))
         (is (= a (.clone a)))))
     (testing "a js/Date given as a param goes out as the same text"
       (is (= "2026-10-12T10:00:00Z" (wire/turn-shaped-data-into-json-data :time/instant (js/Date. "2026-10-12T10:00:00Z")))))
     (testing "text that isn't a moment stays text and fails the shape"
       (doseq [text ["2026-10-12" "2026-02-30T10:00:00Z" "2026-10-12T24:00:00Z" "2026-10-12T10:00:00" "nope"]]
         (is (nil? (time/read-instant text)) text)
         (is (contains? (wire/turn-json-data-into-shaped-data-or-errors [:map [:at :time/instant]] {:at text}) :errors) text))
       (is (thrown? js/Error (time/parse-instant "nope")))))

(deftest a-day-is-a-day-built-on-goog-date
     (testing "the decoder: ISO text becomes a Day, a goog.date.Date"
       (let [{due :shaped-data} (wire/turn-json-data-into-shaped-data-or-errors :time/local-date "2026-11-15")]
         (is (instance? Date due))
         (is (= [2026 10 15] [(.getFullYear due) (.getMonth due) (.getDate due)])
             "its year, month and day are the wire's, wherever the browser is")
         (is (= "2026-11-15" (str due) (wire/turn-shaped-data-into-json-data :time/local-date due) (.toJSON due)))))
     (testing "= and compare, and a clone stays a Day"
       (let [a (time/parse-date "2026-11-15") b (time/parse-date "2026-12-01")]
         (is (= a (time/parse-date "2026-11-15")))
         (is (= [a b] (sort [b a])))
         (is (= "2026-11-15" (str (.clone a))))
         (is (= a (.clone a)))))
     (testing "text that isn't a day stays text and fails the shape"
       (doseq [text ["2026-02-30" "2026-13-01" "2026-11-15T00:00:00Z" "15/11/2026"]]
         (is (nil? (time/read-day text)) text)
         (is (contains? (wire/turn-json-data-into-shaped-data-or-errors :time/local-date text) :errors) text))
       (is (time/read-day "2028-02-29") "a leap day")))

(deftest decimals-are-the-apps-choice
     (try
       (testing "off: a decimal is its text, exactly as it came"
         (types/use-exact-decimals! nil)
         (is (= {:shaped-data {:area "12.30"}} (wire/turn-json-data-into-shaped-data-or-errors [:map [:area :decimal]] {:area "12.30"})))
         (is (= {:area "12.30"} (wire/turn-shaped-data-into-json-data [:map [:area :decimal]] {:area "12.30"})))
         (is (= "12.30" (types/decimal "12.30") (types/decimal-text "12.30")))
         (is (contains? (wire/turn-json-data-into-shaped-data-or-errors :decimal "twelve") :errors)))
       (testing "on: big.js, exact"
         (types/use-exact-decimals! big/big)
         (let [{^js area :shaped-data} (wire/turn-json-data-into-shaped-data-or-errors :decimal "0.10")]
           (is ((:decimal? big/big) area))
           (is (= "0.3" (types/decimal-text (.plus area (types/decimal "0.2")))))
           (is (= "0.1" (wire/turn-shaped-data-into-json-data :decimal area)))))
       (finally (types/use-exact-decimals! big/big))))
