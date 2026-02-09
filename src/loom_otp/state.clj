(ns loom-otp.state
  "Global state management for loom-otp.
   
   Uses mount.lite for state lifecycle, enabling:
   - Clean startup/shutdown
   - Parallel test sessions with isolated state
   - Proper cleanup on stop
   
   Pids are Thread objects directly. ConcurrentHashMap provides
   thread-safe access without contention."
  (:require [mount.lite :as mount])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]
           [java.time Instant]))

;; =============================================================================
;; Cleanup delay configuration
;; =============================================================================

(def ^:private cleanup-delay-ms
  "Default delay before removing exited processes from table (5 minutes)"
  (* 5 60 1000))

;; Forward declaration for cleanup function (defined later, after process-table accessors)
(declare cleanup-expired-internal!)

;; =============================================================================
;; Global State (managed by mount.lite)
;; =============================================================================

(mount/defstate system
  "The process system state. Contains all processes, monitors, and registry.
   Pids are Thread objects. Links are stored in each process's :links atom."
  :start (let [state {:processes (ConcurrentHashMap.)     ; Thread -> process-map
                      :monitors (ConcurrentHashMap.)      ; ref-id (Long) -> monitor-info
                      :registry-forward (ConcurrentHashMap.)  ; name -> Thread
                      :registry-reverse (ConcurrentHashMap.)  ; Thread -> name
                      :trace-fn (atom nil)                ; trace handler function
                      :ref-counter (AtomicLong. 0)
                      :cleanup-thread (atom nil)}
               cleanup-fn (fn []
                            (while (not (Thread/interrupted))
                              (try
                                (Thread/sleep 60000)  ;; every minute
                                (cleanup-expired-internal! (:processes state))
                                (catch InterruptedException _ nil)
                                (catch Exception _ nil))))]
           ;; Start cleanup thread
           (reset! (:cleanup-thread state)
                   (Thread/startVirtualThread cleanup-fn))
           state)
  :stop (let [{:keys [^ConcurrentHashMap processes cleanup-thread]} @system]
          ;; Stop cleanup thread
          (when-let [^Thread t @cleanup-thread]
            (.interrupt t))
          ;; Interrupt all running threads on stop
          (doseq [[^Thread thread proc] processes]
            (when (.isAlive thread)
              (.interrupt thread)))
          ;; Give threads a moment to terminate
          (Thread/sleep 50)
          ;; Clear all maps
          (.clear processes)))

;; =============================================================================
;; State Accessors
;; =============================================================================

(defn processes
  "Returns the ConcurrentHashMap of Thread -> process-map."
  ^ConcurrentHashMap []
  (:processes @system))

(defn monitors
  "Returns the ConcurrentHashMap of ref-id -> monitor-info."
  ^ConcurrentHashMap []
  (:monitors @system))

(defn registry-forward
  "Returns the ConcurrentHashMap of name -> Thread."
  ^ConcurrentHashMap []
  (:registry-forward @system))

(defn registry-reverse
  "Returns the ConcurrentHashMap of Thread -> name."
  ^ConcurrentHashMap []
  (:registry-reverse @system))

(defn trace-fn-atom []
  (:trace-fn @system))

(defn- ref-counter ^AtomicLong []
  (:ref-counter @system))

;; =============================================================================
;; Process Table Operations
;; =============================================================================

(defn get-proc-raw
  "Get process map for a thread/pid, including processes marked for cleanup.
   Used internally for cleanup operations."
  [^Thread pid]
  (when pid
    (.get (processes) pid)))

(defn get-proc
  "Get process map for a thread/pid. Returns nil if not found or if process has fully exited.
   Processes that are 'exiting' (exit-reason set but thread still alive) are returned.
   Processes that have fully exited (exit-reason set and thread not alive) return nil."
  [^Thread pid]
  (when pid
    (when-let [proc (.get (processes) pid)]
      ;; Return nil if process has fully exited (exit-reason set and thread dead)
      (if @(:exit-reason proc)
        (when (.isAlive pid) proc)
        proc))))

(defn put-proc!
  "Add process to the process table."
  [^Thread pid proc]
  (.put (processes) pid proc))

(defn remove-proc!
  "Remove process from the process table."
  [^Thread pid]
  (.remove (processes) pid))

(defn process-exists?
  "Check if a process exists in the table and is not past cleanup time."
  [^Thread pid]
  (and pid (some? (get-proc pid))))

(defn active-pids
  "Return list of all active process pids.
   Includes processes that are 'exiting' (exit-reason set but thread still alive).
   Excludes processes that have fully exited (exit-reason set and thread not alive)."
  []
  (->> (enumeration-seq (.keys (processes)))
       (filter (fn [^Thread pid]
                 (when-let [proc (.get (processes) pid)]
                   ;; Include if: no exit-reason set, OR thread is still alive
                   (or (nil? @(:exit-reason proc))
                       (.isAlive pid)))))))

;; =============================================================================
;; ID Generation
;; =============================================================================

(defn next-ref!
  "Generate a new unique TRef."
  []
  (.incrementAndGet (ref-counter)))

;; =============================================================================
;; System Lifecycle
;; =============================================================================

(defn start!
  "Start the process system."
  []
  (mount/start))

(defn stop!
  "Stop the process system, terminating all processes."
  []
  (mount/stop))

(defn reset-all!
  "Reset all global state. For testing - stops and restarts the system."
  []
  (mount/stop)
  (mount/start))

;; =============================================================================
;; Delayed Process Cleanup
;; =============================================================================

(defn mark-for-cleanup!
  "Mark process for delayed cleanup. Called when a process exits."
  ([^Thread pid] (mark-for-cleanup! pid cleanup-delay-ms))
  ([^Thread pid delay-ms]
   (when-let [proc (get-proc-raw pid)]
     (when-let [cleanup-atom (:cleanup-after proc)]
       (reset! cleanup-atom
               (Instant/ofEpochMilli
                 (+ (System/currentTimeMillis) delay-ms)))))))

(defn- cleanup-expired-internal!
  "Remove processes past their cleanup time. Called periodically."
  [^ConcurrentHashMap processes-map]
  (let [now (Instant/now)]
    (doseq [[^Thread pid proc] processes-map]
      (when-let [cleanup-atom (:cleanup-after proc)]
        (when-let [^Instant cleanup-time @cleanup-atom]
          (when (.isBefore cleanup-time now)
            (.remove processes-map pid)))))))

(defn cleanup-expired!
  "Remove processes past their cleanup time. Public API."
  []
  (cleanup-expired-internal! (processes)))
