(ns loom-otp.process-test
  "Core process tests: spawn, self, alive?, processes, process-info, spawn-opt.
   
   See also:
   - exit_test.clj      - Exit handling
   - link_test.clj      - Links and trap-exit
   - monitor_test.clj   - Monitors
   - receive_test.clj   - Message passing and selective receive
   - registry_test.clj  - Process registration
   - vfuture_test.clj   - Virtual futures
   - control_test.clj   - Control task behavior"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.monitor :as monitor]
            [loom-otp.process.match :as match]
            [loom-otp.types :as t]
            [loom-otp.trace :as trace]
            [mount.lite :as mount]))

;; Use mount.lite to start/stop the system for each test
(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; Basic Process Tests
;; =============================================================================

(deftest spawn-test
  (testing "spawn creates a process and runs function"
    (let [result (promise)
          pid (proc/spawn! (deliver result :done))]
      (is (t/pid? pid))
      (is (= :done (deref result 1000 :timeout)))))
  
  (testing "spawn with arguments"
    (let [result (promise)
          pid (proc/spawn (fn [a b c] (deliver result (+ a b c))) 1 2 3)]
      (is (= 6 (deref result 1000 :timeout)))))
  
  (testing "multiple spawns get unique pids"
    (let [pids (atom #{})
          latch (java.util.concurrent.CountDownLatch. 100)]
      (dotimes [_ 100]
        (proc/spawn!
         (swap! pids conj (core/self))
         (.countDown latch)))
      (.await latch 5 java.util.concurrent.TimeUnit/SECONDS)
      (is (= 100 (count @pids))))))

(deftest self-test
  (testing "self returns pid inside process"
    (let [result (promise)
          pid (proc/spawn! (deliver result (core/self)))]
      (is (= pid (deref result 1000 :timeout)))))
  
  (testing "self throws outside process"
    (is (thrown? clojure.lang.ExceptionInfo (core/self)))))

(deftest processes-test
  (testing "processes returns list of running pids"
    (let [started (java.util.concurrent.CountDownLatch. 3)
          pids (atom [])]
      (dotimes [_ 3]
        (swap! pids conj (proc/spawn!
                         (.countDown started)
                         (Thread/sleep 500))))
      (.await started 1 java.util.concurrent.TimeUnit/SECONDS)
      (let [running (set (proc/processes))]
        (is (every? #(contains? running %) @pids))))))

(deftest alive-test
  (testing "alive? returns true for running process"
    (let [started (promise)
          pid (proc/spawn!
               (deliver started true)
               (Thread/sleep 500))]
      (deref started 1000 :timeout)
      (is (proc/alive? pid))))
  
  (testing "alive? returns false for terminated process"
    (let [done (promise)
          pid (proc/spawn! (deliver done true))]
      (deref done 1000 :timeout)
      (Thread/sleep 50)
      (is (not (proc/alive? pid)))))
  
  (testing "alive? returns false for unknown pid"
    (is (false? (proc/alive? 999999)))))

;; =============================================================================
;; Process Info Tests
;; =============================================================================

(deftest process-info-test
  (testing "process-info returns complete process details"
    (let [info-result (promise)
          pid (proc/spawn-opt!
               {:trap-exit true :reg-name :info-test}
               ;; Create some links and monitors
               (let [other (proc/spawn-link! (Thread/sleep 1000))]
                 (monitor/monitor other)
                 ;; Send self a message to have non-empty queue
                 (core/send (core/self) :dummy-msg)
                 (deliver info-result (proc/process-info (core/self)))
                 (Thread/sleep 100)))]
      (let [info (deref info-result 1000 :timeout)]
        (is (= pid (:pid info)))
        (is (= :info-test (:registered-name info)))
        (is (= true (get-in info [:flags :trap-exit])))
        (is (= :running (:status info)))
        (is (pos? (:message-queue-len info)))
        (is (seq (:links info)))
        (is (seq (:monitors info)))))))

(deftest process-info-nil-for-unknown-test
  (testing "process-info returns nil for unknown pid"
    (is (nil? (proc/process-info 999999)))))

;; =============================================================================
;; Spawn-opt Tests
;; =============================================================================

(deftest spawn-opt-link-test
  (testing "spawn-opt with :link option"
    (let [result (promise)
          parent (proc/spawn-opt!
                  {:trap-exit true}
                  (proc/spawn-opt! {:link true} (exit/exit :child-exit))
                  (match/receive!
                   [:EXIT _ reason] (deliver result reason)
                   (after 500 (deliver result :no-exit))))]
      (is (= :child-exit (deref result 1000 :timeout))))))

(deftest spawn-opt-monitor-test
  (testing "spawn-opt with :monitor option returns [pid ref]"
    (let [result (promise)
          parent (proc/spawn!
                  (let [[child-pid ref] (proc/spawn-opt! {:monitor true} (exit/exit :done))]
                    (match/receive!
                     [:DOWN r :process _ reason]
                     (deliver result {:ref-match (= r ref) :reason reason}))))]
      (let [r (deref result 1000 :timeout)]
        (is (:ref-match r))
        (is (= :done (:reason r)))))))

(deftest spawn-opt-registered-test
  (testing "spawn-opt with :reg-name option"
    (let [started (promise)
          pid (proc/spawn!
               (proc/spawn-opt!
                {:reg-name :my-server}
                (deliver started (core/self))
                (Thread/sleep 100)))]
      (let [child-pid (deref started 1000 :timeout)]
        (is (= child-pid (loom-otp.registry/whereis :my-server)))))))

(deftest spawn-opt-trap-exit-test
  (testing "spawn-opt with :trap-exit sets the flag"
    (let [result (promise)
          pid (proc/spawn-opt!
               {:trap-exit true}
               (deliver result (get-in (proc/process-info (core/self)) [:flags :trap-exit])))]
      (is (= true (deref result 1000 :timeout))))))

;; =============================================================================
;; Concurrent Spawn Tests
;; =============================================================================

(deftest concurrent-spawn-link-test
  (testing "concurrent spawn-link operations"
    (let [n 50
          parent-done (promise)
          parent (proc/spawn-opt!
                  {:trap-exit true}
                  (let [children (doall (for [_ (range n)]
                                          (proc/spawn-link! :done)))]
                    ;; Wait for all exit messages
                    ;; Exit reason is :normal (result is in user-result promise)
                    (dotimes [_ n]
                      (match/receive!
                       [:EXIT _ :normal] nil
                       (after 1000 nil)))
                    (deliver parent-done true)))]
      (is (true? (deref parent-done 5000 :timeout))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest empty-function-test
  (testing "process running empty function exits normally"
    (let [done (promise)]
      (proc/spawn!
       (let [[target ref] (proc/spawn-monitor! nil)]
         (match/receive!
          [:DOWN r :process _ reason] (when (= r ref)
                                        (deliver done reason)))))
      ;; Exit reason is :normal (result is in user-result promise)
      (is (= :normal (deref done 1000 :timeout))))))

(deftest deeply-nested-data-test
  (testing "deeply nested data can be sent and received"
    (let [deep-data {:a {:b {:c {:d {:e {:f {:g 42}}}}}}}
          result (promise)
          pid (proc/spawn!
               (match/receive!
                data (deliver result data)))]
      (core/send pid deep-data)
      (is (= deep-data (deref result 1000 :timeout))))))

(deftest large-message-test
  (testing "large messages can be sent"
    (let [large-data (vec (range 100000))
          result (promise)
          pid (proc/spawn!
               (match/receive!
                data (deliver result (count data))))]
      (core/send pid large-data)
      (is (= 100000 (deref result 5000 :timeout))))))

;; =============================================================================
;; Tracing Tests
;; =============================================================================

(deftest trace-events-test
  (testing "trace handler receives events"
    (let [events (atom [])
          _ (trace/trace (fn [e] (swap! events conj (:event e))))
          done (promise)
          pid (proc/spawn!
               (match/receive!
                :msg (deliver done true)))]
      (core/send pid :msg)
      (deref done 1000 :timeout)
      (Thread/sleep 100)
      (trace/untrace)
      (is (contains? (set @events) :spawn))
      (is (contains? (set @events) :send))
      (is (contains? (set @events) :exit)))))

;; =============================================================================
;; Dynamic Variable Propagation Tests
;; =============================================================================

(def ^:dynamic *test-dynamic-var* :root-value)

(deftest dynamic-var-propagation-test
  (testing "user dynamic bindings are preserved in spawned process"
    (let [result (promise)]
      (binding [*test-dynamic-var* :parent-value]
        (proc/spawn!
         (deliver result *test-dynamic-var*)))
      (is (= :parent-value (deref result 1000 :timeout)))))
  
  (testing "nested spawn preserves dynamic bindings"
    (let [result (promise)]
      (binding [*test-dynamic-var* :grandparent-value]
        (proc/spawn!
         (proc/spawn!
          (deliver result *test-dynamic-var*))))
      (is (= :grandparent-value (deref result 1000 :timeout)))))
  
  (testing "dynamic bindings work with spawn-link"
    (let [result (promise)]
      (binding [*test-dynamic-var* :linked-parent-value]
        (proc/spawn!
         (proc/spawn-link!
          (deliver result *test-dynamic-var*))
         ;; Wait for child to deliver before parent exits (which would kill linked child)
         (deref result 1000 :timeout)))
      (is (= :linked-parent-value (deref result 1000 :timeout)))))
  
  (testing "dynamic bindings work with spawn-trap!"
    (let [result (promise)]
      (binding [*test-dynamic-var* :opt-parent-value]
        (proc/spawn!
         (proc/spawn-trap!
          (deliver result *test-dynamic-var*))))
      (is (= :opt-parent-value (deref result 1000 :timeout))))))
