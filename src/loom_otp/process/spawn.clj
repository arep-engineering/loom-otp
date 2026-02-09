(ns loom-otp.process.spawn
  "Process creation: spawn, spawn-link, spawn-opt.
   
   Single-thread model: each process runs in one virtual thread.
   Exit signals are handled by interrupting the thread directly.
   Pids are Thread objects."
  (:require [loom-otp.types :as t]
            [loom-otp.state :as state]
            [loom-otp.context-mailbox :as cmb]
            [loom-otp.registry :as reg]
            [loom-otp.trace :as trace]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.link :as link]
            [loom-otp.process.monitor :as monitor]
            [loom-otp.vfuture :refer [vfuture]]))

;; =============================================================================
;; Process Cleanup
;; =============================================================================

(defn- process-cleanup!
  "Clean up after process terminates. Notifies links, monitors, unregisters.
   Passes the dying process's message context to linked processes and monitors
   for distributed tracing."
  [proc ^Thread pid reason]
  ;; Merge any control context (from exit signals) into message context
  ;; This ensures context propagates even if process caught the interrupt
  (when-let [ctrl-ctx @(:last-control-ctx proc)]
    (swap! (:message-context proc) merge ctrl-ctx))
  (let [dying-ctx @(:message-context proc)]
    
    (trace/trace-event! :exit {:pid pid :reason reason})
    
    ;; Notify linked processes with dying process's context
    (doseq [^Thread linked-pid (link/get-links pid)]
      (core/send-exit-signal! linked-pid pid reason dying-ctx))
    
    ;; Notify monitors watching this process with dying process's context
    (monitor/notify-monitors! pid reason dying-ctx)
    
    ;; Remove monitors this process was watching
    (monitor/cleanup-monitors-for-watcher! pid)
    
    ;; Remove links
    (link/cleanup-links! pid)
    
    ;; Unregister name
    (reg/unregister! pid)
    
    ;; Mark for delayed cleanup instead of immediate removal
    (state/mark-for-cleanup! pid)))

;; =============================================================================
;; Process Creation
;; =============================================================================

(defn- make-process
  "Create a new process structure with given flags and options.
   flags is a map that will be merged with defaults {:trap-exit false}.
   ex->reason-fn is a function that converts exceptions to exit reasons."
  [flags parent-context ex->reason-fn]
  (let [default-flags {:trap-exit false}]
    {:mailbox (cmb/make-mailbox)
     :exit-reason (atom nil)              ; nil = alive, set once on exit
     :cleanup-after (atom nil)            ; nil = alive, Instant = when to remove
     :user-result (promise)               ; result from user function (delivered on normal exit)
     :message-context (atom (or parent-context {}))
     :last-control-ctx (atom {})          ; context from last exit signal (for interrupt handling)
     :flags (atom (merge default-flags flags))
     :links (atom #{})                    ; set of linked Threads
     :ex->reason-fn (or ex->reason-fn exit/ex->reason)}))                 ; set of linked Threads

(defn- setup-and-run!
  "Set up process (link, register) and run user function.
   Returns :noproc if link fails, otherwise runs user function."
  [proc ^Thread pid bound-func args link-to reg-name]
  ;; Set up link before running user code
  (if (and link-to (not (link/link! pid link-to)))
    ;; Link target doesn't exist - fail immediately
    :noproc
    (do
      ;; Register name if requested
      (when reg-name
        (reg/register! reg-name pid))
      
      (trace/trace-event! :spawn {:pid pid :link-to link-to})
      
      ;; Run user function
      (apply bound-func args)
      
      ;; Return :normal for successful completion
      :normal)))

(defn- user-thread-fn!
  "User thread function: runs the user function and handles termination.
   
   Flow:
   1. Add process to table
   2. Set up link if requested (fail with :noproc if target doesn't exist)
   3. Register name if requested
   4. Deliver spawned promise (caller can now proceed)
   5. Bind *current-pid* and run user function
   6. Handle exit and cleanup"
  [proc bound-func args link-to reg-name spawned-promise]
  (let [^Thread pid (Thread/currentThread)
        ex->reason-fn (:ex->reason-fn proc)]
    ;; Add to process table first
    (state/put-proc! pid proc)
    
    ;; Register name if requested (before linking - if registration fails,
    ;; we don't want to notify linked processes)
    (let [reg-ok? (if reg-name
                    (try
                      (reg/register! reg-name pid)
                      true
                      (catch Exception e
                        ;; Registration failed - clean up without notifying anyone
                        ;; (we haven't linked yet, so no exit signals)
                        (reset! (:exit-reason proc) [:registration-failed (ex-message e)])
                        (deliver spawned-promise false)
                        (state/remove-proc! pid)  ;; Remove from table, no cleanup needed
                        false))
                    true)
          ;; Now set up link (after successful registration if any)
          link-ok? (when reg-ok? (or (nil? link-to) (link/link! pid link-to)))]
      (cond
        ;; Registration failed - already cleaned up, just return
        (not reg-ok?)
        nil
        
        ;; Link failed - signal failure and exit
        (not link-ok?)
        (do
          (reset! (:exit-reason proc) :noproc)
          (deliver spawned-promise false)
          (process-cleanup! proc pid :noproc))
        
        ;; Both OK - continue
        :else
        (do
          ;; Signal that spawn is complete
          (deliver spawned-promise true)
          
          (trace/trace-event! :spawn {:pid pid :link-to link-to})
          
          ;; Run user function - no cross-thread context propagation needed
          ;; One process = one thread, period.
          (try
            ;; Run user function and capture result
            (let [result (apply bound-func args)]
              ;; Deliver result to user-result promise (accessible via await on pid)
              (deliver (:user-result proc) result)
              ;; Only set normal exit if exit-reason wasn't already set by an exit signal
              ;; Exit reason is :normal (like Erlang/otplike), result is in user-result
              (let [reason (or @(:exit-reason proc) :normal)]
                (reset! (:exit-reason proc) reason)
                (process-cleanup! proc pid reason)))

            (catch InterruptedException ie
              ;; Could be interrupted by exit signal (reason already set)
              ;; OR user code threw InterruptedException (treat like any exception)
              ;; Merge control context into message context before cleanup
              (when-let [ctrl-ctx @(:last-control-ctx proc)]
                (swap! (:message-context proc) merge ctrl-ctx))
              (let [;; If exit-reason was set by exit signal, use it
                    ;; Otherwise, compute reason from the exception itself
                    reason (or @(:exit-reason proc) (ex->reason-fn ie))]
                (when-not @(:exit-reason proc)
                  (reset! (:exit-reason proc) reason))
                (process-cleanup! proc pid reason)))

            (catch clojure.lang.ExceptionInfo e
              ;; Check for exit exception
              (let [reason (or (::exit/exit-reason (ex-data e))
                               (ex->reason-fn e))]
                (reset! (:exit-reason proc) reason)
                (process-cleanup! proc pid reason)))

            (catch Throwable t
              (let [reason (ex->reason-fn t)]
                (reset! (:exit-reason proc) reason)
                (process-cleanup! proc pid reason)))))))))

(defn spawn-process
  "Internal spawn implementation. Returns pid (Thread), or [pid mon-ref] if monitoring.
   
   Options:
   - :func - the function to run
   - :args - arguments to pass to func
   - :link-to - pid to link to (for spawn-link)
   - :watcher-pid - pid that will receive monitor notifications
   - :monitor? - whether to set up a monitor
   - :reg-name - name to register the process as
   - :flags - map of process flags (e.g., {:trap-exit true})
   - :ex->reason-fn - function to convert exceptions to exit reasons (default: exit/ex->reason)"
  [{:keys [func args link-to watcher-pid monitor? reg-name flags ex->reason-fn]}]
  (let [;; Get parent context before starting new thread
        parent-context (core/message-context)
        proc (make-process (or flags {}) parent-context ex->reason-fn)
        bound-func (bound-fn* func)
        spawned-promise (promise)
        
        ;; Set up monitor ref before starting thread (if needed)
        mon-ref (when monitor?
                  (let [ref-id (state/next-ref!)]
                    ;; Monitor will be added after we have the pid
                    (t/->ref ref-id)))
        
        ;; Start the virtual thread
        ^Thread pid (Thread/startVirtualThread
                     #(user-thread-fn! proc bound-func args link-to reg-name spawned-promise))]
    
    ;; Add monitor now that we have the pid
    (when mon-ref
      (monitor/add-monitor! (t/ref->id mon-ref) watcher-pid pid reg-name))
    
    ;; Wait for process to be ready
    (let [success? @spawned-promise]
      (if success?
        (if mon-ref [pid mon-ref] pid)
        ;; Spawn failed during setup - wait for process to exit and get reason
        (do
          (.join pid)  ;; Wait for thread to finish cleanup
          (let [reason @(:exit-reason proc)]
            (if (and (vector? reason) (= :registration-failed (first reason)))
              ;; Registration failure - throw ex-info with registration details
              (throw (ex-info "registration failed"
                              {:type :already-registered
                               :name reg-name
                               :existing-pid (reg/whereis reg-name)}))
              ;; Other failure
              (throw (ex-info "spawn failed" {:reason reason})))))))))

(defn spawn-process-async
  "Async version of spawn-process. Returns a vfuture that will resolve to pid (or [pid ref]).
   Does not block the caller - process starts in background."
  [opts]
  (vfuture (spawn-process opts)))

(defn spawn-process-with-result
  "Like spawn-process but returns [pid user-result-promise].
   Used by async to get access to the result promise before process terminates."
  [{:keys [func args link-to watcher-pid monitor? reg-name flags ex->reason-fn]}]
  (let [;; Get parent context before starting new thread
        parent-context (core/message-context)
        proc (make-process (or flags {}) parent-context ex->reason-fn)
        bound-func (bound-fn* func)
        spawned-promise (promise)
        user-result-promise (:user-result proc)
        
        ;; Set up monitor ref before starting thread (if needed)
        mon-ref (when monitor?
                  (let [ref-id (state/next-ref!)]
                    (t/->ref ref-id)))
        
        ;; Start the virtual thread
        ^Thread pid (Thread/startVirtualThread
                     #(user-thread-fn! proc bound-func args link-to reg-name spawned-promise))]
    
    ;; Add monitor now that we have the pid
    (when mon-ref
      (monitor/add-monitor! (t/ref->id mon-ref) watcher-pid pid reg-name))
    
    ;; Wait for process to be ready (or fail early with :noproc)
    @spawned-promise
    
    ;; Return pid and user-result promise
    [pid user-result-promise]))
