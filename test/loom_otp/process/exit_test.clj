(ns loom-otp.process.exit-test
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
;; Exit Tests
;; =============================================================================

(deftest normal-exit-test
  (testing "process exits normally when function returns"
    (let [result (promise)
          pid (proc/spawn!
               (deliver result :done)
               :normal-return)]
      (is (= :done (deref result 1000 :timeout)))
      (Thread/sleep 50)
      (is (not (proc/alive? pid))))))

(deftest explicit-exit-test
  (testing "exit with reason terminates process"
    (let [started (promise)
          pid (proc/spawn!
               (deliver started true)
               (exit/exit :custom-reason)
               (deliver started :should-not-reach))]
      (is (= true (deref started 1000 :timeout)))
      (Thread/sleep 50)
      (is (not (proc/alive? pid))))))

(deftest exception-exit-test
  (testing "uncaught exception terminates process with exception reason"
    (let [done (promise)
          pid (proc/spawn!
               (deliver done true)
               (throw (ex-info "boom" {})))]
      (deref done 1000 :timeout)
      (Thread/sleep 50)
      (is (not (proc/alive? pid))))))

(deftest exit-other-process-test
  (testing "exit/2 sends exit signal to another process"
    (let [result (promise)
          target-pid (proc/spawn-opt!
                      {:trap-exit true}
                      (match/receive!
                       [:EXIT from reason] (deliver result reason)))]
      (Thread/sleep 50)
      (proc/spawn!
       (exit/exit target-pid :test-signal))
      (is (= :test-signal (deref result 1000 :timeout))))))
