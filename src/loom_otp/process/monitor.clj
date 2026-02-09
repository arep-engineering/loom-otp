(ns loom-otp.process.monitor
  "Unidirectional monitors for observing process termination.
   Pids are Thread objects."
  (:require [loom-otp.types :as t]
            [loom-otp.state :as state]
            [loom-otp.context-mailbox :as cmb]
            [loom-otp.registry :as reg]
            [loom-otp.process.core :as core])
  (:import [java.util.concurrent ConcurrentHashMap]))

;; =============================================================================
;; Internal Monitor Operations
;; =============================================================================

(defn add-monitor!
  "Add a monitor. Internal use."
  [^long ref-id ^Thread watcher-pid ^Thread target-pid target-name]
  (.put (state/monitors) ref-id {:ref-id ref-id
                                  :watcher watcher-pid
                                  :target target-pid
                                  :target-name target-name}))

(defn remove-monitor!
  "Remove a monitor by ref-id. Internal use."
  [^long ref-id]
  (.remove (state/monitors) ref-id))

(defn get-monitors-by-target
  "Returns seq of monitor-info maps for monitors watching target-pid."
  [^Thread target-pid]
  (->> (state/monitors)
       (.values)
       (filter (fn [m] (= (:target m) target-pid)))))

(defn get-monitors-by-watcher
  "Returns seq of monitor-info maps for monitors owned by watcher-pid."
  [^Thread watcher-pid]
  (->> (state/monitors)
       (.values)
       (filter (fn [m] (= (:watcher m) watcher-pid)))))

(defn cleanup-monitors-for-watcher!
  "Remove all monitors owned by watcher. Called during process cleanup."
  [^Thread watcher-pid]
  (let [^ConcurrentHashMap monitors (state/monitors)]
    (doseq [{:keys [ref-id]} (get-monitors-by-watcher watcher-pid)]
      (.remove monitors ref-id))))

(defn notify-monitors!
  "Notify all monitors watching pid that it has terminated.
   dying-ctx is the context from the dying process, passed along for tracing."
  [^Thread pid reason dying-ctx]
  (let [^ConcurrentHashMap monitors (state/monitors)
        watching (get-monitors-by-target pid)]
    (doseq [{:keys [ref-id watcher target-name]} watching]
      (when-let [watcher-proc (state/get-proc watcher)]
        (let [ref (t/->ref ref-id)]
          (cmb/send! (:mailbox watcher-proc) dying-ctx [:DOWN ref :process target-name reason])))
      (.remove monitors ref-id))))

;; =============================================================================
;; Public Monitor API
;; =============================================================================

(defn monitor
  "Monitor target process. Returns TRef.
   When target terminates, watcher receives [:DOWN ref :process target reason].
   Must be called from within a process context."
  [target]
  (let [ref-id (state/next-ref!)
        ref (t/->ref ref-id)
        ^Thread self-pid (core/self)  ;; Will throw if not in process
        ^Thread target-pid (reg/resolve-pid target)]
    (if (and target-pid (core/process-exists? target-pid))
      (do
        (add-monitor! ref-id self-pid target-pid target)
        ;; Double-check for race
        (when-not (core/process-exists? target-pid)
          (remove-monitor! ref-id)
          (core/send self-pid [:DOWN ref :process target :noproc]))
        ref)
      ;; Doesn't exist - immediate DOWN
      (do
        (core/send self-pid [:DOWN ref :process target :noproc])
        ref))))

(defn demonitor
  "Remove monitor."
  ([ref] (demonitor ref []))
  ([ref _opts]
   (when (t/ref? ref)
     (remove-monitor! (t/ref->id ref)))
   true))
