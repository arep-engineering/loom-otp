(ns loom-otp.timer
  "Timer functions for delayed and periodic operations.
   
   Timers are implemented as processes:
   - One-shot timers: unlinked process that sleeps then executes
   - Interval timers: linked process that loops, stopping when caller dies
   
   All timer functions return a Timer record that can be passed to cancel."
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.receive :as recv]))

;; =============================================================================
;; Timer Record
;; =============================================================================

(defrecord Timer [pid start-time last-fire-time opts])

;; =============================================================================
;; Core Timer Function
;; =============================================================================

(defn start-timer
  "Create and start a timer. Returns a Timer record.
   
   Options:
   - :after-ms  - ms before first fire (default: 0)
   - :every-ms  - ms between fires (nil = one-shot)
   - :link      - link to caller (default: true if :every-ms, false otherwise)
   - :catch-all - catch exceptions in f and continue (default: false)
   
   Examples:
   (start-timer {:after-ms 1000} f)                      ; one-shot after 1s
   (start-timer {:after-ms 1000 :every-ms 1000} f)       ; every 1s
   (start-timer {:after-ms 0 :every-ms 1000} f)          ; immediately, then every 1s
   (start-timer {:after-ms 1000 :link true} f x y)       ; one-shot, linked
   (start-timer {:every-ms 1000 :catch-all true} f)      ; interval, survives exceptions"
  [{:keys [after-ms every-ms link catch-all] :or {after-ms 0} :as opts} f & args]
  (let [link? (if (some? link) link (some? every-ms))
        start-time (System/currentTimeMillis)
        last-fire (atom nil)
        spawn-opts (if link? {:link true :trap-exit true} {})
        ;; Wrap f with try-catch if :catch-all is true
        wrapped-f (if catch-all
                    (fn [& args]
                      (try
                        (apply f args)
                        (catch Throwable _
                          ;; Swallow exception and continue
                          nil)))
                    f)]
    (map->Timer
      {:pid (proc/spawn-opt! spawn-opts
              (loop [next-fire (+ (System/currentTimeMillis) after-ms)]
                (let [sleep-time (- next-fire (System/currentTimeMillis))]
                  (when (= ::timeout (recv/receive! (max 0 sleep-time) ::timeout))
                    (reset! last-fire (System/currentTimeMillis))
                    (apply wrapped-f args)
                    (when every-ms
                      (recur (+ next-fire every-ms)))))))
       :start-time start-time
       :last-fire-time last-fire
       :opts opts})))

;; =============================================================================
;; Timer Management
;; =============================================================================

(defn cancel
  "Cancel a timer. Returns true if timer process existed, false otherwise."
  [timer]
  (core/send (:pid timer) :cancel))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn tc
  "Time constant - convert time unit to milliseconds.
   (tc 5 :seconds) => 5000
   (tc 2 :minutes) => 120000"
  [n unit]
  (case unit
    (:ms :milliseconds) n
    (:s :sec :seconds) (* n 1000)
    (:min :minutes) (* n 60000)
    (:hr :hours) (* n 3600000)
    (throw (ex-info "Unknown time unit" {:unit unit}))))
