(ns groundwork-test.cdp
  "A small Chrome DevTools Protocol client for groundwork's end-to-end test: a
   headless browser started as a process (Playwright's headless shell, or
   Chrome with --headless; no window ever opens), driven over its
   websocket with the JVM's own HttpClient. Commands are answered by id;
   every event is kept, in order, for the test to read, and hooks may act
   on one (a dialog, a paused request) on a thread of their own."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [jsonista.core :as json])
  (:import
   (java.net URI)
   (java.net.http HttpClient WebSocket WebSocket$Listener)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.concurrent TimeUnit)))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- wait-for-file [file timeout-ms]
  (loop [waited 0]
    (cond (and (.exists file) (str/includes? (slurp file) "\n")) file
          (< timeout-ms waited) (throw (ex-info "The browser didn't say its debugging port" {:file (str file)}))
          :else (do (Thread/sleep 50) (recur (+ waited 50))))))

(defn- handle-text! [{:keys [pending events hooks]} text]
  (let [{:keys [id] :as message} (json/read-value text mapper)]
    (if id
      (when-let [answer (get @pending id)]
        (swap! pending dissoc id)
        (deliver answer message))
      (do (swap! events conj message)
          (doseq [hook @hooks] (future (hook message)))))))

(defn- make-listener [connection]
  (let [buffer (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_ socket] (.request socket 1))
      (onText [_ socket data last?]
        (.append buffer data)
        (when last?
          (let [text (str buffer)]
            (.setLength buffer 0)
            (handle-text! connection text)))
        (.request socket 1)
        nil)
      (onClose [_ _ _ _] nil)
      (onError [_ _ _] nil))))

(defn launch!
  "Starts the browser at binary, headless, on a free debugging port, with a
   profile of its own in a new temporary folder, and connects to it.
   Returns the connection, for every other function and quit!."
  [binary]
  (let [folder    (str (Files/createTempDirectory "groundwork-e2e-browser" (make-array FileAttribute 0)))
        process   (-> (ProcessBuilder. ^java.util.List [binary "--headless" "--remote-debugging-port=0"
                                                        (str "--user-data-dir=" folder) "--no-first-run"
                                                        "--no-default-browser-check" "--disable-gpu" "--disable-extensions"
                                                        "--disable-background-networking" "about:blank"])
                      (.redirectErrorStream true)
                      (.redirectOutput (io/file folder "browser.log"))
                      (.start))
        [port path] (str/split-lines (slurp (wait-for-file (io/file folder "DevToolsActivePort") 20000)))
        connection {:process process :folder folder :pending (atom {}) :next-id (atom 0)
                    :events (atom []) :hooks (atom [])}
        socket     (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                       (URI. (str "ws://127.0.0.1:" port path))
                                       (make-listener connection)))]
    (assoc connection :socket socket)))

(defn send!
  "Sends a command, on a page's session when session-id is given, and
   waits for its answer's result; an error answer throws."
  ([connection method params] (send! connection nil method params))
  ([{:keys [pending next-id ^WebSocket socket]} session-id method params]
   (let [id      (swap! next-id inc)
         answer  (promise)
         message (cond-> {:id id :method method :params params} session-id (assoc :sessionId session-id))]
     (swap! pending assoc id answer)
     (locking socket (.join (.sendText socket (json/write-value-as-string message) true)))
     (let [{:keys [error result] :as got} (deref answer 30000 ::timed-out)]
       (cond
         (= ::timed-out got) (throw (ex-info (str method " got no answer in 30 s") {:method method}))
         error               (throw (ex-info (str method " failed: " (:message error)) {:method method :error error}))
         :else               result)))))

(defn add-hook!
  "f gets every event from now on, each on a thread of its own."
  [{:keys [hooks]} f]
  (swap! hooks conj f))

(defn list-events
  "The events kept, in order, of a session when session-id is given,
   whose method is method."
  [{:keys [events]} session-id method]
  (filterv #(and (= method (:method %)) (or (nil? session-id) (= session-id (:sessionId %)))) @events))

(defn make-context!
  "A browser context of its own: its own cookies, its own connections."
  [connection]
  (:browserContextId (send! connection "Target.createBrowserContext" {})))

(defn set-cookie!
  "A cookie in the context, as its Set-Cookie text names it."
  [connection context-id domain set-cookie-text]
  (let [[name value] (str/split (first (str/split set-cookie-text #";")) #"=" 2)]
    (send! connection "Storage.setCookies" {:browserContextId context-id
                                            :cookies          [{:name name :value value :domain domain :path "/"
                                                                :httpOnly true :sameSite "Lax"}]})))

(defn open-page!
  "A new tab in the context, attached, its page, runtime and network
   events on; returns {:target-id :session-id}."
  [connection context-id]
  (let [target-id  (:targetId (send! connection "Target.createTarget" {:url "about:blank" :browserContextId context-id}))
        session-id (:sessionId (send! connection "Target.attachToTarget" {:targetId target-id :flatten true}))]
    (doseq [domain ["Page.enable" "Runtime.enable" "Network.enable"]]
      (send! connection session-id domain {}))
    {:target-id target-id :session-id session-id}))

(defn evaluate
  "The value of a JavaScript expression in the page, awaited when a promise."
  [connection {:keys [session-id]} expression]
  (let [{:keys [result exceptionDetails]} (send! connection session-id "Runtime.evaluate"
                                                 {:expression expression :returnByValue true :awaitPromise true})]
    (when exceptionDetails
      (throw (ex-info (str "The page threw: " (get-in exceptionDetails [:exception :description] (:text exceptionDetails)))
                      {:expression expression})))
    (:value result)))

(defn wait-for
  "The expression's value once it is truthy, asked every 100 ms; nil after
   timeout-ms."
  [connection page expression timeout-ms]
  (let [until (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value (try (evaluate connection page expression) (catch Exception _ nil))]
        (cond value                                   value
              (< until (System/currentTimeMillis))    nil
              :else                                   (do (Thread/sleep 100) (recur)))))))

(defn go-to!
  "Loads the address in the page, as typed in the address bar."
  [connection {:keys [session-id]} url]
  (send! connection session-id "Page.navigate" {:url url}))

(defn type-text!
  "Focuses the element the selector finds and types the text into it, as a
   keyboard would."
  [connection {:keys [session-id] :as page} selector text]
  (evaluate connection page (str "document.querySelector(" (json/write-value-as-string selector) ").focus()"))
  (send! connection session-id "Input.insertText" {:text text}))

(defn click!
  "Clicks the element the selector finds."
  [connection page selector]
  (evaluate connection page (str "document.querySelector(" (json/write-value-as-string selector) ").click(), true")))

(defn close-page!
  [connection {:keys [target-id]}]
  (send! connection "Target.closeTarget" {:targetId target-id}))

(defn quit!
  "Closes the browser, ends its process and deletes its profile."
  [{:keys [^Process process folder ^WebSocket socket]}]
  ;; not waited for: the browser may close before it answers
  (try (locking socket (.join (.sendText socket (json/write-value-as-string {:id 0 :method "Browser.close" :params {}}) true)))
       (catch Exception _ nil))
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)
    (.waitFor process 5 TimeUnit/SECONDS))
  (doseq [file (reverse (file-seq (io/file folder)))]
    (io/delete-file file true)))
