(ns loom-otp.otplike.proc-util
  "Compatibility shim for otplike.proc-util.
   
   In otplike, execute-proc creates a process context using go blocks.
   In loom-otp, we use virtual threads directly via vfuture."
  (:require [loom-otp.otplike.process :as process]
            [loom-otp.vfuture :as vf]
            [loom-otp.process :as proc]))

;; =============================================================================
;; API
;; =============================================================================

(defmacro ^:no-doc current-line-number []
  (:line (meta &form)))

(defmacro execute-proc
  "Executes body in a newly created process context. Returns a promise
   that will receive the result [:ok res] or [:ex exception]."
  [& body]
  `(let [result# (promise)]
     (proc/spawn!
       (try
         (let [res# (do ~@body)]
           (deliver result# [:ok res#]))
         (catch Throwable t#
           (deliver result# [:ex t#]))))
     result#))

(defmacro execute-proc!
  "Executes body in process context. Blocks waiting for result."
  [& body]
  `(let [[tag# res#] @(execute-proc ~@body)]
     (case tag#
       :ok res#
       :ex (throw res#))))

(defmacro execute-proc!!
  "Same as execute-proc! - blocks waiting for result."
  [& body]
  `(execute-proc! ~@body))

(defmacro defn-proc
  "Define a function that runs body in a process context."
  [fname args & body]
  `(defn ~fname []
     (let [result# (promise)]
       (proc/spawn!
         (try
           (let [res# (do ~@body)]
             (when (some? res#) (deliver result# res#)))
           (catch Throwable t#
             (deliver result# nil))))
       @result#)))
