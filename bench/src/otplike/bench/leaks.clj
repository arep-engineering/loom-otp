(ns otplike.bench.leaks
  "Memory leak benchmarks that mimic automation-runtime patterns.
   
   These benchmarks test for memory leaks in patterns commonly used in
   the automation runtime:
   
   1. Timer-based periodic execution (call-on-timer pattern)
   2. Nested async/await calls (in-otp-blocking, defn-async)
   3. Long-running gen_server with trap-exit
   4. Rapid process spawn/exit cycles with links
   5. Binding block retention in recursive spawns"
  (:require [otplike.process :as process :refer [! proc-fn]]
            [otplike.proc-util :as proc-util]
            [otplike.gen-server :as gs]
            [otplike.timer :as timer]
            [otplike.bench.common :as c]))

;; =============================================================================
;; Pattern 1: Timer-based periodic execution (call-on-timer pattern)
;; =============================================================================

(defn- timer-callback-fn
  "Simulates a callback that schedules next timer (like call-on-timer)."
  [state-atom timer-atom done-promise iterations-left]
  (when (pos? @iterations-left)
    (swap! state-atom update :count inc)
    (swap! iterations-left dec)
    (if (pos? @iterations-left)
      ;; Schedule next timer - this is the pattern that might leak
      (reset! timer-atom 
              (timer/apply-after 10 
                #(timer-callback-fn state-atom timer-atom done-promise iterations-left)))
      (deliver done-promise true))))

(defn leak-timer-scheduling
  "Test memory leak from repeated timer scheduling.
   
   Mimics the call-on-timer pattern where each timer callback schedules
   the next timer. This creates chains of processes."
  [n-iterations n-concurrent]
  (c/println-subheader (format "Timer Scheduling (iters=%d, concurrent=%d)" 
                               n-iterations n-concurrent))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Run multiple concurrent timer chains
    (let [done-promises (atom [])]
      (proc-util/execute-proc!!
        (dotimes [_ n-concurrent]
          (let [state-atom (atom {:count 0})
                timer-atom (atom nil)
                done-promise (promise)
                iterations-left (atom n-iterations)]
            (swap! done-promises conj done-promise)
            (timer-callback-fn state-atom timer-atom done-promise iterations-left))))
      
      ;; Wait for all chains to complete
      (doseq [done @done-promises]
        (deref done 60000 :timeout)))
    
    (Thread/sleep 1000)
    (let [mem-after (c/measure-memory-after-gc)]
      (c/println-result "after timer chains" mem-after 
                        (format "MB (delta: %.2f)" (- mem-after baseline))))
    
    ;; Second round - check if memory accumulates
    (let [done-promises (atom [])]
      (proc-util/execute-proc!!
        (dotimes [_ n-concurrent]
          (let [state-atom (atom {:count 0})
                timer-atom (atom nil)
                done-promise (promise)
                iterations-left (atom n-iterations)]
            (swap! done-promises conj done-promise)
            (timer-callback-fn state-atom timer-atom done-promise iterations-left))))
      (doseq [done @done-promises]
        (deref done 60000 :timeout)))
    
    (Thread/sleep 1000)
    (let [mem-final (c/measure-memory-after-gc)]
      (c/println-result "after 2nd round" mem-final 
                        (format "MB (delta from baseline: %.2f)" (- mem-final baseline))))))

;; =============================================================================
;; Pattern 2: Nested async/await (in-otp-blocking pattern)
;; =============================================================================

(defn- in-otp-blocking-sim
  "Simulates the in-otp-blocking pattern:
   - spawn proc-fn-2 which spawns proc-fn-1 with link and trap-exit
   - proc-fn-1 does work and sends result
   - proc-fn-2 waits for result"
  [work-fn]
  (let [result-promise (promise)
        proc-fn-1 (proc-fn [parent-pid]
                    (try
                      (let [result (work-fn)]
                        (! parent-pid [:result result]))
                      (catch Throwable e
                        (! parent-pid [:error e]))))
        proc-fn-2 (proc-fn []
                    (process/spawn-opt proc-fn-1 [(process/self)] 
                                       {:link true :flags {:trap-exit true}})
                    (process/receive!
                      [:result res] (deliver result-promise [:result res])
                      [:error err] (deliver result-promise [:exception err])
                      [:EXIT pid reason] (deliver result-promise 
                                           [:exception (ex-info "Unexpected exit" 
                                                               {:pid pid :reason reason})])
                      (after 5000 (deliver result-promise 
                                    [:exception (ex-info "Timeout" {})]))))]
    (process/spawn proc-fn-2)
    (let [[type value] (deref result-promise 10000 [:exception (ex-info "Deref timeout" {})])]
      (case type
        :result value
        :exception (throw value)))))

(defn leak-nested-async
  "Test memory leak from repeated nested async/await patterns.
   
   Each call spawns 2 processes with links - this tests if process
   cleanup happens correctly."
  [n-calls]
  (c/println-subheader (format "Nested Async/Await (n=%d)" n-calls))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Execute many nested async calls
    (dotimes [i n-calls]
      (try
        (in-otp-blocking-sim #(do (Thread/sleep 1) (* i 2)))
        (catch Exception _)))
    
    (Thread/sleep 1000)
    (let [mem-after (c/measure-memory-after-gc)]
      (c/println-result (format "after %d nested calls" n-calls) 
                        mem-after 
                        (format "MB (delta: %.2f)" (- mem-after baseline))))
    
    ;; Second round
    (dotimes [i n-calls]
      (try
        (in-otp-blocking-sim #(do (Thread/sleep 1) (* i 3)))
        (catch Exception _)))
    
    (Thread/sleep 1000)
    (let [mem-final (c/measure-memory-after-gc)]
      (c/println-result "after 2nd round" mem-final 
                        (format "MB (delta: %.2f)" (- mem-final baseline))))))

;; =============================================================================
;; Pattern 3: Long-running gen_server with trap-exit
;; =============================================================================

(defn leak-genserver-with-children
  "Test memory leak from gen_server spawning/monitoring children.
   
   Simulates the automation-runtime pattern where a gen_server
   manages multiple block runtimes as children."
  [n-children n-cycles]
  (c/println-subheader (format "GenServer with Children (children=%d, cycles=%d)" 
                               n-children n-cycles))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Start a parent gen_server (use start! macro which awaits the async result)
    (let [start-result 
          (proc-util/execute-proc!!
            (gs/start!
              {:init (fn [_] [:ok {:children (atom [])}])
               :handle-call 
               (fn [msg _from {:keys [children] :as state}]
                 (case (first msg)
                   :spawn-child
                   (let [child-pid 
                         (process/spawn-opt
                           (proc-fn []
                             (process/receive!
                               :stop :ok
                               (after 60000 :timeout)))
                           []
                           {:link true})]
                     (swap! children conj child-pid)
                     [:reply :ok state])
                   
                   :kill-children
                   (do
                     (doseq [child @children]
                       (process/exit child :shutdown))
                     (reset! children [])
                     [:reply :ok state])
                   
                   :get-count
                   [:reply (count @children) state]
                   
                   ;; Default case
                   [:reply :unknown state]))
               :handle-info (fn [_msg state] [:noreply state])
               :terminate (fn [_reason _state] :ok)}
              []
              {:spawn-opt {:flags {:trap-exit true}}}))
          [status pid] start-result]
      
      (when (= :ok status)
        ;; Give the gen_server time to fully start
        (Thread/sleep 100)
        ;; Cycle: spawn children, kill them, repeat
        (doseq [cycle (range n-cycles)]
          ;; Spawn children
          (proc-util/execute-proc!!
            (dotimes [_ n-children]
              (gs/call! pid [:spawn-child])))
          
          (Thread/sleep 100)
          
          ;; Kill all children
          (proc-util/execute-proc!!
            (gs/call! pid [:kill-children]))
          
          (Thread/sleep 100)
          
          (when (zero? (mod (inc cycle) 5))
            (let [mem (c/measure-memory-after-gc)]
              (c/println-result (format "after cycle %d" (inc cycle)) 
                                mem "MB"))))
        
        ;; Stop the parent
        (proc-util/execute-proc!!
          (process/exit pid :shutdown)))
      
      (Thread/sleep 1000)
      (let [mem-final (c/measure-memory-after-gc)]
        (c/println-result "final" mem-final 
                          (format "MB (delta: %.2f)" (- mem-final baseline)))))))

;; =============================================================================
;; Pattern 4: apply-interval with linked process
;; =============================================================================

(defn leak-apply-interval
  "Test memory leak from apply-interval timers.
   
   apply-interval spawns a process with link and trap-exit that
   runs in a loop spawning new processes for each callback."
  [n-intervals interval-ms duration-ms]
  (c/println-subheader (format "Apply-Interval (n=%d, interval=%dms, duration=%dms)" 
                               n-intervals interval-ms duration-ms))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Start multiple interval timers
    (let [timers (atom [])
          counters (atom (vec (repeat n-intervals 0)))]
      (proc-util/execute-proc!!
        (dotimes [i n-intervals]
          (swap! timers conj
                 (timer/apply-interval 
                   interval-ms
                   (fn [] (swap! counters update i inc))))))
      
      ;; Let them run
      (Thread/sleep duration-ms)
      
      (let [mem-during (c/measure-memory-after-gc)]
        (c/println-result "during intervals" mem-during 
                          (format "MB (delta: %.2f)" (- mem-during baseline))))
      
      ;; Cancel all timers
      (doseq [t @timers]
        (timer/cancel t))
      
      (Thread/sleep 1000)
      (let [mem-after-cancel (c/measure-memory-after-gc)]
        (c/println-result "after cancel" mem-after-cancel 
                          (format "MB (delta: %.2f, calls: %s)" 
                                  (- mem-after-cancel baseline)
                                  (reduce + @counters)))))
    
    ;; Second round - check accumulation
    (let [timers (atom [])
          counters (atom (vec (repeat n-intervals 0)))]
      (proc-util/execute-proc!!
        (dotimes [i n-intervals]
          (swap! timers conj
                 (timer/apply-interval 
                   interval-ms
                   (fn [] (swap! counters update i inc))))))
      
      (Thread/sleep duration-ms)
      
      (doseq [t @timers]
        (timer/cancel t))
      
      (Thread/sleep 1000)
      (let [mem-final (c/measure-memory-after-gc)]
        (c/println-result "after 2nd round" mem-final 
                          (format "MB (delta: %.2f)" (- mem-final baseline)))))))

;; =============================================================================
;; Pattern 5: Binding block retention (the known FIXME)
;; =============================================================================

(defn leak-ring-spawn
  "Test the known binding block retention issue.
   
   This is the 'ring' pattern mentioned in process.clj FIXME:
   spawn chains where bindings from folded binding blocks are stacked
   and not garbage collected."
  [ring-size n-rings]
  (c/println-subheader (format "Ring Spawn (size=%d, rings=%d)" ring-size n-rings))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    (doseq [ring-num (range n-rings)]
      ;; Create a ring of processes that spawn each other
      (let [done (promise)
            ;; Each process spawns the next in the ring
            ring-proc 
            (fn ring-proc [n data]
              (proc-fn []
                (if (pos? n)
                  ;; Spawn next process in ring with accumulated data
                  (process/spawn (ring-proc (dec n) (assoc data n (byte-array 1000))))
                  ;; Last in ring - signal done
                  (deliver done true))))]
        (process/spawn (ring-proc ring-size {}))
        (deref done 30000 :timeout))
      
      (when (zero? (mod (inc ring-num) 10))
        (let [mem (c/measure-memory-after-gc)]
          (c/println-result (format "after %d rings" (inc ring-num)) 
                            mem "MB"))))
    
    (Thread/sleep 1000)
    (let [mem-final (c/measure-memory-after-gc)]
      (c/println-result "final" mem-final 
                        (format "MB (delta: %.2f)" (- mem-final baseline))))))

;; =============================================================================
;; Pattern 6: Simulate automation block lifecycle
;; =============================================================================

(defn leak-block-lifecycle
  "Simulate the full automation block lifecycle:
   - start-block-runtime creates gen_server with trap-exit
   - call-on-timer schedules periodic work
   - in-worker-async wraps DB calls
   
   This combines patterns to test realistic usage."
  [n-blocks block-lifetime-ms work-interval-ms]
  (c/println-subheader (format "Block Lifecycle (blocks=%d, lifetime=%dms)" 
                               n-blocks block-lifetime-ms))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    (doseq [round [1 2 3]]
      ;; Start blocks
      (let [block-pids (atom [])]
        (proc-util/execute-proc!!
          (dotimes [i n-blocks]
            (let [[status pid]
                  (gs/start!
                    {:init 
                     (fn []
                       (let [self-pid (process/self)
                             activeness (atom :active)
                             timer-ref (atom nil)
                             work-count (atom 0)]
                         ;; Schedule periodic work (like call-on-timer)
                         (letfn [(schedule-work []
                                   (when-not (= :stopped @activeness)
                                     (reset! timer-ref
                                             (timer/apply-after 
                                               work-interval-ms
                                               #(do
                                                  (gs/cast self-pid [:do-work])
                                                  (schedule-work))))))]
                           (schedule-work))
                         [:ok {:activeness activeness 
                               :timer-ref timer-ref
                               :work-count work-count
                               :block-id i}]))
                     
                     :handle-cast
                     (fn [msg {:keys [work-count] :as state}]
                       (case (first msg)
                         :do-work 
                         (do
                           ;; Simulate nested async work (like in-worker-async)
                           (try
                             (in-otp-blocking-sim 
                               #(do (Thread/sleep 1) (swap! work-count inc)))
                             (catch Exception _))
                           [:noreply state])))
                     
                     :terminate
                     (fn [_reason {:keys [activeness timer-ref]}]
                       (reset! activeness :stopped)
                       (when @timer-ref
                         (timer/cancel @timer-ref))
                       :ok)}
                    []
                    {:spawn-opt {:flags {:trap-exit true}}})]
              (when (= :ok status)
                (swap! block-pids conj pid)))))
        
        ;; Let blocks run
        (Thread/sleep block-lifetime-ms)
        
        (let [mem-during (c/measure-memory-after-gc)]
          (c/println-result (format "round %d during" round) 
                            mem-during "MB"))
        
        ;; Stop all blocks
        (proc-util/execute-proc!!
          (doseq [pid @block-pids]
            (process/exit pid :shutdown)))
        
        (Thread/sleep 500))
      
      (let [mem-after (c/measure-memory-after-gc)]
        (c/println-result (format "round %d after cleanup" round) 
                          mem-after 
                          (format "MB (delta: %.2f)" (- mem-after baseline)))))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn run-all []
  (c/println-header "MEMORY LEAK BENCHMARKS (Automation Runtime Patterns)")
  
  ;; Give system time to stabilize
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 1: Timer scheduling
  (leak-timer-scheduling 100 10)
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 2: Nested async/await
  (leak-nested-async 500)
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 3: GenServer with children
  (leak-genserver-with-children 50 20)
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 4: apply-interval
  (leak-apply-interval 20 50 2000)
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 5: Ring spawn (binding retention)
  (leak-ring-spawn 100 50)
  (c/force-gc!)
  (Thread/sleep 1000)
  
  ;; Pattern 6: Full block lifecycle
  (leak-block-lifecycle 10 3000 100)
  
  (c/println-header "LEAK BENCHMARKS COMPLETE"))

(defn run-quick []
  "Quick leak test for development."
  (c/println-header "QUICK LEAK TEST")
  (c/force-gc!)
  (leak-timer-scheduling 50 5)
  (c/force-gc!)
  (leak-nested-async 100)
  (c/force-gc!)
  (leak-apply-interval 10 100 1000))

(defn run-nested-async-stress []
  "Focused stress test for nested async pattern."
  (c/println-header "NESTED ASYNC STRESS TEST")
  (c/force-gc!)
  (leak-nested-async 1000)
  (c/force-gc!)
  (Thread/sleep 2000)
  (leak-nested-async 1000)
  (c/force-gc!)
  (Thread/sleep 2000)
  (leak-nested-async 1000))
