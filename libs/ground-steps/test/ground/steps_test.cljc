(ns ground.steps-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ground.steps :as steps]))

(defn- logged
  "A step that writes :name> on the way in and :name< on the way out."
  [n & {:as more}]
  (merge {:name  (keyword "test" (name n))
          :enter (fn [ctx] (update ctx :log conj (keyword (str (name n) ">"))))
          :leave (fn [ctx] (update ctx :log conj (keyword (str (name n) "<"))))}
         more))

(def start-ctx {:log []})

(deftest in-order-out-in-reverse
  (is (= [:a> :b> :c> :c< :b< :a<]
         (:log (steps/run [(logged :a) (logged :b) (logged :c)] start-ctx)))))

(deftest an-answer-stops-entering
  (let [b   (logged :b :enter (fn [ctx] (-> ctx (update :log conj :b>) (steps/answer :done))))
        ctx (steps/run [(logged :a) b (logged :c)] start-ctx)]
    (testing "c never enters; a and b leave"
      (is (= [:a> :b> :b< :a<] (:log ctx))))
    (is (= :done (::steps/answer ctx)))))

(defn- adding
  "A step asking for x + y as work, keeping the result under :sum."
  [x y]
  {:name  :test/add
   :enter (fn [ctx] (steps/effect ctx {:effect :test/add :x x :y y}
                                  (fn [ctx sum] (assoc ctx :sum sum))))})

(def handlers {:test/add (fn [{:keys [x y]}] (+ x y))})

(deftest an-effect-is-done-by-the-driver
  (is (= 3 (:sum (steps/run handlers [(adding 1 2)] {})))))

(deftest the-core-never-does-the-work
  (let [state (steps/run-until-effect (steps/start [(adding 1 2)] {}))]
    (testing "the state waits, holding the work as data"
      (is (= {:effect :test/add :x 1 :y 2} (dissoc (:pending state) ::steps/then))))
    (testing "the result is given back by hand"
      (is (= 40 (:sum (:ctx (steps/run-until-effect (steps/resume state 40)))))))))

(deftest an-effect-can-ask-for-more
  (let [twice {:name  :test/twice
               :enter (fn [ctx]
                        (steps/effect ctx {:effect :test/add :x 1 :y 1}
                                      (fn [ctx a]
                                        (steps/effect ctx {:effect :test/add :x a :y 10}
                                                      (fn [ctx b] (assoc ctx :sum b))))))}]
    (is (= 12 (:sum (steps/run handlers [twice] {}))))))

(deftest a-step-after-an-effect-sees-its-result
  (let [after {:name :test/after :enter (fn [ctx] (assoc ctx :seen (:sum ctx)))}]
    (is (= 3 (:seen (steps/run handlers [(adding 1 2) after] {}))))))

(deftest a-throw-unwinds-through-error
  (let [boom (logged :b :enter (fn [_] (throw (ex-info "boom" {}))))
        a    (logged :a :error (fn [ctx] (update ctx :log conj :a!)))
        ctx  (steps/run [a boom (logged :c)] start-ctx)]
    (testing "c never enters; a gets :error, not :leave"
      (is (= [:a> :a!] (:log ctx))))
    (testing "unhandled, the error stays in the context"
      (is (= {:step :test/b :phase :enter} (select-keys (steps/error ctx) [:step :phase])))
      (is (= "boom" (ex-message (:exception (steps/error ctx))))))))

(deftest a-handled-error-leaves-as-usual
  (let [a    (logged :a)
        b    (logged :b :error (fn [ctx] (-> ctx
                                             (dissoc ::steps/error)
                                             (update :log conj :b!)
                                             (steps/answer :sorry))))
        boom (logged :c :enter (fn [_] (throw (ex-info "boom" {}))))
        ctx  (steps/run [a b boom] start-ctx)]
    (testing "b handles it; a below it leaves normally"
      (is (= [:a> :b> :b! :a<] (:log ctx))))
    (is (nil? (steps/error ctx)))
    (is (= :sorry (::steps/answer ctx)))))

(deftest a-failing-effect-is-an-error-of-its-step
  (let [ctx (steps/run {:test/add (fn [_] (throw (ex-info "database down" {})))}
                       [(adding 1 2)] {})]
    (is (= {:step :test/add :phase :enter :effect :test/add}
           (select-keys (steps/error ctx) [:step :phase :effect])))))

(deftest an-effect-without-a-handler-is-an-error
  (let [ctx (steps/run {} [(adding 1 2)] {})]
    (is (= ::steps/no-handler (:type (ex-data (:exception (steps/error ctx))))))))

(deftest a-throw-on-the-way-out-reaches-the-rest
  (let [a   (logged :a :error (fn [ctx] (update ctx :log conj :a!)))
        b   (logged :b :leave (fn [_] (throw (ex-info "late" {}))))
        ctx (steps/run [a b] start-ctx)]
    (is (= [:a> :b> :a!] (:log ctx)))
    (is (= {:step :test/b :phase :leave} (select-keys (steps/error ctx) [:step :phase])))))

(deftest the-vector-is-plain-data
  (let [v [(logged :a) (logged :c)]]
    (is (= [:test/a :test/b :test/c] (steps/names (steps/insert-after v :test/a (logged :b)))))
    (is (= [:test/b :test/a :test/c] (steps/names (steps/insert-before v :test/a (logged :b)))))
    (is (= [:test/c] (steps/names (steps/without v :test/a))))
    (is (= [:test/x :test/c] (steps/names (steps/replace-step v :test/a (logged :x)))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"No step :test/zzz"
                          (steps/insert-after v :test/zzz (logged :b))))))

(deftest check-names-what-is-wrong
  (is (= 2 (count (steps/check [(logged :a) (logged :b)]))))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #":test/a is in the steps 2 times"
                        (steps/check [(logged :a) (logged :a)])))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"needs a namespaced :name"
                        (steps/check [{:name :plain :enter identity}])))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"has none of :enter :leave :error"
                        (steps/check [{:name :test/empty}]))))

(deftest a-required-step-must-be-there
  (let [v [(logged :a) (logged :b)]]
    (is (= v (steps/check-required v [:test/a])))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"The steps lack :test/access"
                          (steps/check-required v [:test/a :test/access])))))
