(ns loom-otp.process.link
  "Bidirectional links between processes for fault propagation.
   Pids are Thread objects."
  (:require [loom-otp.state :as state]))

;; =============================================================================
;; Internal Link Operations
;; =============================================================================

(defn link!
  "Create bidirectional link between new-pid and existing-pid.
   new-pid is the process being spawned, existing-pid is the link target.
   Returns true if link established, false if existing-pid doesn't exist.
   
   Links are stored in each process's :links atom (set of linked Threads)."
  [^Thread new-pid ^Thread existing-pid]
  (let [existing-proc (state/get-proc existing-pid)]
    (if existing-proc
      (do
        ;; Add link to existing process first
        (swap! (:links existing-proc) conj new-pid)
        ;; Add link to new process
        (when-let [new-proc (state/get-proc new-pid)]
          (swap! (:links new-proc) conj existing-pid))
        true)
      false)))

(defn unlink!
  "Remove bidirectional link between two pids.
   Removes from other process first, then from self."
  [^Thread pid-a ^Thread pid-b]
  ;; Remove from other process first
  (when-let [proc-b (state/get-proc pid-b)]
    (swap! (:links proc-b) disj pid-a))
  ;; Remove from self
  (when-let [proc-a (state/get-proc pid-a)]
    (swap! (:links proc-a) disj pid-b)))

(defn get-links
  "Returns set of linked Threads for given pid."
  [^Thread pid]
  (when-let [proc (state/get-proc pid)]
    @(:links proc)))

(defn cleanup-links!
  "Remove all links for pid. Called during process cleanup."
  [^Thread pid]
  (when-let [proc (state/get-proc pid)]
    (let [linked-pids @(:links proc)]
      ;; Remove this pid from all linked processes
      (doseq [^Thread other-pid linked-pids]
        (when-let [other-proc (state/get-proc other-pid)]
          (swap! (:links other-proc) disj pid))))))
