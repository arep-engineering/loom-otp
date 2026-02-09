(ns loom-otp.otplike.timer
  "Compatibility shim providing otplike.timer API backed by loom-otp.timer.
   
   otplike.timer returns channels as timer references.
   This shim wraps loom-otp.timer's Timer records to provide similar semantics."
  (:require [loom-otp.timer :as timer]
            [loom-otp.otplike.process :as process]
            [loom-otp.otplike.util :as u]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]))

;; =============================================================================
;; Timer reference wrapper - provides channel-like cancel semantics
;; =============================================================================

(deftype TimerRef [timer]
  Object
  (toString [_] (str "TimerRef<" (:pid timer) ">")))

(defn- make-timer-ref [timer]
  (TimerRef. timer))

(defn- unwrap-timer [^TimerRef tref]
  (.-timer tref))

;; =============================================================================
;; API
;; =============================================================================

(defn cancel
  "Cancel a timer. Safe to call multiple times."
  [tref]
  (u/check-args [(instance? TimerRef tref)])
  (timer/cancel (unwrap-timer tref))
  nil)

(defn apply-after
  "Apply f to args after msecs. Returns timer reference.
   Exceptions in f are caught and ignored."
  ([msecs f]
   (apply-after msecs f []))
  ([msecs f args]
   (u/check-args [(nat-int? msecs)
                  (fn? f)
                  (vector? args)])
   (make-timer-ref
     (apply timer/start-timer {:after-ms msecs :catch-all true} f args))))

(defn send-after
  "Send message to pid (or self) after msecs. Returns timer reference."
  ([msecs message]
   (u/check-args [(nat-int? msecs)])
   (send-after msecs (process/self) message))
  ([msecs pid message]
   (u/check-args [(nat-int? msecs)
                  (some? pid)])
   (apply-after msecs #(process/! pid message))))

(defn exit-after
  "Send exit signal to pid (or self) after msecs. Returns timer reference."
  ([msecs reason]
   (u/check-args [(nat-int? msecs)])
   (exit-after msecs (process/self) reason))
  ([msecs pid reason]
   (u/check-args [(nat-int? msecs)
                  (some? pid)])
   (apply-after msecs #(when-let [p (process/resolve-pid pid)]
                         (process/exit p reason)))))

(defn kill-after
  "Send :kill exit signal after msecs. Returns timer reference."
  ([msecs]
   (u/check-args [(nat-int? msecs)])
   (kill-after msecs (process/self)))
  ([msecs pid]
   (u/check-args [(nat-int? msecs)
                  (some? pid)])
   (exit-after msecs pid :kill)))

(defn apply-interval
  "Apply f to args repeatedly at interval msecs. Returns timer reference.
   Timer is automatically cancelled when the calling process dies.
   Exceptions in f are caught and ignored (timer continues).
   Must be called from within a process context."
  ([msecs f]
   (apply-interval msecs f []))
  ([msecs f args]
   (u/check-args [(nat-int? msecs)
                  (fn? f)
                  (vector? args)])
   ;; Explicit process context check - required for interval timers (for :link true)
   (when-not (core/try-self)
     (throw (IllegalArgumentException. "apply-interval must be called from process context")))
   (make-timer-ref
     (apply timer/start-timer {:after-ms msecs :every-ms msecs :link true :catch-all true} f args))))

(defn send-interval
  "Send message to pid (or self) repeatedly at interval msecs.
   Returns timer reference.
   Timer is automatically cancelled when the target process dies.
   Must be called from within a process context."
  ([msecs message]
   (u/check-args [(nat-int? msecs)])
   ;; Explicit process context check - required for interval timers
   (when-not (core/try-self)
     (throw (IllegalArgumentException. "send-interval must be called from process context")))
   (send-interval msecs (process/self) message))
  ([msecs pid message]
   (u/check-args [(nat-int? msecs)
                  (some? pid)])
   ;; Also check here for 3-arg arity called directly from outside process
   (when-not (core/try-self)
     (throw (IllegalArgumentException. "send-interval must be called from process context")))
   (if-let [resolved-pid (process/resolve-pid pid)]
     (make-timer-ref
       (timer/start-timer {:after-ms msecs :every-ms msecs :link true :catch-all true}
                          #(process/! resolved-pid message)))
     ;; Target doesn't exist, return dummy ref
     (make-timer-ref (timer/map->Timer {:pid nil :start-time 0 :opts {}})))))
