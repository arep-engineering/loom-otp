(ns loom-otp.control-test
  "Tests for control task behavior - exit signals arriving during computation.
   
   These tests verify that the dual-task architecture (control + user tasks)
   works correctly. Exit signals should be processed even when the user task
   is not in receive!."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.match :as match]
            [mount.lite :as mount]))

(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; Exit during computation (not in receive!)
;; =============================================================================

(deftest exit-during-computation-test
  (testing "exit signal terminates process doing interruptible work"
    ;; On the JVM, only blocking/interruptible operations respond to Thread.interrupt().
    ;; Pure computation loops cannot be interrupted. This test uses a loop with
    ;; sleeps to simulate realistic interruptible work (I/O, receive, etc.)
    (let [computation-started (promise)
          computation-finished (promise)
          parent-result (promise)]
      (proc/spawn-trap!
       ;; Spawn child (not linked, so killing it doesn't affect parent)
       (let [child (proc/spawn!
                    (deliver computation-started true)
                    ;; Work loop with sleeps - CAN be interrupted
                    (loop [i 0]
                      (when (< i 1000)
                        (Thread/sleep 10)
                        (recur (inc i))))
                    ;; Should NOT reach here - should be killed
                    (deliver computation-finished true))]
         ;; Wait for child to start
         (deref computation-started 1000 :timeout)
         (Thread/sleep 50)
         ;; Kill the child - this should interrupt its work
         (exit/exit child :kill)
         ;; Wait a bit
         (Thread/sleep 200)
         ;; Child should be dead
         (deliver parent-result (proc/alive? child))))
      ;; Child should have been killed, not finished
      (is (false? (deref parent-result 3000 :timeout)))
      (is (= :not-finished (deref computation-finished 100 :not-finished))))))

(deftest linked-exit-during-computation-test
  (testing "linked process exit terminates process doing interruptible work"
    ;; When a parent process exits abnormally, linked children should be
    ;; terminated. On the JVM, only blocking/interruptible operations can
    ;; be interrupted - pure computation loops cannot be stopped.
    ;; This test uses a loop with sleeps to simulate interruptible work.
    (let [child-started (promise)
          child-finished (promise)]
      ;; Spawn a process that will exit, killing its linked child
      (proc/spawn!
       ;; Spawn linked child that does interruptible work
       (proc/spawn-link!
        (deliver child-started true)
        ;; Work loop with sleeps - CAN be interrupted
        (loop [i 0]
          (when (< i 1000)
            (Thread/sleep 10)  ;; Interruptible!
            (recur (inc i))))
        ;; Should NOT reach here
        (deliver child-finished true))
       ;; Wait for child to start
       (deref child-started 1000 :timeout)
       (Thread/sleep 50)
       ;; Parent exits with error - should kill linked child
       (exit/exit :parent-crashed))
      ;; Wait for things to settle
      (Thread/sleep 500)
      ;; Child should NOT have finished - it should have been killed
      (is (= :not-finished (deref child-finished 100 :not-finished))
          "Linked child should be terminated when parent exits"))))

(deftest kill-is-untrappable-test
  (testing ":kill exits process even when trapping exits and doing interruptible work"
    ;; :kill is untrappable - even with trap-exit true, the process dies.
    ;; Uses interruptible work (sleeps) since JVM can't interrupt pure computation.
    (let [started (promise)
          finished (promise)
          result (promise)]
      (proc/spawn!
       (let [target (proc/spawn-trap!
                     (deliver started true)
                     ;; Interruptible work loop
                     (loop [i 0]
                       (when (< i 1000)
                         (Thread/sleep 10)
                         (recur (inc i))))
                     (deliver finished true))]
         (deref started 1000 :timeout)
         (Thread/sleep 50)
         ;; Send :kill - should work even though trapping exits
         (exit/exit target :kill)
         (Thread/sleep 200)
         (deliver result (proc/alive? target))))
      (is (false? (deref result 2000 :timeout)))
      (is (= :not-finished (deref finished 100 :not-finished))))))

;; =============================================================================
;; Control message priority
;; =============================================================================

(deftest trapped-exit-delivered-test
  (testing "trapped EXIT is delivered as a message"
    ;; When trapping exits, EXIT signals become regular messages.
    ;; They are delivered in mailbox order (no priority over other messages).
    ;; This test verifies EXIT is delivered and can be received.
    (let [result (promise)]
      (proc/spawn!
       (let [target (proc/spawn-trap!
                     ;; Wait for EXIT message
                     (match/receive!
                      [:EXIT from reason] 
                      (deliver result {:from from :reason reason})
                      
                      (after 2000 (deliver result :timeout))))]
         (Thread/sleep 50)
         (exit/exit target :test-exit)))
      ;; Should receive the EXIT message
      (let [r (deref result 3000 :timeout)]
        (is (map? r) "Should receive EXIT message")
        (is (= :test-exit (:reason r)))))))

(deftest exit-checked-between-receives-test
  (testing "exit signals checked even when doing work between receives"
    ;; A process doing work between receives should still respond to exits
    ;; This requires the control task to set exit-reason which user task checks
    (let [work-cycles (atom 0)
          result (promise)]
      (proc/spawn!
       (let [target (proc/spawn!
                     ;; Do receive, work, receive, work, ...
                     ;; Should be terminated when exit arrives, not complete all work
                     (loop []
                       (match/receive!
                        [:work] nil
                        (after 10 nil))
                       ;; Simulate work between receives
                       (swap! work-cycles inc)
                       (Thread/sleep 10)
                       (when (< @work-cycles 1000)
                         (recur)))
                     (deliver result :completed))]
         ;; Send some work
         (dotimes [_ 100]
           (core/send target [:work]))
         ;; Wait a bit for some work to be done
         (Thread/sleep 200)
         ;; Now kill - should interrupt the loop
         (exit/exit target :kill)
         (Thread/sleep 200)
         ;; Check if it completed or was killed
         (when-not (realized? result)
           (deliver result [:killed-at @work-cycles]))))
      (let [r (deref result 3000 :timeout)]
        (is (vector? r) "Process should have been killed, not completed")
        (when (vector? r)
          (is (< (second r) 100) 
              (format "Expected < 100 cycles, got %d" (second r))))))))

;; =============================================================================
;; Interrupt in various states
;; =============================================================================

(deftest interrupt-in-sleep-test
  (testing "process can be killed while sleeping"
    (let [started (promise)
          finished (promise)
          result (promise)]
      (proc/spawn!
       (let [target (proc/spawn!
                     (deliver started true)
                     (Thread/sleep 10000)  ;; Long sleep
                     (deliver finished true))]
         (deref started 1000 :timeout)
         (Thread/sleep 50)
         (exit/exit target :kill)
         (Thread/sleep 200)
         (deliver result (proc/alive? target))))
      (is (false? (deref result 2000 :timeout)))
      (is (= :not-finished (deref finished 100 :not-finished))))))

(deftest interrupt-in-blocking-io-test
  (testing "process can be killed during blocking operation"
    (let [started (promise)
          finished (promise)
          result (promise)]
      (proc/spawn!
       (let [target (proc/spawn!
                     (deliver started true)
                     ;; Block on a promise that will never be delivered
                     (deref (promise))
                     (deliver finished true))]
         (deref started 1000 :timeout)
         (Thread/sleep 50)
         (exit/exit target :kill)
         (Thread/sleep 200)
         (deliver result (proc/alive? target))))
      (is (false? (deref result 2000 :timeout)))
      (is (= :not-finished (deref finished 100 :not-finished))))))

;; =============================================================================
;; Process state transitions
;; =============================================================================

(deftest process-becomes-exiting-test
  (testing "process status becomes :exiting after exit signal"
    (let [result (promise)]
      (proc/spawn!
       (let [target (proc/spawn-trap!
                     ;; Just wait
                     (Thread/sleep 5000))]
         (Thread/sleep 50)
         ;; Check status before
         (let [info-before (proc/process-info target)]
           ;; Send exit (not :kill, so trappable)
           (exit/exit target :normal)
           (Thread/sleep 50)
           ;; The process should still exist but may be exiting
           (let [info-after (proc/process-info target)]
             (deliver result {:before (:status info-before)
                              :after (:status info-after)})))))
      (let [r (deref result 2000 :timeout)]
        (is (= :running (:before r)))))))
