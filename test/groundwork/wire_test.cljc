(ns groundwork.wire-test
  "The wire format, the same on both sides: values keep their types; an id
   is a UUID on the server and text in the browser."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork.parquery.wire :as wire]))

(def shape
  [:map [:job/id :uuid] [:job/due :time/local-date] [:job/at :time/instant]
   [:job/area :decimal] [:job/stage :keyword] [:job/cents :int]])

(def id #uuid "7f3c1e2a-0000-4000-8000-000000000001")

(deftest values-travel-as-text-and-come-back-typed
  (let [value  {:job/id id
                :job/due   (wire/parse-date "2026-11-15")
                :job/at    (wire/parse-instant "2026-10-12T10:00:00Z")
                :job/area  (wire/decimal "12.30")
                :job/stage :curing
                :job/cents 4200}
        text   (wire/write-json (wire/encode shape value))
        parsed (wire/read-json text)]
    (testing "on the wire: plain JSON, keys with their namespace"
      (is (= {:job/id "7f3c1e2a-0000-4000-8000-000000000001" :job/due "2026-11-15"
              :job/at "2026-10-12T10:00:00Z" :job/area #?(:clj "12.30" :cljs "12.3") :job/stage "curing" :job/cents 4200}
             parsed)))
    (testing "back by the shape: the same values"
      (let [{back :value} (wire/decode shape parsed)]
        (is (= #?(:clj id :cljs (str id)) (:job/id back))
            "an id: a UUID on the server, its text in the browser")
        (is (= "2026-11-15" (str (:job/due back))) "a day stays that day")
        (is (= #?(:clj "12.30" :cljs "12.3") (wire/decimal-text (:job/area back)))
            "exact; the browser doesn't keep trailing zeros")
        (is (= :curing (:job/stage back)))
        (is (= 4200 (:job/cents back)))))
    (testing "what doesn't fit says why"
      (is (contains? (:errors (wire/decode shape (assoc parsed :job/id "nope"))) :job/id)))))

#?(:cljs
   (deftest an-id-is-text-in-the-browser
     (let [text "7f3c1e2a-0000-4000-8000-000000000001"]
       (testing "text that is an id fits, anything else doesn't"
         (is (wire/fits? :uuid text))
         (is (not (wire/fits? :uuid "nope")))
         (is (not (wire/fits? :uuid id)) "a UUID value isn't what the browser keeps"))
       (testing "a UUID value given goes out, and is read, as its text"
         (is (= {:shop/id text} (wire/encode [:map [:shop/id :uuid]] {:shop/id id})))
         (is (= {:value [text]} (wire/decode [:vector :uuid] [id])))))))
