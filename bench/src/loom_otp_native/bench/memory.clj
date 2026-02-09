(ns loom-otp.bench.memory
  "Memory and GC benchmarks for loom-otp."
  (:require [loom-otp.process :as proc]
            [loom-otp.process.match :refer [receive!]]
            [loom-otp.process.core :as core]
            [loom-otp.bench.common :as c]
            [mount.lite :as mount]))

;; =============================================================================
;; Memory Benchmarks
;; =============================================================================

(defn memory-baseline
  "Measure baseline memory after system start (no processes)."
  []
  (mount/stop)
  (c/force-gc!)
  (mount/start)
  (let [mem (c/measure-memory-after-gc)]
    (c/println-result "baseline (system started, no procs)" mem "MB")
    mem))

(defn memory-n-idle
  "Spawn N processes that block on receive, measure memory.
   Returns memory per process in KB."
  [n baseline-mb]
  (let [pids (atom [])
        ;; Spawn N processes that just wait for :stop message
        _ (dotimes [_ n]
            (swap! pids conj
                   (proc/spawn!
                     (receive! :stop :done))))
        ;; Let them all start
        _ (Thread/sleep 500)
        mem-after (c/measure-memory-after-gc)
        mem-per-proc-kb (* (/ (- mem-after baseline-mb) n) 1024)]
    (c/println-result (format "idle-procs n=%d" n)
                      mem-after
                      (format "MB (%.2f KB/proc)" mem-per-proc-kb))
    ;; Stop all processes by sending :stop message
    (doseq [pid @pids]
      (core/send pid :stop))
    (Thread/sleep 500)
    mem-per-proc-kb))

(defn memory-spawn-exit-cycles
  "Spawn/exit N processes, repeat for M cycles, measure memory after each.
   Detects memory leaks - memory should return to baseline after GC."
  [n cycles]
  (c/println-subheader (format "Spawn/Exit Cycles (n=%d)" n))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline before cycles" baseline "MB")
    (doseq [cycle (range 1 (inc cycles))]
      ;; Spawn and wait for all to exit
      (let [done (promise)
            counter (atom 0)]
        (dotimes [_ n]
          (proc/spawn!
            (when (= n (swap! counter inc))
              (deliver done true))))
        (deref done 30000 :timeout))
      ;; Force GC and measure
      (let [mem (c/measure-memory-after-gc)]
        (c/println-result (format "after cycle %d" cycle) 
                          mem 
                          (format "MB (delta: %.2f)" (- mem baseline)))))))

(defn memory-message-queue-growth
  "Send N messages to one process without receiving.
   Measures mailbox memory growth."
  [n-messages]
  (let [baseline (c/measure-memory-after-gc)
        receiver (proc/spawn!
                   ;; Just sit there forever
                   (receive! [:done] :done))]
    ;; Send messages from outside process context
    (proc/spawn!
      (dotimes [i n-messages]
        (proc/send receiver [:msg i (byte-array 100)])))
    ;; Wait for sends to complete
    (Thread/sleep 1000)
    (let [mem-after (c/measure-memory-after-gc)
          mem-per-msg-bytes (* (/ (- mem-after baseline) n-messages) 1024 1024)]
      (c/println-result (format "queued-messages n=%d" n-messages)
                        mem-after
                        (format "MB (%.0f bytes/msg)" mem-per-msg-bytes))
      ;; Cleanup - send done message to exit receiver
      (core/send receiver [:done]))))

(defn memory-context-accumulation
  "Send messages with context, check if context accumulates in receiver.
   Each message carries context that might be retained."
  [n-messages]
  (let [baseline (c/measure-memory-after-gc)
        done (promise)
        receiver (proc/spawn!
                   (loop [count 0]
                     (receive!
                       [:done] (deliver done count)
                       [:msg _] (if (< count n-messages)
                                  (recur (inc count))
                                  (deliver done count)))))]
    ;; Send messages with unique context data
    (proc/spawn!
      (dotimes [i n-messages]
        (proc/send receiver [:msg {:context-id i :data (str "context-" i)}]))
      (proc/send receiver [:done]))
    (deref done 30000 :timeout)
    (let [mem-after (c/measure-memory-after-gc)]
      (c/println-result (format "context-accumulation n=%d" n-messages)
                        mem-after
                        (format "MB (delta: %.2f)" (- mem-after baseline))))))

(defn gc-pressure-test
  "Rapid spawn/exit to measure GC pressure.
   Reports time spent and estimates GC overhead."
  [n-spawns]
  (let [start-time (System/currentTimeMillis)
        ;; Spawn/exit rapidly
        _ (dotimes [_ n-spawns]
            (let [done (promise)]
              (proc/spawn! (deliver done true))
              @done))
        elapsed-ms (- (System/currentTimeMillis) start-time)
        ;; Force GC and measure final state
        _ (c/force-gc!)
        final-mem (c/used-memory-mb)]
    (c/println-result (format "gc-pressure n=%d" n-spawns)
                      elapsed-ms
                      (format "ms total (%.2f MB final)" final-mem))))

(defn memory-scaling-test
  "Test memory scaling with increasing process counts.
   Shows if memory per process is constant or grows."
  []
  (c/println-subheader "Memory Scaling")
  (let [baseline (memory-baseline)]
    (doseq [n [100 500 1000 5000 10000 25000 50000]]
      (try
        (memory-n-idle n baseline)
        ;; Cleanup between scales
        (c/force-gc!)
        (Thread/sleep 500)
        (catch Throwable e
          (println (format "FAILED at n=%d: %s" n (.getMessage e)))
          (throw e))))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn- warm-up-memory! []
  (let [baseline (memory-baseline)]
    (doseq [n c/MEMORY-SCALES]
      (memory-n-idle n baseline)
      (c/force-gc!)))
  (gc-pressure-test 10000))

(defn run-all []
  (mount/start)
  (try
    (c/println-header "MEMORY BENCHMARKS")
    
    ;; Warm-up phase
    (c/warm-up! warm-up-memory!)
    
    (let [baseline (memory-baseline)]
      
      (c/println-subheader "Idle Processes")
      (doseq [n c/MEMORY-SCALES]
        (memory-n-idle n baseline)
        (c/force-gc!)
        (Thread/sleep 200)))
    
    (memory-spawn-exit-cycles 10000 5)
    
    (c/println-subheader "Message Queue Growth")
    (doseq [n [1000 10000 100000]]
      (mount/stop)
      (mount/start)
      (memory-message-queue-growth n))
    
    (c/println-subheader "Context Accumulation")
    (mount/stop)
    (mount/start)
    (memory-context-accumulation 10000)
    
    (c/println-subheader "GC Pressure")
    (mount/stop)
    (mount/start)
    (doseq [n [1000 10000 50000]]
      (gc-pressure-test n))
    
    (finally
      (mount/stop))))

(defn run-scaling []
  "Run detailed scaling test (separate from main benchmarks)."
  (mount/start)
  (try
    (c/println-header "MEMORY SCALING TEST")
    (memory-scaling-test)
    (finally
      (mount/stop))))
