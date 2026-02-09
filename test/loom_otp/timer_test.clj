(ns loom-otp.timer-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.match :as match]
            [loom-otp.timer :as timer]
            [mount.lite :as mount]))

(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; One-shot Timer Tests
;; =============================================================================

(deftest one-shot-timer-test
  (testing "one-shot timer fires after delay"
    (let [result (promise)
          start (System/currentTimeMillis)]
      (proc/spawn!
        (timer/start-timer {:after-ms 100} core/send (core/self) :delayed-msg)
        (match/receive!
          :delayed-msg (deliver result (- (System/currentTimeMillis) start))))
      (let [elapsed (deref result 2000 :timeout)]
        (is (number? elapsed))
        (is (>= elapsed 90))
        (is (<= elapsed 500)))))
  
  (testing "one-shot timer returns Timer record"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 1000} core/send (core/self) :msg)]
          (deliver result (instance? loom_otp.timer.Timer timer))
          (timer/cancel timer)))
      (is (true? (deref result 2000 :timeout)))))
  
  (testing "one-shot timers with different delays fire in order"
    (let [result (promise)]
      (proc/spawn!
        (timer/start-timer {:after-ms 50} core/send (core/self) [:msg 1])
        (timer/start-timer {:after-ms 100} core/send (core/self) [:msg 2])
        (timer/start-timer {:after-ms 150} core/send (core/self) [:msg 3])
        (let [msgs (atom [])]
          (dotimes [_ 3]
            (match/receive!
              [:msg n] (swap! msgs conj n)
              (after 1000 nil)))
          (deliver result @msgs)))
      (is (= [1 2 3] (deref result 2000 :timeout)))))
  
  (testing "one-shot timer is not linked by default"
    (let [result (promise)
          timer-fired (atom false)]
      (proc/spawn!
        ;; Spawn a child that creates a timer then dies
        (proc/spawn!
          (timer/start-timer {:after-ms 100} reset! timer-fired true))
        ;; Wait for timer to fire even though creator died
        (Thread/sleep 300)
        (deliver result @timer-fired))
      (is (true? (deref result 2000 :timeout))))))

;; =============================================================================
;; Interval Timer Tests
;; =============================================================================

(deftest interval-timer-test
  (testing "interval timer fires periodically"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 50 :every-ms 50} core/send (core/self) :tick)]
          (let [ticks (atom 0)]
            (dotimes [_ 3]
              (match/receive!
                :tick (swap! ticks inc)
                (after 500 nil)))
            (timer/cancel timer)
            (deliver result @ticks))))
      (is (>= (deref result 2000 :timeout) 3))))
  
  (testing "interval timer is linked to caller by default"
    (let [result (promise)
          tick-count (atom 0)]
      (proc/spawn!
        ;; Spawn a child that creates an interval timer then dies
        (proc/spawn!
          (timer/start-timer {:after-ms 50 :every-ms 50} swap! tick-count inc)
          ;; Die after a short time
          (Thread/sleep 100))
        ;; Wait and check that timer stopped when child died
        (Thread/sleep 100)
        (let [count-after-death @tick-count]
          (Thread/sleep 200)
          ;; Timer should have stopped, count should not increase much
          (deliver result (<= @tick-count (+ count-after-death 1)))))
      (is (true? (deref result 2000 :timeout)))))
  
  (testing "interval timer can be unlinked"
    (let [result (promise)
          tick-count (atom 0)
          timer-holder (atom nil)]
      (proc/spawn!
        ;; Spawn a child that creates an unlinked interval timer then dies
        (proc/spawn!
          (reset! timer-holder 
                  (timer/start-timer {:after-ms 50 :every-ms 50 :link false} 
                                     swap! tick-count inc))
          ;; Die immediately
          nil)
        ;; Wait and check that timer continues after child died
        (Thread/sleep 300)
        (timer/cancel @timer-holder)
        (deliver result (>= @tick-count 3)))
      (is (true? (deref result 2000 :timeout))))))

;; =============================================================================
;; Cancel Tests
;; =============================================================================

(deftest cancel-test
  (testing "cancel prevents timer from firing"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 200} core/send (core/self) :should-not-receive)]
          (timer/cancel timer)
          (match/receive!
            :should-not-receive (deliver result :received)
            (after 400 (deliver result :not-received)))))
      (is (= :not-received (deref result 2000 :timeout)))))
  
  (testing "cancel returns true for active timer"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 1000} core/send (core/self) :msg)]
          (deliver result (timer/cancel timer))))
      (is (true? (deref result 2000 :timeout)))))
  
  (testing "cancel returns false for already-fired timer"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 50} core/send (core/self) :msg)]
          (match/receive! :msg nil (after 200 nil))
          (Thread/sleep 100)  ;; Ensure timer process has exited
          (deliver result (timer/cancel timer))))
      (is (false? (deref result 2000 :timeout)))))
  
  (testing "cancel stops interval timer"
    (let [result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer {:after-ms 50 :every-ms 50} core/send (core/self) :tick)]
          (match/receive! :tick nil (after 200 nil))  ;; Wait for first tick
          (timer/cancel timer)
          (match/receive!
            :tick (deliver result :received-after-cancel)
            (after 300 (deliver result :properly-cancelled)))))
      (is (= :properly-cancelled (deref result 2000 :timeout))))))

;; =============================================================================
;; Function Application Tests
;; =============================================================================

(deftest function-application-test
  (testing "timer calls function with arguments"
    (let [result (promise)]
      (timer/start-timer {:after-ms 100} deliver result :done)
      (is (= :done (deref result 2000 :timeout)))))
  
  (testing "timer calls function with multiple arguments"
    (let [result (promise)]
      (timer/start-timer {:after-ms 100}
                         (fn [a b c] (deliver result (+ a b c)))
                         1 2 3)
      (is (= 6 (deref result 2000 :timeout)))))
  
  (testing "exception in timer function crashes timer process"
    ;; Timer process crashes, but doesn't affect caller (one-shot is unlinked)
    (let [crashed (promise)
          timer (timer/start-timer {:after-ms 50} 
                                   (fn [] (throw (ex-info "boom" {}))))]
      (Thread/sleep 200)
      ;; Timer process should have died
      (is (false? (proc/alive? (:pid timer)))))))

;; =============================================================================
;; Precise Timing Tests
;; =============================================================================

(deftest precise-timing-test
  (testing "interval timer maintains precise timing despite slow function"
    (let [fire-times (atom [])
          result (promise)]
      (proc/spawn!
        (let [timer (timer/start-timer 
                      {:after-ms 100 :every-ms 100}
                      (fn []
                        (swap! fire-times conj (System/currentTimeMillis))
                        (Thread/sleep 30)))]  ;; Function takes 30ms
          (Thread/sleep 450)  ;; Should fire ~4 times
          (timer/cancel timer)
          (deliver result @fire-times)))
      (let [times (deref result 2000 :timeout)]
        ;; Should have at least 3 fires
        (is (>= (count times) 3))
        ;; Check intervals are close to 100ms despite 30ms execution time
        (when (>= (count times) 2)
          (let [intervals (map - (rest times) times)]
            (doseq [interval intervals]
              ;; Allow some slack but should be closer to 100 than 130
              (is (< interval 120)))))))))

;; =============================================================================
;; Time Constant Tests
;; =============================================================================

(deftest tc-test
  (testing "tc converts time units"
    (is (= 1000 (timer/tc 1 :seconds)))
    (is (= 1000 (timer/tc 1 :sec)))
    (is (= 1000 (timer/tc 1 :s)))
    (is (= 60000 (timer/tc 1 :minutes)))
    (is (= 60000 (timer/tc 1 :min)))
    (is (= 3600000 (timer/tc 1 :hours)))
    (is (= 3600000 (timer/tc 1 :hr)))
    (is (= 100 (timer/tc 100 :milliseconds)))
    (is (= 100 (timer/tc 100 :ms))))
  
  (testing "tc throws on unknown unit"
    (is (thrown? Exception (timer/tc 1 :unknown)))))
