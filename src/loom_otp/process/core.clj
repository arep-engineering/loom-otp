(ns loom-otp.process.core
  "Minimal core process primitives: current process, messaging, exit signal handling.
   
   This namespace provides the foundation that other process.* namespaces build on.
   Pids are Thread objects.
   
   Process identity is determined by Thread -> process-map lookup in ConcurrentHashMap.
   One process = one thread = one mailbox. No cross-thread context sharing."
  (:refer-clojure :exclude [send])
  (:require [loom-otp.types :as t]
            [loom-otp.state :as state]
            [loom-otp.context-mailbox :as cmb]
            [loom-otp.registry :as reg]
            [loom-otp.trace :as trace]))

;; =============================================================================
;; Process Introspection
;; =============================================================================

(defn self
  "Return the pid (Thread) of the current process. Must be called from within a process."
  ^Thread []
  (let [^Thread t (Thread/currentThread)]
    (if (state/get-proc t)
      t
      (throw (ex-info "self called outside of process" {})))))

(defn try-self
  "Return the pid (Thread) of the current process, or nil if not in a process context."
  []
  (let [^Thread t (Thread/currentThread)]
    (when (state/get-proc t) t)))

(defn self-proc
  "Return the current process map. Internal use."
  []
  (let [^Thread t (Thread/currentThread)]
    (or (state/get-proc t)
        (throw (ex-info "not in a process" {})))))

(defn get-proc
  "Get process by pid (Thread). Returns nil if not found."
  [^Thread pid]
  (state/get-proc pid))

(defn process-exists?
  "Check if process exists in the process table."
  [^Thread pid]
  (state/process-exists? pid))

(defn get-user-result
  "Get the user-result promise from a process by pid.
   Returns nil if process doesn't exist or has no user-result."
  [^Thread pid]
  (when-let [proc (state/get-proc pid)]
    (:user-result proc)))

;; =============================================================================
;; Message Context API
;; =============================================================================

(defn message-context
  "Get the current process's message context.
   Returns {} if called outside a process."
  []
  (if-let [proc (state/get-proc (Thread/currentThread))]
    @(:message-context proc)
    {}))

(defn update-message-context!
  "Merge ctx into current process's message context.
   No-op if called outside a process or ctx is nil/empty."
  [ctx]
  (when-let [proc (state/get-proc (Thread/currentThread))]
    (when (and ctx (seq ctx))
      (swap! (:message-context proc) merge ctx))))

(defn- current-context
  "Get context from current process, or {} if not in a process."
  []
  (if-let [proc (state/get-proc (Thread/currentThread))]
    @(:message-context proc)
    {}))

;; =============================================================================
;; Message Passing
;; =============================================================================

(defn send
  "Send message to destination (pid/Thread or registered name). Returns true if delivered.
   Message is wrapped with sender's context for propagation."
  [dest message]
  (let [^Thread pid (reg/resolve-pid dest)]
    (if-let [proc (and pid (state/get-proc pid))]
      (do
        (trace/trace-event! :send {:from (Thread/currentThread)
                                   :to pid
                                   :message message})
        (cmb/send! (:mailbox proc) (current-context) message)
        true)
      false)))

;; =============================================================================
;; Exit Signal Handling
;; =============================================================================

(defn- normal-reason?
  "Returns true if reason represents a normal exit."
  [reason]
  (= reason :normal))

(defn send-exit-signal!
  "Send exit signal to a process. Handles trap-exit semantics.
   - If trap-exit=true: converts to [:EXIT from reason] message
   - If reason=:kill: interrupts thread with :killed
   - If reason=:normal (or [:normal x]) and not trapping: ignored
   - Otherwise: sets exit reason and interrupts thread
   
   ctx is the context from the signaling/dying process.
   Returns true if signal was sent, false if target doesn't exist."
  [^Thread to-pid ^Thread from-pid reason ctx]
  (if-let [proc (state/get-proc to-pid)]
    (let [trap-exit? (get @(:flags proc) :trap-exit false)]
      (trace/trace-event! :exit-signal {:pid to-pid :from from-pid :reason reason :trap-exit trap-exit?})
      (cond
        ;; :kill is untrappable - always interrupt
        (= reason :kill)
        (do
          ;; Set context before interrupting so cleanup can propagate it
          (when-let [ctrl-ctx (:last-control-ctx proc)]
            (reset! ctrl-ctx ctx))
          (reset! (:exit-reason proc) :killed)
          (.interrupt to-pid)
          true)
        
        ;; Normal exit when not trapping - ignore
        (and (normal-reason? reason) (not trap-exit?))
        true
        
        ;; Trapping exits - convert to message
        trap-exit?
        (do
          (cmb/send! (:mailbox proc) ctx [:EXIT from-pid reason])
          true)
        
        ;; Not trapping - terminate the process
        :else
        (do
          ;; Set context before interrupting so cleanup can propagate it
          (when-let [ctrl-ctx (:last-control-ctx proc)]
            (reset! ctrl-ctx ctx))
          (reset! (:exit-reason proc) reason)
          (.interrupt to-pid)
          true)))
    false))
