(ns loom-otp.process
  "Main process API.
   
   This namespace provides the primary interface for working with processes:
   - spawn, spawn-link, spawn-opt for creating processes
   - self, send for current process and messaging
   - exit for termination
   - monitor, demonitor for unidirectional monitors
   - receive!, selective-receive! for receiving messages (function-based)
   - alive?, processes, process-info for introspection
   - register for name registration
   
   Pids are Thread objects directly.
   
   For pattern-matching receive macros, see loom-otp.process.match."
  (:refer-clojure :exclude [send])
  (:require [loom-otp.types :as t]
            [loom-otp.state :as state]
            [loom-otp.registry :as reg]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as pexit]
            [loom-otp.process.link :as plink]
            [loom-otp.process.monitor :as pmonitor]
            [loom-otp.process.receive :as precv]
            [loom-otp.process.spawn :as spawn]))

;; =============================================================================
;; Re-exports from sub-namespaces
;; =============================================================================

;; From process.core
(def self core/self)
(def send core/send)

;; From process.exit
(def exit pexit/exit)

;; From process.monitor
(def monitor pmonitor/monitor)
(def demonitor pmonitor/demonitor)

;; From process.receive
(def receive! precv/receive!)
(def selective-receive! precv/selective-receive!)

;; =============================================================================
;; Process Info
;; =============================================================================

(defn process-info
  "Get information about a process. Returns nil for non-Thread or unknown pid."
  [^Thread pid]
  (when (t/pid? pid)
    (when-let [proc (state/get-proc pid)]
      {:pid pid
       :links (plink/get-links pid)
       :monitors (->> (pmonitor/get-monitors-by-watcher pid)
                      (map (fn [m] [(:ref-id m) [(:target m) (:target-name m)]]))
                      (into {}))
       :monitored-by (->> (pmonitor/get-monitors-by-target pid)
                          (map (fn [m] [(:ref-id m) (:watcher m)]))
                          (into {}))
       :registered-name (reg/get-registered-name pid)
       :status (if @(:exit-reason proc) :exiting :running)
       :message-queue-len (count @(:mailbox proc))
       :flags @(:flags proc)})))

;; =============================================================================
;; Process Introspection
;; =============================================================================

(defn alive?
  "Check if process is alive. Returns true if alive, false if dead or not found.
   Returns false for non-Thread arguments."
  ([^Thread pid]
   (if (t/pid? pid)
     (if-let [proc (state/get-proc pid)]
       (and (.isAlive pid) (nil? @(:exit-reason proc)))
       false)
     false))
  ([]
   (alive? (core/self))))

(defn processes
  "Return list of all active process pids (Threads).
   Does not include processes that are past their cleanup time."
  []
  (state/active-pids))

;; =============================================================================
;; Registration (convenience wrapper)
;; =============================================================================

(defn register
  "Register name for pid. Throws if name already registered."
  ([name] (reg/register! name (core/self)))
  ([name pid] (reg/register! name pid)))

;; =============================================================================
;; Spawn API
;; =============================================================================

(defn spawn-opt
  "Spawn with options. Returns pid, or [pid ref] if :monitor is true.
   If :async is true, returns a vfuture that resolves to pid (or [pid ref]).
   
   Options:
   - :link - if true, link to parent process
   - :monitor - if true, monitor child process (returns [pid ref])
   - :reg-name - name to register the process as
   - :trap-exit - if true, convert exit signals to [:EXIT pid reason] messages
   - :async - if true, return vfuture instead of blocking for pid
   - :ex->reason-fn - function to convert exceptions to exit reasons"
  [{:keys [link monitor reg-name trap-exit async ex->reason-fn]} func & args]
  (let [parent (when (or link monitor) (core/try-self))
        flags (when trap-exit {:trap-exit true})
        opts {:func func 
              :args args 
              :link-to (when link parent)
              :watcher-pid (when monitor parent)
              :monitor? monitor
              :reg-name reg-name
              :flags flags
              :ex->reason-fn ex->reason-fn}]
    (if async
      (spawn/spawn-process-async opts)
      (spawn/spawn-process opts))))

(def spawn
  "Spawn a new process running (func & args). Returns pid (Thread)."
  (partial spawn-opt {}))

(def spawn-link
  "Spawn a new process linked to current process. Returns pid (Thread)."
  (partial spawn-opt {:link true}))

(def spawn-trap
  "Spawn a new process with trap-exit enabled. Returns pid (Thread)."
  (partial spawn-opt {:trap-exit true}))

(def spawn-monitor
  "Spawn a new process monitored by current process. Returns [pid ref]."
  (partial spawn-opt {:monitor true}))

;; =============================================================================
;; Spawn Macros (convenience wrappers that wrap body in fn)
;; =============================================================================

(defmacro spawn-opt!
  "Spawn with options, body wrapped in anonymous function.
   
   Usage: (spawn-opt! {:trap-exit true} (do-something) (do-more))
   
   Options: same as spawn-opt"
  [opts & body]
  `(spawn-opt ~opts (fn [] ~@body)))

(defmacro spawn!
  "Spawn a new process running body. Returns pid (Thread).
   
   Usage: (spawn! (println \"hello\") (do-work))"
  [& body]
  `(spawn (fn [] ~@body)))

(defmacro spawn-link!
  "Spawn a new process linked to current process, body wrapped in fn.
   
   Usage: (spawn-link! (do-work))"
  [& body]
  `(spawn-link (fn [] ~@body)))

(defmacro spawn-trap!
  "Spawn a new process with trap-exit enabled, body wrapped in fn.
   
   Usage: (spawn-trap! (receive! [:EXIT pid reason] ...))"
  [& body]
  `(spawn-trap (fn [] ~@body)))

(defmacro spawn-monitor!
  "Spawn a new process monitored by current process, body wrapped in fn.
   Returns [pid ref].
   
   Usage: (let [[pid ref] (spawn-monitor! (do-work))] ...)"
  [& body]
  `(spawn-monitor (fn [] ~@body)))

(defmacro spawn-async!
  "Spawn a new process asynchronously, body wrapped in fn.
   Returns a vfuture that resolves to pid (Thread).
   
   Usage: (let [f (spawn-async! (do-work))] ... @f)"
  [& body]
  `(spawn-opt {:async true} (fn [] ~@body)))
