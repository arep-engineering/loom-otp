(ns loom-otp.core
  "System lifecycle management.
   
   Usage:
   (require '[loom-otp.core :as otp])
   
   ;; Start the system first
   (otp/start!)
   
   ;; Main API - loom-otp.process provides most things:
   ;; - spawn, spawn-link, spawn-opt - create processes
   ;; - self, send - current process and messaging
   ;; - exit - terminate processes
   ;; - link, unlink - bidirectional links
   ;; - monitor, demonitor - unidirectional monitors  
   ;; - receive!, selective-receive! - receive messages (functions)
   ;; - alive?, processes, process-info, register
   
   ;; For pattern-matching receive:
   ;; - loom-otp.process.match for receive!, selective-receive! macros
   
   ;; For virtual thread futures:
   ;; - loom-otp.vfuture for vfuture macro
   
   ;; Behaviors:
   ;; - loom-otp.gen-server for gen_server behavior
   ;; - loom-otp.supervisor for supervisor behavior
   ;; - loom-otp.timer for timers
   
   ;; Stop when done
   (otp/stop!)"
  (:require [loom-otp.state :as state]
            [mount.lite :as mount]))

;; =============================================================================
;; System Lifecycle
;; =============================================================================

(defn start!
  "Start the loom-otp system. Must be called before using processes."
  []
  (mount/start))

(defn stop!
  "Stop the loom-otp system, terminating all processes and timers."
  []
  (mount/stop))

(defn status
  "Get the status of all mount states."
  []
  (mount/status))

;; Re-export with-session for parallel testing
(defmacro with-session
  "Create a new isolated session for parallel testing.
   All states are independent within this session."
  [& body]
  `(mount/with-session ~@body))

;; =============================================================================
;; Testing Utilities
;; =============================================================================

(def reset-all! state/reset-all!)
