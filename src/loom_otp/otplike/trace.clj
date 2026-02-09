(ns loom-otp.otplike.trace
  "Compatibility shim providing otplike.trace API backed by loom-otp."
  (:require [loom-otp.otplike.process :as process]))

;; =============================================================================
;; Predicates
;; =============================================================================

(defn- pid=? [pid {:keys [pid] :as _event}]
  (= pid pid))

(defn- reg-name=? [reg-name {:keys [reg-name] :as _event}]
  (= reg-name reg-name))

(defn- kind=? [kind {:keys [kind] :as _event}]
  (= kind kind))

(defn crashed?
  "Check if event represents a crash (abnormal exit)."
  [{:keys [kind extra]}]
  (and (= kind :exiting)
       (not (#{:normal :shutdown} (:reason extra)))))

;; =============================================================================
;; API
;; =============================================================================

(defn pid
  "Trace events for a specific pid."
  [pid handler]
  (process/trace (partial pid=? pid) handler))

(defn reg-name
  "Trace events for a specific registered name."
  [reg-name handler]
  (process/trace (partial reg-name=? reg-name) handler))

(defn kind
  "Trace events of a specific kind."
  [kind handler]
  (process/trace (partial kind=? kind) handler))

(defn crashed
  "Trace crash events."
  [handler]
  (process/trace crashed? handler))
