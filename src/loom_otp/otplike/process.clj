(ns loom-otp.otplike.process
  "Compatibility shim providing otplike.process API backed by loom-otp.
   
   Key differences handled:
   - `!` function for sending (vs loom-otp's `send`)
   - `proc-fn`, `proc-defn` macros (vs loom-otp's plain functions)
   - `async`/`await!`/`await!!` implemented via vfuture
   - Tuple returns [:ok pid] vs map {:ok pid}
   - `receive!!` variant (blocking) is same as receive! in virtual threads
   
   This namespace does NOT modify loom-otp internals."
  (:refer-clojure :exclude [await])
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as pexit]
            [loom-otp.process.link :as plink]
            [loom-otp.process.monitor :as pmonitor]
            [loom-otp.process.receive :as precv]
            [loom-otp.process.match :as pmatch]
            [loom-otp.process.spawn :as pspawn]
            [loom-otp.vfuture :as vf]
            [loom-otp.registry :as reg]
            [loom-otp.types :as t]
            [loom-otp.state :as state]
            [loom-otp.trace :as trace]
            [loom-otp.otplike.util :as util]))

;; =============================================================================
;; Types - Re-export
;; =============================================================================

(defn pid?
  "Returns true if x is a process identifier."
  [x]
  (t/pid? x))

(defn ref?
  "Returns true if x is a reference."
  [x]
  (t/ref? x))

;; =============================================================================
;; Process introspection
;; =============================================================================

(defn self
  "Returns the process identifier of the calling process."
  []
  (core/self))

(defn whereis
  "Returns the pid registered under name, or nil."
  [name]
  (reg/whereis name))

(defn registered
  "Returns a set of all registered names."
  []
  (set (reg/registered)))

(defn resolve-pid
  "Resolve pid-or-name to a pid. Returns nil if not found."
  [pid-or-name]
  (reg/resolve-pid pid-or-name))

(defn pid->str
  "Convert pid to string representation."
  [pid]
  (when-not (t/pid? pid)
    (throw (ex-info "pid->str requires a pid" {:arg pid})))
  (str pid))

;; =============================================================================
;; Message sending - otplike uses `!`
;; =============================================================================

(defn !
  "Send a message to dest (pid or registered name). Returns true if sent, false if not."
  [dest message]
  (when (nil? dest)
    (throw (ex-info "! requires a non-nil dest" {:dest dest})))
  (core/send dest message))

;; =============================================================================
;; Process lifecycle
;; =============================================================================

(defn exit
  "Exit with reason, or send exit signal to pid with reason.
   Returns true if process is/was alive, false if already terminated."
  ([reason]
   (pexit/exit reason))
  ([pid reason]
   (when-not (t/pid? pid)
     (throw (ex-info "exit requires a pid" {:arg pid})))
   (when (nil? reason)
     (throw (ex-info "exit requires a non-nil reason" {:reason reason})))
   ;; Check if process is alive before sending signal
   (let [is-alive (proc/alive? pid)]
     (when is-alive
       (pexit/exit pid reason))
     is-alive)))

(defn flag
  "Set a process flag. Returns the old value.
   Currently supports :trap-exit."
  [flag-name value]
  (let [proc (core/self-proc)
        flags (:flags proc)]
    (case flag-name
      :trap-exit
      (let [old-val (get @flags :trap-exit false)]
        (swap! flags assoc :trap-exit (boolean value))
        old-val)
      (throw (ex-info "Unknown flag" {:flag flag-name})))))

;; =============================================================================
;; Links
;; =============================================================================

(defn link
  "Create a bidirectional link between calling process and pid.
   Must be called from within a process context.
   If pid is terminated, sends [:EXIT pid :noproc] to self if trapping exits,
   otherwise causes self to exit."
  [pid]
  (when-not (t/pid? pid)
    (throw (ex-info "link requires a pid" {:arg pid})))
  (let [self-pid (core/self)
        self-proc (core/self-proc)]
    (if (proc/alive? pid)
      ;; Target is alive - create bidirectional link
      (plink/link! self-pid pid)
      ;; Target is dead - send exit signal or exit
      (let [ctx (core/message-context)]
        (core/send-exit-signal! self-pid pid :noproc ctx)))
    true))

(defn unlink
  "Remove link between calling process and pid.
   Must be called from within a process context."
  [pid]
  (when-not (t/pid? pid)
    (throw (ex-info "unlink requires a pid" {:arg pid})))
  (let [self-pid (core/self)]
    (plink/unlink! self-pid pid)
    true))

;; =============================================================================
;; Monitors
;; =============================================================================

(defn monitor
  "Monitor a process. Returns a reference."
  [pid-or-name]
  (pmonitor/monitor pid-or-name))

(defn demonitor
  "Remove a monitor. With {:flush true}, removes pending DOWN messages.
   Throws when called not in process context, or mref is not a ref."
  ([mref]
   (demonitor mref {}))
  ([mref {:keys [flush]}]
   ;; Validate mref is a ref
   (when-not (t/ref? mref)
     (throw (ex-info "demonitor requires a ref" {:arg mref})))
   ;; Validate we're in process context
   (when-not (core/try-self)
     (throw (ex-info "demonitor must be called from process context" {})))
   (pmonitor/demonitor mref)
   (when flush
     ;; Try to remove any pending DOWN message with this ref
     (try
       (precv/selective-receive!
         (fn [[tag ref & _]]
           (and (= tag :DOWN) (= ref mref)))
         0)
       (catch Exception _)))
   true))

;; =============================================================================
;; Process spawning
;; =============================================================================

(defn- otplike-ex->reason
  "Convert exception to otplike-compatible exit reason.
   Returns [:exception {:class ... :message ... :stack-trace ...}]"
  [^Throwable e]
  [:exception (util/stack-trace e)])

(defn spawn-opt
  "Spawn a process with options.
   proc-func is a function (not proc-fn).
   Options: :flags {:trap-exit true}, :link true, :register name"
  ([proc-func opts]
   (spawn-opt proc-func [] opts))
  ([proc-func args opts]
   ;; Validate arguments
   (when-not (fn? proc-func)
     (throw (ex-info "spawn requires a function" {:arg proc-func})))
   (when-not (sequential? args)
     (throw (ex-info "spawn args must be sequential" {:args args})))
   (when-not (map? opts)
     (throw (ex-info "spawn options must be a map" {:opts opts})))
   (when-let [flags (:flags opts)]
     (when-not (map? flags)
       (throw (ex-info "spawn :flags must be a map" {:flags flags}))))
   ;; Check for already registered name
   (when-let [reg-name (:register opts)]
     (when (reg/whereis reg-name)
       (throw (ex-info "name already registered" {:name reg-name}))))
   ;; Check if calling process has exited (for spawn-link)
   (when (:link opts)
     (when-let [self-pid (core/try-self)]
       (when-let [proc (state/get-proc self-pid)]
         (when @(:exit-reason proc)
           (throw (ex-info "spawn-link called from exited process"
                           {:reason :noproc}))))))
   (let [{:keys [flags link register]} opts
         spawn-opts (cond-> {:ex->reason-fn otplike-ex->reason}
                      (:trap-exit flags) (assoc :trap-exit true)
                      link (assoc :link true)
                      register (assoc :reg-name register))]
     (proc/spawn-opt spawn-opts (fn [] (apply proc-func args))))))

(defn spawn
  "Spawn a process running (proc-func & args)."
  ([proc-func]
   (spawn proc-func []))
  ([proc-func args]
   (spawn-opt proc-func args {})))

(defn spawn-link
  "Spawn a process linked to the calling process."
  ([proc-func]
   (spawn-link proc-func []))
  ([proc-func args]
   (spawn-opt proc-func args {:link true})))

;; =============================================================================
;; proc-fn / proc-defn macros
;; 
;; In otplike, proc-fn creates a TProcFn that wraps code in a go block.
;; In loom-otp, we just use regular functions since virtual threads don't need go.
;; The proc-fn macro creates a function that can be passed to spawn.
;; =============================================================================

(defmacro proc-fn
  "Create a process function. Returns a function suitable for spawn.
   
   Usage:
   (proc-fn [x y] (+ x y))
   (proc-fn my-name [x] (do-something x))
   
   In otplike this creates a TProcFn for go blocks.
   In loom-otp this creates a regular function."
  [name-or-args & more]
  (if (symbol? name-or-args)
    ;; Named: (proc-fn name [args] body...)
    (let [args (first more)
          body (rest more)]
      `(fn ~name-or-args ~args ~@body))
    ;; Anonymous: (proc-fn [args] body...)
    `(fn ~name-or-args ~@more)))

(defmacro proc-defn
  "Define a process function. Same as (def name (proc-fn ...))"
  {:arglists '([fname doc-string? args & body])}
  [fname & more]
  (when-not (symbol? fname)
    (throw (ex-info "proc-defn requires a symbol name" {:arg fname})))
  (when-not (or (string? (first more)) (vector? (first more)))
    (throw (ex-info "proc-defn requires args vector" {:arg (first more)})))
  (let [[doc-string args body] (if (string? (first more))
                                 [(first more) (second more) (drop 2 more)]
                                 [nil (first more) (rest more)])]
    (when-not (vector? args)
      (throw (ex-info "proc-defn requires args vector" {:arg args})))
    ;; Quote the arglists so the symbols in args aren't evaluated
    (let [meta-map (cond-> (meta fname)
                     doc-string (assoc :doc doc-string)
                     args (assoc :arglists `'(~args)))]
      `(def ~(with-meta fname meta-map)
         (proc-fn ~fname ~args ~@body)))))

(defmacro proc-defn-
  "Same as proc-defn but private."
  [fname & more]
  `(proc-defn ~(vary-meta fname assoc :private true) ~@more))

;; =============================================================================
;; Async/Await - synchronous execution with exception capture
;;
;; In the original otplike, async used core.async go blocks which are
;; cooperative coroutines on a thread pool. With virtual threads, blocking
;; is cheap, so we execute synchronously and immediately.
;;
;; The async/await pattern is used for composable exception handling:
;; - async executes body immediately and captures exceptions
;; - await retrieves the value or re-throws the captured exception
;; - map-async transforms values while preserving exception capture
;;
;; IMPORTANT: async executes the body IMMEDIATELY on the thread calling async,
;; not lazily on await. This maintains the single-thread-per-process invariant
;; and allows async code to access the process mailbox.
;; =============================================================================

;; Async wraps an already-computed result.
;; Fields:
;;   - result: atom holding [:value v] | [:exception e] (always populated)
;;   - map-fns: vector of transformation functions to apply when dereferenced
;;   - creator-pid: the pid of the process that created this async (or nil if outside process)
(deftype Async [result map-fns creator-pid]
  clojure.lang.IDeref
  (deref [this]
    ;; Check cross-process await - if created in a process, can only await in same process
    (let [current (core/try-self)]
      (when (and creator-pid (not= creator-pid current))
        (throw (ex-info "Cannot await async from different process than creator"
                        {:creator creator-pid :current current}))))
    ;; Return cached result or throw cached exception
    (let [[tag v] @result]
      (if (= tag :exception)
        ;; Re-throw with exit-reason in ex-data so ex-catch can extract it
        (throw (ex-info "async exit" {:loom-otp.process.exit/exit-reason v}))
        ;; Apply transformation functions
        (reduce (fn [v f] (f v)) v map-fns))))
  
  clojure.lang.IPending
  (isRealized [_] true))  ;; Always realized since we execute immediately

(defn async?
  "Returns true if x is an async value."
  [x]
  (instance? Async x))

(defn async-value
  "Wrap a value as an already-completed async."
  [value]
  (Async. (atom [:value value]) [] (core/try-self)))

(defn map-async
  "Apply f to the eventual value of async-val.
   Creates a new Async that will apply f after the original transformations.
   The new Async shares the same result atom."
  [f async-val]
  {:pre [(fn? f) (async? async-val)]}
  (Async. (.result ^Async async-val)
          (conj (.map-fns ^Async async-val) f)
          (.creator-pid ^Async async-val)))

(defmacro async
  "Execute body immediately and capture the result or exception.
   Returns an Async value that can be awaited.
   
   The body runs synchronously on the calling thread. This maintains the 
   single-thread-per-process model and allows async code to access 
   the process mailbox.
   
   NOTE: InterruptedException is NOT caught during execution - it needs 
   to propagate to the spawn handler which checks the exit-reason set 
   by exit signals."
  [& body]
  `(let [creator-pid# (core/try-self)
         result# (try
                   [:value (do ~@body)]
                   (catch InterruptedException ie#
                     (throw ie#))
                   (catch Throwable t#
                     [:exception (pexit/ex->reason t#)]))]
     (Async. (atom result#) [] creator-pid#)))

(defmacro await!
  "Wait for async value and return result. Parks/blocks if needed."
  [x]
  `(let [v# ~x]
     (if (async? v#)
       @v#
       v#)))

(defn await!!
  "Same as await! - blocks. In virtual threads there's no difference.
   Throws IllegalArgumentException if x is not an async value."
  [x]
  (if (async? x)
    @x
    (throw (IllegalArgumentException. (str "await!! requires an async value, got: " (type x))))))

(defmacro await?!
  "If x is async, await it. Otherwise return x."
  [x]
  `(let [v# ~x]
     (if (async? v#)
       @v#
       v#)))

(defmacro with-async
  "Transform async value with body. Body receives the resolved value."
  [[binding async-expr] & body]
  `(map-async (fn [~binding] ~@body) ~async-expr))

;; =============================================================================
;; Receive macros - delegate to loom-otp.process.match
;; =============================================================================

(defmacro receive!
  "Receive a message with pattern matching.
   Supports (after timeout body) clause."
  [& clauses]
  `(pmatch/receive! ~@clauses))

(defmacro receive!!
  "Same as receive! - in virtual threads blocking is the same as parking."
  [& clauses]
  `(pmatch/receive! ~@clauses))

(defmacro selective-receive!
  "Selective receive with pattern matching.
   Scans mailbox for first matching message."
  [& clauses]
  `(pmatch/selective-receive! ~@clauses))

;; =============================================================================
;; Exception handling
;; =============================================================================

(defn ex->reason
  "Convert exception to exit reason.
   Uses util/stack-trace for otplike-compatible exception format.
   InterruptedException is converted to :killed."
  [^Throwable e]
  (or (::pexit/exit-reason (ex-data e))  ;; Check namespaced key first
      (:exit-reason (ex-data e))
      (when (instance? InterruptedException e) :killed)
      [:exception (util/stack-trace e)]))

(defmacro ex-catch
  "Execute expr, return result or [:EXIT reason] on exception."
  [expr]
  `(try
     ~expr
     (catch Throwable t#
       ;; Check for exit exception with namespaced key,
       ;; or convert InterruptedException to :killed
       (let [reason# (or (:loom-otp.process.exit/exit-reason (ex-data t#))
                         (:exit-reason (ex-data t#))
                         (when (instance? InterruptedException t#) :killed)
                         [:exception (util/stack-trace t#)])]
         [:EXIT reason#]))))

;; =============================================================================
;; Process info
;; =============================================================================

(defn alive?
  "Check if process is alive."
  ([]
   (proc/alive?))
  ([pid]
   (proc/alive? pid)))

(defn processes
  "Return list of all process pids."
  []
  (proc/processes))

(def ^:private allowed-info-keys
  "Valid info keys for process-info"
  #{:links
    :monitors
    :monitored-by
    :registered-name
    :status
    :life-time-ms
    :initial-call
    :message-queue-len
    :messages
    :flags})

(defn- validate-info-keys
  "Validate that all info keys are valid. Throws on invalid key."
  [items]
  (let [keys-to-check (if (keyword? items) [items] items)]
    (doseq [k keys-to-check]
      (when-not (allowed-info-keys k)
        (throw (ex-info "invalid info key" {:key k :allowed allowed-info-keys}))))))

(defn process-info
  "Get information about a process.
   Throws if pid is not a pid, or specified info-key doesn't exist."
  ([pid]
   (process-info pid [:initial-call :status :message-queue-len :links :flags]))
  ([pid items]
   ;; Validate pid
   (when-not (t/pid? pid)
     (throw (ex-info "process-info requires a pid" {:arg pid})))
   ;; Validate items is keyword or sequential
   (when-not (or (keyword? items) (sequential? items))
     (throw (ex-info "process-info items must be keyword or sequential" {:items items})))
   ;; Validate all keys are valid (even for exited processes)
   (validate-info-keys items)
   ;; Get info (returns nil for exited process)
   (when-let [info (proc/process-info pid)]
     (if (keyword? items)
       ;; Single item - return [key value]
       [items (get info items)]
       ;; Multiple items - return list of [key value]
       (for [k items]
         [k (get info k)])))))

;; =============================================================================
;; Tracing
;; =============================================================================

(defn trace
  "Set up tracing with predicate and handler."
  [pred handler]
  (trace/trace (fn [event]
                 (when (pred event)
                   (handler event))))
  true)

(defn untrace
  "Remove trace handler."
  []
  (trace/untrace)
  true)
