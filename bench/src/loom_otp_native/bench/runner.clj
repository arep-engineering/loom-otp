(ns loom-otp.bench.runner
  "Main entry point for loom-otp benchmarks."
  (:require [loom-otp.bench.common :as c]
            [loom-otp.bench.spawn :as spawn]
            [loom-otp.bench.memory :as memory]
            [loom-otp.bench.messaging :as messaging]
            [loom-otp.bench.leaks :as leaks])
  (:gen-class))

(defn -main [& args]
  (c/print-system-info "loom-otp")
  (println)
  
  (let [mode (first args)]
    (case mode
      "spawn"     (spawn/run-all)
      "memory"    (memory/run-all)
      "messaging" (messaging/run-all)
      "scaling"   (memory/run-scaling)
      "leaks"     (leaks/run-all)
      "leaks-quick" (leaks/run-quick)
      "leaks-nested" (leaks/run-nested-async-stress)
      ;; Default: run all
      (do
        (spawn/run-all)
        (println)
        (memory/run-all)
        (println)
        (messaging/run-all))))
  
  (println)
  (println "========================================")
  (println "loom-otp benchmarks complete")
  (println "========================================")
  (flush)
  (System/exit 0))
