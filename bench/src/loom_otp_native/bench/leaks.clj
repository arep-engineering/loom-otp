(ns loom-otp.bench.leaks
  "Memory leak benchmarks that mimic automation-runtime patterns.
   
   These benchmarks test for memory leaks in patterns commonly used in
   the automation runtime:
   
   1. Timer-based periodic execution (call-on-timer pattern)
   2. Nested async/await calls (in-otp-blocking, defn-async)
   3. Long-running gen_server with trap-exit
   4. Rapid process spawn/exit cycles with links
   5. Binding block retention in recursive spawns"
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.link :as link]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.receive :as recv]
            [loom-otp.process.match :refer [receive! selective-receive!]]
            [loom-otp.gen-server :as gs]
            [loom-otp.timer :as timer]
            [loom-otp.bench.common :as c]
            [mount.lite :as mount]))

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
              (timer/start-timer {:after-ms 10} 
                timer-callback-fn state-atom timer-atom done-promise iterations-left))
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
      (proc/spawn!
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
      (proc/spawn!
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
  (let [result-promise (promise)]
    (proc/spawn!
      (let [parent-pid (core/self)]
        (proc/spawn-opt! {:link true :trap-exit true}
          (try
            (let [result (work-fn)]
              (core/send parent-pid [:result result]))
            (catch Throwable e
              (core/send parent-pid [:error e]))))
        (receive!
          [:result res] (deliver result-promise [:result res])
          [:error err] (deliver result-promise [:exception err])
          [:EXIT pid reason] (deliver result-promise 
                               [:exception (ex-info "Unexpected exit" 
                                                   {:pid pid :reason reason})])
          (after 5000 (deliver result-promise 
                        [:exception (ex-info "Timeout" {})])))))
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

(defn- make-parent-server []
  (reify gs/IGenServer
    (init [_ _args] [:ok {:children (atom [])}])
    
    (handle-call [_ request _from {:keys [children] :as state}]
      (case (first request)
        :spawn-child
        (let [child-pid 
              (proc/spawn-opt! {:link true}
                (receive!
                  :stop :ok
                  (after 60000 :timeout)))]
          (swap! children conj child-pid)
          [:reply :ok state])
        
        :kill-children
        (do
          (doseq [child @children]
            (exit/exit child :shutdown))
          (reset! children [])
          [:reply :ok state])
        
        :get-count
        [:reply (count @children) state]))
    
    (handle-cast [_ _ state] [:noreply state])
    (handle-info [_ _ state] [:noreply state])
    (terminate [_ _reason _state] :ok)))

(defn leak-genserver-with-children
  "Test memory leak from gen_server spawning/monitoring children.
   
   Simulates the automation-runtime pattern where a gen_server
   manages multiple block runtimes as children."
  [n-children n-cycles]
  (c/println-subheader (format "GenServer with Children (children=%d, cycles=%d)" 
                               n-children n-cycles))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Start a parent gen_server
    (let [{:keys [ok error]} (gs/start (make-parent-server) [] {:timeout 10000})
          pid ok]
      
      (when pid
        ;; Cycle: spawn children, kill them, repeat
        (doseq [cycle (range n-cycles)]
          ;; Spawn children (gs/call must be inside process context)
          (let [done (promise)]
            (proc/spawn!
              (dotimes [_ n-children]
                (gs/call pid [:spawn-child]))
              (deliver done true))
            (deref done 10000 :timeout))
          
          (Thread/sleep 100)
          
          ;; Kill all children
          (let [done (promise)]
            (proc/spawn!
              (gs/call pid [:kill-children])
              (deliver done true))
            (deref done 10000 :timeout))
          
          (Thread/sleep 100)
          
          (when (zero? (mod (inc cycle) 5))
            (let [mem (c/measure-memory-after-gc)]
              (c/println-result (format "after cycle %d" (inc cycle)) 
                                mem "MB"))))
        
        ;; Stop the parent
        (exit/exit pid :shutdown))
      
      (Thread/sleep 1000)
      (let [mem-final (c/measure-memory-after-gc)]
        (c/println-result "final" mem-final 
                          (format "MB (delta: %.2f)" (- mem-final baseline)))))))

;; =============================================================================
;; Pattern 4: apply-interval with linked process
;; =============================================================================

(defn leak-apply-interval
  "Test memory leak from interval timers.
   
   start-timer with :every-ms spawns a process with link and trap-exit that
   runs in a loop executing the callback."
  [n-intervals interval-ms duration-ms]
  (c/println-subheader (format "Apply-Interval (n=%d, interval=%dms, duration=%dms)" 
                               n-intervals interval-ms duration-ms))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    ;; Start multiple interval timers
    (let [timers (atom [])
          counters (atom (vec (repeat n-intervals 0)))]
      (proc/spawn!
        (dotimes [i n-intervals]
          (swap! timers conj
                 (timer/start-timer 
                   {:after-ms 0 :every-ms interval-ms}
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
      (proc/spawn!
        (dotimes [i n-intervals]
          (swap! timers conj
                 (timer/start-timer 
                   {:after-ms 0 :every-ms interval-ms}
                   (fn [] (swap! counters update i inc))))))
      
      (Thread/sleep duration-ms)
      
      (doseq [t @timers]
        (timer/cancel t))
      
      (Thread/sleep 1000)
      (let [mem-final (c/measure-memory-after-gc)]
        (c/println-result "after 2nd round" mem-final 
                          (format "MB (delta: %.2f)" (- mem-final baseline)))))))

;; =============================================================================
;; Pattern 5: Binding block retention (the known FIXME in otplike)
;; =============================================================================

(defn leak-ring-spawn
  "Test if binding/closure retention is an issue in loom-otp.
   
   This is the 'ring' pattern that caused problems in otplike:
   spawn chains where bindings from folded binding blocks are stacked
   and not garbage collected.
   
   In loom-otp with virtual threads, this should NOT be a problem."
  [ring-size n-rings]
  (c/println-subheader (format "Ring Spawn (size=%d, rings=%d)" ring-size n-rings))
  (let [baseline (c/measure-memory-after-gc)]
    (c/println-result "baseline" baseline "MB")
    
    (doseq [ring-num (range n-rings)]
      ;; Create a ring of processes that spawn each other
      (let [done (promise)]
        ;; Use a function that creates nested spawns
        (letfn [(ring-proc [n data]
                  (if (pos? n)
                    ;; Spawn next process in ring with accumulated data
                    (proc/spawn! (ring-proc (dec n) (assoc data n (byte-array 1000))))
                    ;; Last in ring - signal done
                    (deliver done true)))]
          (proc/spawn! (ring-proc ring-size {})))
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

(defn- make-block-server [block-id work-interval-ms]
  (reify gs/IGenServer
    (init [_ _args]
      (let [activeness (atom :active)
            timer-ref (atom nil)
            work-count (atom 0)]
        [:ok {:activeness activeness 
              :timer-ref timer-ref
              :work-count work-count
              :block-id block-id}]))
    
    (handle-call [_ _request _from state]
      [:noreply state])
    
    (handle-cast [_ request {:keys [work-count timer-ref] :as state}]
      (case (first request)
        :start-timer
        (let [self-pid (core/self)]
          (reset! timer-ref
                  (timer/start-timer
                    {:after-ms work-interval-ms :every-ms work-interval-ms}
                    (fn [] (gs/cast self-pid [:do-work]))))
          [:noreply state])
        
        :do-work 
        (do
          ;; Simulate nested async work (like in-worker-async)
          (try
            (in-otp-blocking-sim 
              #(do (Thread/sleep 1) (swap! work-count inc)))
            (catch Exception _))
          [:noreply state])
        
        [:noreply state]))
    
    (handle-info [_ _ state] [:noreply state])
    
    (terminate [_ _reason {:keys [activeness timer-ref]}]
      (reset! activeness :stopped)
      (when @timer-ref
        (timer/cancel @timer-ref))
      :ok)))

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
        (proc/spawn!
          (dotimes [i n-blocks]
            (let [{:keys [ok]} (gs/start-link 
                                (make-block-server i work-interval-ms)
                                []
                                {:timeout 5000})]
              (when ok
                (swap! block-pids conj ok)
                ;; Start the timer for this block
                (gs/cast ok [:start-timer])))))
        
        ;; Let blocks run
        (Thread/sleep block-lifetime-ms)
        
        (let [mem-during (c/measure-memory-after-gc)]
          (c/println-result (format "round %d during" round) 
                            mem-during "MB"))
        
        ;; Stop all blocks
        (doseq [pid @block-pids]
          (exit/exit pid :shutdown))
        
        (Thread/sleep 500))
      
      (let [mem-after (c/measure-memory-after-gc)]
        (c/println-result (format "round %d after cleanup" round) 
                          mem-after 
                          (format "MB (delta: %.2f)" (- mem-after baseline)))))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn run-all []
  (mount/start)
  (try
    (c/println-header "MEMORY LEAK BENCHMARKS (Automation Runtime Patterns)")
    
    ;; Give system time to stabilize
    (c/force-gc!)
    (Thread/sleep 1000)
    
    ;; Pattern 1: Timer scheduling
    (leak-timer-scheduling 100 10)
    (mount/stop)
    (mount/start)
    
    ;; Pattern 2: Nested async/await
    (leak-nested-async 500)
    (mount/stop)
    (mount/start)
    
    ;; Pattern 3: GenServer with children
    (leak-genserver-with-children 50 20)
    (mount/stop)
    (mount/start)
    
    ;; Pattern 4: apply-interval
    (leak-apply-interval 20 50 2000)
    (mount/stop)
    (mount/start)
    
    ;; Pattern 5: Ring spawn (binding retention)
    (leak-ring-spawn 100 50)
    (mount/stop)
    (mount/start)
    
    ;; Pattern 6: Full block lifecycle
    (leak-block-lifecycle 10 3000 100)
    
    (c/println-header "LEAK BENCHMARKS COMPLETE")
    
    (finally
      (mount/stop))))

(defn run-quick []
  "Quick leak test for development."
  (mount/start)
  (try
    (c/println-header "QUICK LEAK TEST")
    (c/force-gc!)
    (leak-timer-scheduling 50 5)
    (mount/stop)
    (mount/start)
    (leak-nested-async 100)
    (mount/stop)
    (mount/start)
    (leak-apply-interval 10 100 1000)
    (finally
      (mount/stop))))

(defn run-nested-async-stress []
  "Focused stress test for nested async pattern."
  (mount/start)
  (try
    (c/println-header "NESTED ASYNC STRESS TEST")
    (c/force-gc!)
    (leak-nested-async 1000)
    (c/force-gc!)
    (Thread/sleep 2000)
    (leak-nested-async 1000)
    (c/force-gc!)
    (Thread/sleep 2000)
    (leak-nested-async 1000)
    (finally
      (mount/stop))))
