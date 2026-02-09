(ns loom-otp.process.link-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
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
;; Link Tests
;; =============================================================================

(deftest spawn-link-test
  (testing "spawn-link creates linked process"
    (let [child-died (promise)
          parent-died (promise)
          parent-pid (proc/spawn!
                      (proc/spawn-link!
                       (exit/exit :child-exit))
                      (Thread/sleep 500)
                      (deliver parent-died false))]
      (Thread/sleep 200)
      (is (not (proc/alive? parent-pid)) "parent should die when linked child exits"))))

(deftest link-bidirectional-test
  (testing "spawn-link creates link that propagates exit"
    ;; Simpler test - just verify child exit kills parent
    (let [result (promise)
          parent (proc/spawn!
                  (proc/spawn-link!
                   (Thread/sleep 50)
                   (exit/exit :child-exit))
                  ;; Parent should die when child exits
                  (Thread/sleep 1000)
                  (deliver result :parent-survived))]
      ;; If parent survives, result will be :parent-survived
      ;; If parent dies, we get :timeout
      (is (= :timeout (deref result 500 :timeout))))))

;; Note: explicit link/unlink API was removed. Links are only created via spawn-link.

(deftest exit-propagation-chain-test
  (testing "exit propagates through linked processes"
    (let [result (promise)]
      (proc/spawn-opt!
       {:trap-exit true}
       ;; Create a linked child that itself has a linked grandchild
       (let [child (proc/spawn-link!
                    (let [grandchild (proc/spawn-link!
                                      (Thread/sleep 5000))]
                      (Thread/sleep 5000)))]
         (Thread/sleep 100)
         ;; Kill child - grandchild should also die
         (exit/exit child :kill)
         ;; We should receive EXIT from child
         (match/receive!
          [:EXIT from reason] (deliver result {:from from :child child})
          (after 1000 (deliver result :no-exit)))))
      (let [r (deref result 2000 :timeout)]
        (is (= (:child r) (:from r)))))))

;; =============================================================================
;; Trap Exit Tests
;; =============================================================================

(deftest trap-exit-test
  (testing "trap-exit converts exit signals to messages"
    (let [result (promise)
          parent (proc/spawn-opt!
                  {:trap-exit true}
                  (let [child (proc/spawn-link!
                               (exit/exit :child-reason))]
                    (match/receive!
                     [:EXIT pid reason] (deliver result {:pid pid :reason reason}))))]
      (let [r (deref result 1000 :timeout)]
        (is (= :child-reason (:reason r)))))))

(deftest trap-exit-normal-test
  (testing "normal exit is ignored without trap-exit"
    (let [result (promise)
          parent (proc/spawn!
                  (proc/spawn-link! :normal)
                  (Thread/sleep 200)
                  (deliver result :survived))]
      (is (= :survived (deref result 1000 :timeout)))))
  
  (testing "normal exit is delivered with trap-exit"
    (let [result (promise)
          parent (proc/spawn-opt!
                  {:trap-exit true}
                  (proc/spawn-link! :normal)
                  (match/receive!
                   [:EXIT _ reason] (deliver result reason)
                   (after 500 (deliver result :no-exit))))]
      ;; Exit reason is :normal (result is in user-result promise, not exit reason)
      (is (= :normal (deref result 1000 :timeout))))))

(deftest kill-ignores-trap-test
  (testing ":kill reason ignores trap-exit flag"
    (let [result (promise)]
      (proc/spawn!
       (let [target (proc/spawn-opt! {:trap-exit true} (Thread/sleep 5000))]
         (Thread/sleep 50)
         (exit/exit target :kill)
         (Thread/sleep 100)
         (deliver result (proc/alive? target))))
      (is (false? (deref result 2000 :timeout))))))
