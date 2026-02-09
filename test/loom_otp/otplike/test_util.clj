(ns loom-otp.otplike.test-util
  "Test utilities for otplike compatibility tests.
   
   Adapts otplike's test helpers for loom-otp's virtual thread model.
   NO core.async - uses promises and vfutures instead."
  (:require [clojure.test :as clojure-test]
            [clojure.core.match :refer [match]]
            [loom-otp.otplike.proc-util :as proc-util]
            [loom-otp.otplike.process :as process]
            [loom-otp.process.match :as pmatch]
            [loom-otp.process :as proc]
            [loom-otp.vfuture :as vf]))

(defmacro clojure-minor-version []
  (let [[_ minor] (re-find #"^1\.(\d+)" (clojure-version))]
    (Integer/parseInt minor)))

(defmacro if-clojure-version [minor-versions & body]
  (if ((set minor-versions) (clojure-minor-version))
    `(do ~@body)))

(defn uuid-keyword
  "Makes random keyword with a name being UUID string."
  []
  (keyword (str (java.util.UUID/randomUUID))))

(defmacro await-message
  "Tries to receive a message. MUST be called from within a process context!
   Returns:
    - :timeout if no message appears during timeout-ms
    - [:exit [pid reason]] if :EXIT message received
    - [:down [ref object reason]] if :DOWN message received
    - [:message message] on any other message
    - [:noproc reason] if inbox becomes closed before message appears"
  [timeout-ms]
  `(try
     (process/receive!
       [:EXIT pid# reason#] [:exit [pid# reason#]]
       [:DOWN ref# :process object# reason#] [:down [ref# object# reason#]]
       msg# [:message msg#]
       (~'after ~timeout-ms :timeout))
     (catch InterruptedException _#
       [:noproc :killed])
     (catch Exception e#
       [:noproc (process/ex->reason e#)])))

(defmacro await-completion!
  "Wait for promise to be delivered or timeout.
   Adapted for use within process context - actually just delegates to await-completion!!
   since in virtual threads there's no parking/blocking distinction.
   Returns:
    - :closed if promise receives nil (like closing a channel)
    - [:ok val] if promise gets a value during timeout-ms
   Throws on timeout."
  [prom timeout-ms]
  `(await-completion!! ~prom ~timeout-ms))

(defn await-completion!!
  "Wait for promise to be delivered or timeout.
   Returns:
    - :closed if promise receives nil (like closing a channel)
    - [:ok val] if promise gets a value during timeout-ms
   Throws on timeout."
  [prom timeout-ms]
  (let [result (deref prom timeout-ms ::timeout)]
    (cond
      (= result ::timeout)
      (throw (Exception. (str "timeout " timeout-ms)))
      
      (nil? result)
      :closed
      
      :else
      [:ok result])))

(defmacro def-proc-test
  "Define a test that runs in a process context.
   Tests should explicitly set trap-exit if needed for supervisor testing."
  [name & body]
  `(clojure-test/deftest ~name
     (proc-util/execute-proc!!
       ~@body)))

(defmacro matches? [what to]
  `(match ~what ~to true _# false))

(defn sym-bound? [sym]
  (if-let [v (resolve sym)]
    (bound? v)))
