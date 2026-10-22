(ns ground.steps
  "Work done as a vector of steps over one map.

   A request, a job or a migrate is a context map that travels through a
   vector of steps. Each step is a map:

     {:name  :ground.access/check   ; a namespaced keyword, once per vector
      :enter (fn [ctx] ctx)          ; on the way in, in order
      :leave (fn [ctx] ctx)          ; on the way out, in reverse
      :error (fn [ctx] ctx)}         ; on the way out after something threw

   Every function is optional but one. A function takes the context and
   returns it, changed or not; it never calls the next step.

   Three things a step can put in the context, through this namespace:

   - an answer (`answer`): no further step enters; the steps that entered
     leave, in reverse.
   - an effect (`effect`): work as data (run this SQL, send this email)
     with what to do with its result. The step doesn't do it; a driver
     does, and gives the result back. So the same steps run where work is
     waited for (the JVM) and where it is awaited (the browser, Node).
   - an error: anything a step, an effect or its handler throws is caught
     and kept under ::error; the steps that entered get their :error
     function, in reverse. One that removes ::error has handled it: the rest
     leave as usual.

   The core is pure: `start`, `advance`, `resume` and `fail` turn one state
   value into the next and never do work themselves. `run` (waits) and
   `run-async` (awaits, ClojureScript) are the drivers."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; What a step puts in the context
;; =============================================================================

(defn answer
  "The context with an answer: no further step enters."
  [ctx value]
  (assoc ctx ::answer value))

(defn answered?
  "Whether the context has an answer."
  [ctx]
  (contains? ctx ::answer))

(defn effect
  "The context asking for work: `fx` is a map whose :effect names the kind
   of work (a namespaced keyword, like :ground.sql/rows) and holds what the
   work needs. When it is done, (then ctx result) gives the context back."
  [ctx fx then]
  (when-not (qualified-keyword? (:effect fx))
    (throw (ex-info "An effect's :effect is a namespaced keyword" {:type ::bad-effect :effect fx})))
  (assoc ctx ::effect (assoc fx ::then then)))

;; =============================================================================
;; The pure core: one state value to the next
;; =============================================================================

(defn start
  "The state before the first step: the context, the steps to enter, none
   entered yet."
  [steps ctx]
  {:ctx ctx :queue (vec steps) :stack [] :phase :enter :current nil :pending nil :done? false})

(defn- caught [e]
  {:exception e})

(defn- failed
  "The state after something threw: the error in the context, its step
   dropped, the entered steps to get :error."
  [{:keys [current phase] :as state} e & [fx]]
  (-> state
      (assoc-in [:ctx ::error] (cond-> {:step (:name current) :phase phase :exception e}
                                 fx (assoc :effect (:effect fx))))
      (assoc :current nil :pending nil :phase :error)))

(defn- finished
  "The state once the current step's function (and its effects) are done."
  [{:keys [current phase ctx] :as state}]
  (cond
    (= phase :enter)                          (-> state (update :stack conj current) (assoc :current nil))
    (and (= phase :error) (not (::error ctx))) (assoc state :current nil :phase :leave)
    :else                                     (assoc state :current nil)))

(defn- settled
  "The state after a step's function (or an effect's then) gave back `ctx`:
   waiting for an effect, or the step done."
  [state ctx]
  (if-let [fx (::effect ctx)]
    (assoc state :ctx (dissoc ctx ::effect) :pending fx)
    (finished (assoc state :ctx ctx))))

(defn- call
  "Run `f` on the context for the current step."
  [state f]
  (let [result (try {:ctx (f (:ctx state))}
                    (catch #?(:clj Throwable :cljs :default) e (caught e)))]
    (if (contains? result :ctx)
      (settled state (:ctx result))
      (failed state (:exception result)))))

(defn- enter-next [{:keys [queue ctx] :as state}]
  (if (or (empty? queue) (answered? ctx))
    (assoc state :phase :leave)
    (let [step  (first queue)
          state (assoc state :queue (subvec queue 1) :current step)]
      (if-let [f (:enter step)]
        (call state f)
        (finished state)))))

(defn- unwind-next
  "One entered step, from the top: its :leave, or its :error when there is
   one."
  [{:keys [stack phase] :as state}]
  (if (empty? stack)
    (assoc state :done? true)
    (let [step  (peek stack)
          state (assoc state :stack (pop stack) :current step)]
      (if-let [f (get step phase)]
        (call state f)
        (finished state)))))

(defn advance
  "The next state. A state waiting for an effect (:pending) or done
   (:done?) stays as it is: `resume` or `fail` moves a waiting one on."
  [{:keys [done? pending phase] :as state}]
  (cond
    (or done? pending) state
    (= phase :enter)   (enter-next state)
    :else              (unwind-next state)))

(defn run-until-effect
  "Advance until the state waits for an effect or is done."
  [state]
  (loop [state state]
    (if (or (:done? state) (:pending state))
      state
      (recur (advance state)))))

(defn resume
  "The state after the waiting effect's work gave `result`."
  [{:keys [pending] :as state} result]
  (let [then  (::then pending)
        state (assoc state :pending nil)]
    (call state #(then % result))))

(defn fail
  "The state after the waiting effect's work threw `e`."
  [{:keys [pending] :as state} e]
  (failed state e pending))

;; =============================================================================
;; Drivers: they do the work effects ask for
;; =============================================================================

(defn- handler-for [handlers fx]
  (or (get handlers (:effect fx))
      (fn [_] (throw (ex-info (str "No handler for the effect " (:effect fx))
                              {:type ::no-handler :effect (:effect fx)})))))

(defn- work [fx]
  (dissoc fx ::then))

(defn run
  "Run the steps over `ctx` and return the last context. `handlers` maps an
   effect's kind to a function of the effect that does the work and returns
   its result: (fn [{:keys [sql params]}] rows). An error no step handled
   stays in the context under ::error."
  ([steps ctx] (run {} steps ctx))
  ([handlers steps ctx]
   (loop [state (run-until-effect (start steps ctx))]
     (if (:done? state)
       (:ctx state)
       (let [fx     (:pending state)
             result (try {:value ((handler-for handlers fx) (work fx))}
                         (catch #?(:clj Throwable :cljs :default) e (caught e)))]
         (recur (run-until-effect (if (contains? result :value)
                                    (resume state (:value result))
                                    (fail state (:exception result))))))))))

#?(:cljs
   (defn run-async
     "`run` for work that is awaited: a handler may return a promise. Returns
      a promise of the last context."
     ([steps ctx] (run-async {} steps ctx))
     ([handlers steps ctx]
      (letfn [(go [state]
                (let [state (run-until-effect state)]
                  (if (:done? state)
                    (js/Promise.resolve (:ctx state))
                    (let [fx (:pending state)]
                      (-> (js/Promise.resolve nil)
                          (.then (fn [_] ((handler-for handlers fx) (work fx))))
                          (.then (fn [result] (go (resume state result)))
                                 (fn [e] (go (fail state e)))))))))]
        (go (start steps ctx))))))

(defn error
  "The error no step handled, or nil: {:step :phase :exception (:effect)}."
  [ctx]
  (::error ctx))

;; =============================================================================
;; The vector: plain data, so plain functions change it
;; =============================================================================

(defn names
  "The steps' names, in order."
  [steps]
  (mapv :name steps))

(defn check
  "The steps, or a throw naming what's wrong: each a map with a namespaced
   :name and at least one of :enter :leave :error, each a function; no name
   twice."
  [steps]
  (let [problems (concat
                  (for [[i step] (map-indexed vector steps)
                        :let [fns (select-keys step [:enter :leave :error])]
                        problem [(when-not (map? step) "is not a map")
                                 (when-not (qualified-keyword? (:name step)) "needs a namespaced :name")
                                 (when (empty? fns) "has none of :enter :leave :error")
                                 (when-not (every? fn? (vals fns)) "has an :enter, :leave or :error that isn't a function")]
                        :when problem]
                    (str "step " i " (" (pr-str (:name step)) ") " problem))
                  (for [[n c] (frequencies (names steps)) :when (and n (< 1 c))]
                    (str (pr-str n) " is in the steps " c " times")))]
    (when (seq problems)
      (throw (ex-info (str "The steps are wrong: " (str/join "; " problems))
                      {:type ::bad-steps :problems (vec problems)})))
    steps))

(defn check-required
  "The steps, or a throw naming each required step they lack."
  [steps required]
  (let [missing (remove (set (names steps)) required)]
    (when (seq missing)
      (throw (ex-info (str "The steps lack " (str/join ", " (map pr-str missing)))
                      {:type ::missing-steps :missing (vec missing) :steps (names steps)})))
    steps))

(defn- index-of [steps step-name]
  (or (first (keep-indexed (fn [i s] (when (= step-name (:name s)) i)) steps))
      (throw (ex-info (str "No step " (pr-str step-name)) {:type ::no-step :name step-name :steps (names steps)}))))

(defn insert-after
  "The steps with `step` right after the one named `step-name`."
  [steps step-name step]
  (let [i (inc (index-of steps step-name))]
    (into (conj (subvec (vec steps) 0 i) step) (subvec (vec steps) i))))

(defn insert-before
  "The steps with `step` right before the one named `step-name`."
  [steps step-name step]
  (let [i (index-of steps step-name)]
    (into (conj (subvec (vec steps) 0 i) step) (subvec (vec steps) i))))

(defn replace-step
  "The steps with the one named `step-name` replaced by `step`."
  [steps step-name step]
  (assoc (vec steps) (index-of steps step-name) step))

(defn without
  "The steps without the one named `step-name`."
  [steps step-name]
  (let [i (index-of steps step-name)]
    (into (subvec (vec steps) 0 i) (subvec (vec steps) (inc i)))))
