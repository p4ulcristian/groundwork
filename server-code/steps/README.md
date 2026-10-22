# groundwork.steps

Work done as a list of steps over one map, the context. A step is
`{:name a-keyword :enter (fn [context] context)}`; the steps run in order
until one adds an answer.

Needs only Clojure. API: `groundwork.steps.api` (`run-steps-until-answered`,
`add-answer-to-context`, `does-context-have-answer?`). Decisions:
[`decisions/`](decisions/README.md).

```clojure
(require '[groundwork.steps.api :as steps])

(def add-ask-to-context
  {:name  :add-ask-to-context
   :enter (fn [context] (assoc context :ask {:shop/id 7}))})

(def add-answer
  {:name  :add-answer
   :enter (fn [context] (steps/add-answer-to-context context {:shop (:ask context)}))})

(steps/run-steps-until-answered [add-ask-to-context add-answer] {})
;=> {:ask #:shop{:id 7}, :groundwork.steps/answer {:shop #:shop{:id 7}}}
```

## Tests

```sh
clojure -X:test     # in this folder: steps alone, nothing else on the classpath
```
