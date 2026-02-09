(ns loom-otp.trace
  "Tracing infrastructure for debugging and monitoring.
   
   Set a trace handler to receive events about process lifecycle
   and message passing."
  (:require [loom-otp.state :as state]))

;; =============================================================================
;; Tracing
;; =============================================================================

(defn trace-event!
  "Emit a trace event if handler is set."
  [event-type data]
  (when-let [handler @(state/trace-fn-atom)]
    (try
      (handler (assoc data :event event-type :timestamp (System/currentTimeMillis)))
      (catch Exception _))))

(defn trace
  "Set trace handler function. Handler receives event maps."
  [handler]
  (reset! (state/trace-fn-atom) handler))

(defn untrace
  "Remove trace handler."
  []
  (reset! (state/trace-fn-atom) nil))
