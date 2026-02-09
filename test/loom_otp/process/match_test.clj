(ns loom-otp.process.match-test
  "Tests for receive! and selective-receive! macros."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
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
;; Message Passing Tests
;; =============================================================================

(deftest send-receive-test
  (testing "basic message send and receive"
    (let [result (promise)
          pid (proc/spawn!
               (match/receive!
                [:hello name] (deliver result name)))]
      (core/send pid [:hello "world"])
      (is (= "world" (deref result 1000 :timeout)))))
  
  (testing "send returns true for live process"
    (let [pid (proc/spawn! (Thread/sleep 500))]
      (Thread/sleep 50)
      (is (true? (core/send pid :test)))))
  
  (testing "send returns false for dead process"
    (is (false? (core/send 999999 :test))))
  
  (testing "send to registered name"
    (let [result (promise)
          pid (proc/spawn!
               (proc/register :echo)
               (match/receive!
                [:echo msg] (deliver result msg)))]
      (Thread/sleep 50)
      (is (true? (core/send :echo [:echo "hello"])))
      (is (= "hello" (deref result 1000 :timeout))))))

(deftest receive-timeout-test
  (testing "receive with timeout returns timeout value"
    (let [result (promise)
          pid (proc/spawn!
               (deliver result
                        (match/receive!
                         [:msg x] x
                         (after 100 :timed-out))))]
      (is (= :timed-out (deref result 1000 :timeout)))))
  
  (testing "receive without timeout blocks indefinitely"
    (let [received (promise)
          pid (proc/spawn!
               (match/receive!
                [:msg x] (deliver received x)))]
      ;; Should not complete without message
      (is (= :not-delivered (deref received 100 :not-delivered)))
      ;; Send message
      (core/send pid [:msg :value])
      (is (= :value (deref received 1000 :timeout))))))

(deftest multiple-messages-test
  (testing "messages delivered in FIFO order"
    (let [result (promise)
          pid (proc/spawn!
               (let [msgs (atom [])]
                 (dotimes [_ 5]
                   (match/receive!
                    [:val x] (swap! msgs conj x)))
                 (deliver result @msgs)))]
      (doseq [i (range 5)]
        (core/send pid [:val i]))
      (is (= [0 1 2 3 4] (deref result 1000 :timeout)))))
  
  (testing "pattern matching selects correct clause"
    (let [result (promise)
          pid (proc/spawn!
               (match/receive!
                [:add a b] (deliver result [:add (+ a b)])
                [:sub a b] (deliver result [:sub (- a b)])
                [:mul a b] (deliver result [:mul (* a b)])))]
      (core/send pid [:mul 3 4])
      (is (= [:mul 12] (deref result 1000 :timeout))))))

(deftest receive-no-match-test
  (testing "receive throws on no matching clause"
    (let [result (promise)
          pid (proc/spawn!
               (try
                 (match/receive!
                  [:expected x] x)
                 (catch Exception e
                   (deliver result :no-match))))]
      (core/send pid [:unexpected :data])
      (is (= :no-match (deref result 1000 :timeout))))))

;; =============================================================================
;; Selective Receive Tests
;; =============================================================================

(deftest selective-receive-basic-test
  (testing "selective-receive! finds matching message not at front"
    (let [result (promise)
          pid (proc/spawn!
               ;; Messages will arrive in order: :a, :b, :c
               ;; But we want to receive :b first
               (Thread/sleep 50) ;; Wait for messages
               (let [msg (match/selective-receive!
                          [:target x] x)]
                 (deliver result msg)))]
      ;; Send messages - :target is not first
      (core/send pid :noise1)
      (core/send pid [:other 1])
      (core/send pid [:target :found-it])
      (core/send pid :noise2)
      (is (= :found-it (deref result 1000 :timeout))))))

(deftest selective-receive-preserves-order-test
  (testing "selective-receive! preserves order of non-matching messages"
    (let [result (promise)
          pid (proc/spawn!
               (Thread/sleep 50) ;; Wait for messages
               ;; First, selectively receive :target
               (match/selective-receive!
                [:target] :got-target)
               ;; Now receive remaining in order
               (let [remaining (atom [])]
                 (dotimes [_ 3]
                   (match/receive!
                    msg (swap! remaining conj msg)))
                 (deliver result @remaining)))]
      ;; Send: :a, :b, [:target], :c
      (core/send pid :a)
      (core/send pid :b)
      (core/send pid [:target])
      (core/send pid :c)
      (is (= [:a :b :c] (deref result 1000 :timeout))))))

(deftest selective-receive-timeout-test
  (testing "selective-receive! with timeout returns timeout body"
    (let [result (promise)
          pid (proc/spawn!
               (let [r (match/selective-receive!
                        [:never-matches] :matched
                        (after 100 :timed-out))]
                 (deliver result r)))]
      ;; Send non-matching message
      (core/send pid :something-else)
      (is (= :timed-out (deref result 1000 :timeout))))))

(deftest selective-receive-immediate-match-test
  (testing "selective-receive! returns immediately if match already in queue"
    (let [result (promise)
          started (promise)
          pid (proc/spawn!
               (deliver started true)
               (Thread/sleep 100) ;; Let messages arrive
               (let [start-time (System/currentTimeMillis)
                     msg (match/selective-receive!
                          [:target x] x)
                     end-time (System/currentTimeMillis)]
                 (deliver result {:msg msg :time (- end-time start-time)})))]
      (deref started 500 :timeout)
      ;; Send target message
      (core/send pid [:target :immediate])
      (let [r (deref result 1000 :timeout)]
        (is (= :immediate (:msg r)))
        ;; Should be fast (< 50ms) since message is already there
        (is (< (:time r) 50))))))

(deftest selective-receive-waits-for-new-message-test
  (testing "selective-receive! waits for new matching message"
    (let [result (promise)
          started (promise)
          pid (proc/spawn!
               (deliver started true)
               ;; Start selective receive before message arrives
               (let [msg (match/selective-receive!
                          [:target x] x)]
                 (deliver result msg)))]
      (deref started 500 :timeout)
      (Thread/sleep 50) ;; Process is now waiting
      ;; Send non-matching then matching
      (core/send pid :noise)
      (core/send pid [:target :arrived-later])
      (is (= :arrived-later (deref result 1000 :timeout))))))

(deftest selective-receive-multiple-patterns-test
  (testing "selective-receive! matches any of multiple patterns"
    (let [result (promise)
          pid (proc/spawn!
               (Thread/sleep 50)
               (let [msg (match/selective-receive!
                          [:pattern-a x] [:a x]
                          [:pattern-b x] [:b x])]
                 (deliver result msg)))]
      ;; pattern-b message arrives first in queue, but pattern-a is first in patterns
      ;; Selective receive should find the first MATCH in the queue
      (core/send pid :noise)
      (core/send pid [:pattern-b :from-b])
      (core/send pid [:pattern-a :from-a])
      ;; Should match pattern-b because it's first in queue
      (is (= [:b :from-b] (deref result 1000 :timeout))))))

(deftest selective-receive-zero-timeout-test
  (testing "selective-receive! with timeout 0 checks queue without blocking"
    (let [result (promise)
          pid (proc/spawn!
               (Thread/sleep 50) ;; Let message arrive
               (let [msg (match/selective-receive!
                          [:target x] x
                          (after 0 :nothing))]
                 (deliver result msg)))]
      ;; Don't send any target message
      (core/send pid :noise)
      (is (= :nothing (deref result 1000 :timeout)))))
  
  (testing "selective-receive! with timeout 0 finds existing match"
    (let [result (promise)
          pid (proc/spawn!
               (Thread/sleep 50) ;; Let message arrive
               (let [msg (match/selective-receive!
                          [:target x] x
                          (after 0 :nothing))]
                 (deliver result msg)))]
      (core/send pid [:target :found])
      (is (= :found (deref result 1000 :timeout))))))

;; =============================================================================
;; Concurrency Tests (high volume messaging)
;; =============================================================================

(deftest concurrent-sends-test
  (testing "concurrent sends from multiple processes"
    (let [n 100
          result (promise)
          receiver (proc/spawn!
                    (let [msgs (atom [])]
                      (dotimes [_ n]
                        (match/receive!
                         [:msg i] (swap! msgs conj i)))
                      (deliver result (count @msgs))))]
      (Thread/sleep 50)
      (dotimes [i n]
        (proc/spawn! (core/send receiver [:msg i])))
      (is (= n (deref result 5000 :timeout))))))

(deftest high-frequency-messaging-test
  (testing "high frequency message passing"
    (let [n 10000
          result (promise)
          receiver (proc/spawn!
                    (loop [count 0]
                      (if (= count n)
                        (deliver result count)
                        (do
                          (match/receive! :ping nil)
                          (recur (inc count))))))]
      (Thread/sleep 50)
      (proc/spawn!
       (dotimes [_ n]
         (core/send receiver :ping)))
      (is (= n (deref result 10000 :timeout))))))
