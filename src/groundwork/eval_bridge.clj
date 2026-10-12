(ns groundwork.eval-bridge
  "Evaluating code in a running preview from outside it, given a secret:
   the REPL for previews (see the decision on REPLs by environment). Off
   unless :web's :eval-bridge turns it on, and never in production: a
   setting there stops the start.

     :eval-bridge {:secret     \"at least 16 characters\"
                   :timeout-ms 30000     ; the longest an evaluation may run
                   :max-chars  20000}    ; the most of a value or output sent back
                  ; or nil

     POST /groundwork/eval   with X-Eval-Secret: <secret>, the body Clojure code
     → {\"value\": \"…\" \"out\": \"…\"}, or {\"error\": \"…\" \"out\": \"…\"};
       504 when it ran too long. A wrong or missing secret is a plain 404.

     POST /groundwork/eval?cljs=app   the same, in the browser tabs open on
     the app, through the browser build's REPL (shadow-cljs running in this
     JVM).

   The code runs in the user namespace; (groundwork.core/current) is the
   running app. Every evaluation is logged (who asked, from where, the code's
   start, how it ended), and the :on-request hook sees it like every request."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json])
  (:import
   (java.io StringWriter)
   (java.lang System$Logger System$Logger$Level)
   (java.security MessageDigest)))

(def ^:private ^System$Logger logger (System/getLogger "groundwork.eval-bridge"))

(def path "/groundwork/eval")

(defn- cut [^String s max-chars]
  (when s
    (if (<= (count s) max-chars) s (str (subs s 0 max-chars) "… (cut at " max-chars " characters)"))))

(defn- root-cause
  "What really went wrong: a compiler error unwrapped to its cause."
  [^Throwable e]
  (if (and (instance? clojure.lang.Compiler$CompilerException e) (.getCause e))
    (recur (.getCause e))
    e))

(defn- json-answer [status body]
  {:status status :headers {"Content-Type" "application/json"} :body (json/write-value-as-string body)})

(defn- run-clojure
  "Evaluate code in the user namespace on a virtual thread: {:value :out}
   or {:error :out}, or ::timeout."
  [code timeout-ms]
  (let [out    (StringWriter.)
        task   (fn []
                 (binding [*ns*  (or (find-ns 'user) (create-ns 'user))
                           *out* out
                           *err* out]
                   (refer-clojure)
                   (try {:value (pr-str (load-string code))}
                        (catch Throwable e
                          (let [e (root-cause e)]
                            {:error (str (.getSimpleName (class e)) ": " (ex-message e))})))))
        result (promise)
        thread (Thread/startVirtualThread #(deliver result (task)))
        r      (deref result timeout-ms ::timeout)]
    (when (= ::timeout r) (.interrupt thread))
    (if (= ::timeout r) r (assoc r :out (str out)))))

(defn- run-cljs
  "Evaluate code in the browser tabs of a shadow-cljs build running in this
   JVM: {:value :out} or {:error …}."
  [build code timeout-ms]
  (if-let [cljs-eval (when (some-> (try (requiring-resolve 'shadow.cljs.devtools.server.runtime/get-instance)
                                        (catch Exception _ nil))
                                   (apply []))
                       (requiring-resolve 'shadow.cljs.devtools.api/cljs-eval))]
    (let [f (future (try (cljs-eval (keyword build) code {})
                         (catch Throwable e {:err (ex-message e)})))
          r (deref f timeout-ms ::timeout)]
      (cond
        (= ::timeout r) (do (future-cancel f) r)
        (:err r)        {:error (str (:err r)) :out (:out r)}
        :else           {:value (str/join "\n" (:results r)) :out (:out r)}))
    {:error "The browser build isn't running in this server (no shadow-cljs started here)"}))

(defn route
  "The bridge's route, server-to-server."
  [{:keys [secret timeout-ms max-chars]}]
  (let [secret-bytes (.getBytes ^String secret "UTF-8")]
    [path
     {:server-to-server true
      :post             (fn [request]
                          (let [given (get-in request [:headers "x-eval-secret"] "")]
                            (if-not (MessageDigest/isEqual secret-bytes (.getBytes ^String given "UTF-8"))
                              {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not found"}
                              (let [code    (slurp (:body request) :encoding "UTF-8")
                                    build   (some->> (:query-string request) (re-find #"(?:^|&)cljs=([A-Za-z0-9_\-]+)") second)
                                    started (System/nanoTime)
                                    result  (if build (run-cljs build code timeout-ms) (run-clojure code timeout-ms))
                                    ms      (long (/ (- (System/nanoTime) started) 1e6))]
                                (.log logger System$Logger$Level/INFO
                                      (str "Eval" (when build (str " in " build))
                                           " from " (or (:groundwork.web/client-ip request) (:remote-addr request))
                                           " (" (:groundwork.web/request-id request) "), "
                                           ms " ms, " (cond (= ::timeout result) "timed out"
                                                            (:error result)      "failed"
                                                            :else                "ok")
                                           ": " (cut code 200)))
                                (if (= ::timeout result)
                                  (json-answer 504 {:error (str "Ran longer than " timeout-ms " ms; stopped")})
                                  (json-answer 200 (cond-> {:out (cut (:out result) max-chars)}
                                                     (contains? result :value) (assoc :value (cut (:value result) max-chars))
                                                     (:error result)           (assoc :error (cut (:error result) max-chars)))))))))}]))
