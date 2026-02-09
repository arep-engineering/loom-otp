(ns otplike.bench.runner
  "Main entry point for otplike benchmarks."
  (:require [otplike.bench.common :as c]
            [otplike.bench.spawn :as spawn]
            [otplike.bench.memory :as memory]
            [otplike.bench.messaging :as messaging]
            [otplike.bench.leaks :as leaks]
            [loom-otp.core :as otp])
  (:gen-class))

(defn -main [& args]
  ;; Start loom-otp system (required for otplike compat layer)
  (otp/start!)
  (c/print-system-info "otplike via loom-otp compat")
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
  (println "otplike via loom-otp compat benchmarks complete")
  (println "========================================")
  (flush)
  (otp/stop!)
  (System/exit 0))
