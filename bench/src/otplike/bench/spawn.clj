(ns otplike.bench.spawn
  "Spawn benchmarks for otplike."
  (:require [otplike.process :as process :refer [! proc-fn proc-defn]]
            [otplike.proc-util :as proc-util]
            [otplike.bench.common :as c]))

;; =============================================================================
;; Spawn Benchmarks
;; =============================================================================

(defn spawn-n-immediate-exit
  "Spawn N processes that exit immediately (return :done).
   Measures raw spawn throughput without synchronization."
  [n]
  (let [time-ms (c/time-ms
                  (dotimes [_ n]
                    (process/spawn (proc-fn [] :done))))]
    (c/println-result (format "spawn-immediate-exit n=%d" n)
                      (/ n (/ time-ms 1000.0))
                      "proc/sec")))

(defn spawn-n-wait-exit
  "Spawn N processes, wait for all to exit using counter.
   Measures spawn + exit coordination throughput."
  [n]
  (let [done (promise)
        counter (atom 0)
        time-ms (c/time-ms
                  (dotimes [_ n]
                    (process/spawn
                      (proc-fn []
                        (when (= n (swap! counter inc))
                          (deliver done true)))))
                  (deref done 60000 :timeout))]
    (c/println-result (format "spawn-wait-exit n=%d" n)
                      (/ n (/ time-ms 1000.0))
                      "proc/sec")))

(defn spawn-sequential
  "Spawn one process, wait for it to exit, repeat N times.
   Measures spawn latency (round-trip time)."
  [n]
  (let [time-ms (c/time-ms
                  (dotimes [_ n]
                    (let [done (promise)]
                      (process/spawn
                        (proc-fn []
                          (deliver done true)))
                      (deref done 1000 :timeout))))]
    (c/println-result (format "spawn-sequential n=%d" n)
                      (/ time-ms n)
                      "ms/spawn")))

;; Process tree node - defined at top level to avoid go block context issues
(proc-defn tree-node [parent]
  (process/receive!
    [:spread 1]
    (! parent [:result 1])
    
    [:spread n]
    (let [self (process/self)
          msg [:spread (dec n)]]
      (! (process/spawn tree-node [self]) msg)
      (! (process/spawn tree-node [self]) msg)
      (process/receive!
        [:result r1]
        (process/receive!
          [:result r2] (! parent [:result (+ 1 r1 r2)]))))))

(defn spawn-tree
  "Create binary tree of processes, depth D -> 2^(D+1)-1 processes.
   Each node spawns two children, waits for results, reports sum to parent."
  [depth]
  (let [result (proc-util/execute-proc!!
                 (let [start (System/nanoTime)]
                   (! (process/spawn tree-node [(process/self)]) [:spread depth])
                   (process/receive!
                     [:result total]
                     {:total total
                      :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})))]
    (c/println-result (format "spawn-tree depth=%d procs=%d" depth (:total result))
                      (/ (:total result) (/ (:elapsed-ms result) 1000.0))
                      "proc/sec")))

;; Linked process tree node
(proc-defn link-tree-node [parent]
  (process/receive!
    [:spread 1]
    (! parent [:result 1])
    
    [:spread n]
    (let [self (process/self)
          msg [:spread (dec n)]]
      (! (process/spawn-link link-tree-node [self]) msg)
      (! (process/spawn-link link-tree-node [self]) msg)
      (process/receive!
        [:result r1]
        (process/receive!
          [:result r2] (! parent [:result (+ 1 r1 r2)]))))))

(defn spawn-link-tree
  "Same as spawn-tree but with linked processes.
   Measures link setup overhead."
  [depth]
  (let [result (proc-util/execute-proc!!
                 (let [start (System/nanoTime)]
                   (! (process/spawn-link link-tree-node [(process/self)]) [:spread depth])
                   (process/receive!
                     [:result total]
                     {:total total
                      :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})))]
    (c/println-result (format "spawn-link-tree depth=%d procs=%d" depth (:total result))
                      (/ (:total result) (/ (:elapsed-ms result) 1000.0))
                      "proc/sec")))

;; =============================================================================
;; Runner
;; =============================================================================

(defn- warm-up-spawn! []
  (doseq [n c/SPAWN-SCALES]
    (spawn-n-immediate-exit n))
  (doseq [n c/SPAWN-SCALES]
    (spawn-n-wait-exit n))
  (doseq [d c/TREE-DEPTHS]
    (spawn-tree d)))

(defn run-all []
  (c/println-header "SPAWN BENCHMARKS")
  
  ;; Warm-up phase
  (c/warm-up! warm-up-spawn!)
  
  (c/println-subheader "Immediate Exit (fire-and-forget)")
  (doseq [n c/SPAWN-SCALES]
    (spawn-n-immediate-exit n))
  
  (c/println-subheader "Wait for Exit (synchronized)")
  (doseq [n c/SPAWN-SCALES]
    (spawn-n-wait-exit n))
  
  (c/println-subheader "Sequential (latency)")
  (doseq [n c/SPAWN-SCALES-SMALL]
    (spawn-sequential n))
  
  (c/println-subheader "Process Tree")
  (doseq [d c/TREE-DEPTHS]
    (spawn-tree d))
  
  (c/println-subheader "Linked Process Tree")
  (doseq [d c/TREE-DEPTHS]
    (spawn-link-tree d)))
