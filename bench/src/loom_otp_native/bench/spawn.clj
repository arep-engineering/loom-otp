(ns loom-otp.bench.spawn
  "Spawn benchmarks for loom-otp."
  (:require [loom-otp.process :as proc]
            [loom-otp.process.match :refer [receive!]]
            [loom-otp.bench.common :as c]
            [mount.lite :as mount]))

;; =============================================================================
;; Spawn Benchmarks
;; =============================================================================

(defn spawn-n-immediate-exit
  "Spawn N processes that exit immediately (return :done).
   Measures raw spawn throughput without synchronization."
  [n]
  (let [time-ms (c/time-ms
                  (dotimes [_ n]
                    (proc/spawn! :done)))]
    (c/println-result (format "spawn-immediate-exit n=%d" n)
                      (/ n (/ time-ms 1000.0))
                      "proc/sec")))

(defn spawn-n-async
  "Spawn N processes asynchronously, fire-and-forget.
   Measures pure async spawn throughput without waiting for completion."
  [n]
  (let [time-ms (c/time-ms
                  (->> (range n)
                       (mapv (fn [_] (proc/spawn-async! :done)))))]
    (c/println-result (format "spawn-async n=%d" n)
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
                    (proc/spawn!
                      (when (= n (swap! counter inc))
                        (deliver done true))))
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
                      (proc/spawn! (deliver done true))
                      (deref done 1000 :timeout))))]
    (c/println-result (format "spawn-sequential n=%d" n)
                      (/ time-ms n)
                      "ms/spawn")))

(defn spawn-tree
  "Create binary tree of processes, depth D -> 2^(D+1)-1 processes.
   Each node spawns two children, waits for results, reports sum to parent."
  [depth]
  (letfn [(node [parent d]
            (proc/spawn!
              (if (zero? d)
                (proc/send parent 1)
                (let [self (proc/self)]
                  (node self (dec d))
                  (node self (dec d))
                  (receive! n1
                    (receive! n2
                      (proc/send parent (+ 1 n1 n2))))))))]
    (let [result (promise)
          time-ms (c/time-ms
                    (proc/spawn!
                      (node (proc/self) depth)
                      (receive! total (deliver result total)))
                    (deref result 120000 :timeout))
          total @result]
      (c/println-result (format "spawn-tree depth=%d procs=%d" depth total)
                        (/ total (/ time-ms 1000.0))
                        "proc/sec"))))

(defn spawn-link-tree
  "Same as spawn-tree but with linked processes.
   Measures link setup overhead."
  [depth]
  (letfn [(node [parent d]
            (proc/spawn-link!
              (if (zero? d)
                (proc/send parent 1)
                (let [self (proc/self)]
                  (node self (dec d))
                  (node self (dec d))
                  (receive! n1
                    (receive! n2
                      (proc/send parent (+ 1 n1 n2))))))))]
    (let [result (promise)
          time-ms (c/time-ms
                    (proc/spawn!
                      (node (proc/self) depth)
                      (receive! total (deliver result total)))
                    (deref result 120000 :timeout))
          total @result]
      (c/println-result (format "spawn-link-tree depth=%d procs=%d" depth total)
                        (/ total (/ time-ms 1000.0))
                        "proc/sec"))))

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
  (mount/start)
  (try
    (c/println-header "SPAWN BENCHMARKS")
    
    ;; Warm-up phase
    (c/warm-up! warm-up-spawn!)
    
    (c/println-subheader "Immediate Exit (fire-and-forget)")
    (doseq [n c/SPAWN-SCALES]
      (spawn-n-immediate-exit n))
    
    (c/println-subheader "Async Spawn")
    (doseq [n c/SPAWN-SCALES]
      (spawn-n-async n))
    
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
      (spawn-link-tree d))
    
    (finally
      (mount/stop))))
