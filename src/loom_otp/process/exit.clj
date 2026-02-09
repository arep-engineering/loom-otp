(ns loom-otp.process.exit
  "Exit handling: exceptions, reasons, and exit API."
  (:require [loom-otp.registry :as reg]
            [loom-otp.process.core :as core]))

;; =============================================================================
;; Exit Exception
;; =============================================================================

(defn exit-exception
  "Create an exception for process exit."
  [reason]
  (ex-info "process exit" {::exit-reason reason}))

(defn exit-exception?
  "Returns true if e is an exit exception."
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (some? (::exit-reason (ex-data e)))))

(defn exit-reason
  "Extract exit reason from an exit exception, or nil."
  [e]
  (::exit-reason (ex-data e)))

(defn ex->reason
  "Convert an exception to an exit reason.
   If the exception has an ::exit-reason in ex-data, returns that.
   Otherwise returns [:exception e] with the raw Throwable.
   
   For custom exception formatting, use :ex->reason-fn spawn option."
  [^Throwable e]
  (or (exit-reason e)
      [:exception e]))

;; =============================================================================
;; Exit API
;; =============================================================================

(defn exit
  "Exit current process or send exit signal to another.
   
   (exit reason) - exit current process with reason
   (exit pid reason) - send exit signal to pid with current process's context"
  ([reason]
   (throw (exit-exception reason)))
  ([pid reason]
   (let [target-pid (reg/resolve-pid pid)
         ctx (core/message-context)]
     (when target-pid
       (core/send-exit-signal! target-pid (core/self) reason ctx))
     true)))
