# ground-steps

Work done as a vector of steps over one map. A request, a job or a migrate
is a context map that travels through steps; each step takes the map and
returns it. Steps never call each other and never do outside work
themselves: they ask for it as data, and a driver does it.

No dependencies but Clojure. Runs on the JVM and in ClojureScript.

Door: `ground.steps`. Decisions: [`decisions/`](decisions/).

## A step

```clojure
(require '[ground.steps :as steps])

(def who-is-asking
  {:name  :app/who-is-asking
   :enter (fn [ctx] (assoc ctx :person (get-in ctx [:request :cookie])))})

(steps/run [who-is-asking] {:request {:cookie "anna"}})
;=> {:request {:cookie "anna"} :person "anna"}
```

`:enter` runs on the way in, in order; `:leave` on the way out, in reverse;
`:error` on the way out after something threw.

## An answer stops the way in

```clojure
(def may-they?
  {:name  :app/may-they
   :enter (fn [ctx] (if (= "anna" (:person ctx))
                      ctx
                      (steps/answer ctx {:status :not-found})))})

(steps/run [who-is-asking may-they? run-the-handler] {:request {:cookie "bob"}})
;; run-the-handler never runs; who-is-asking and may-they? leave
```

## Work as data

A step doesn't run SQL or call Stripe. It says what it needs and what to do
with the result:

```clojure
(def load-order
  {:name  :app/load-order
   :enter (fn [ctx]
            (steps/effect ctx
                          {:effect :ground.sql/rows
                           :sql    "SELECT * FROM orders WHERE order__id = ?"
                           :params [(:order-id ctx)]}
                          (fn [ctx rows] (assoc ctx :order (first rows)))))})

;; the driver does the work, with one handler per kind of effect
(steps/run {:ground.sql/rows (fn [{:keys [sql params]}] (jdbc/execute! ds (into [sql] params)))}
           [load-order]
           {:order-id 42})
```

On the JVM `run` waits for each handler. In ClojureScript `run-async` takes
handlers that return promises and returns a promise. The steps are the same.

The core is pure, so the work can be looked at without doing it:

```clojure
(-> (steps/start [load-order] {:order-id 42})
    steps/run-until-effect
    :pending)
;=> {:effect :ground.sql/rows :sql "SELECT …" :params [42] …}
```

## Errors

Anything a step, an effect or a handler throws is caught. The steps that
entered get `:error`, in reverse; one that removes `::steps/error` from the
context has handled it, and the rest leave as usual. Unhandled, it stays:

```clojure
(steps/error ctx)
;=> {:step :app/load-order :phase :enter :effect :ground.sql/rows :exception #error{…}}
```

## The vector is data

```clojure
(steps/names v)                                   ;=> [:app/who-is-asking :app/may-they …]
(steps/insert-after v :app/may-they audit-step)
(steps/insert-before v :app/run-handler timing-step)
(steps/replace-step v :app/may-they stricter-check)
(steps/without v :app/timing)
(steps/check v)                                   ; names, functions, no name twice
(steps/check-required v [:ground.access/check])   ; throws naming what's missing
```

## Tests

```sh
clojure -X:test                                          # JVM
clojure -M:cljs-test compile test && node target/test.js # ClojureScript
```
