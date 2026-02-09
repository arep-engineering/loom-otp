(ns loom-otp.vfuture-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.vfuture :as vf]
            [mount.lite :as mount]))

(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; VFuture Tests
;; =============================================================================

(deftest vfuture-basic-test
  (testing "vfuture executes body and deref returns result"
    (let [result (promise)
          pid (proc/spawn!
               (let [f (vf/vfuture (+ 1 2 3))]
                 (deliver result @f)))]
      (is (= 6 (deref result 1000 :timeout)))))
  
  (testing "vfuture runs on separate thread"
    (let [result (promise)
          pid (proc/spawn!
               (let [main-thread (Thread/currentThread)
                     f (vf/vfuture (Thread/currentThread))]
                 (deliver result (not= main-thread @f))))]
      (is (true? (deref result 1000 :timeout))))))

(deftest vfuture-concurrent-test
  (testing "multiple vfuture operations run concurrently"
    (let [result (promise)
          pid (proc/spawn!
               (let [start (System/currentTimeMillis)
                     ;; Start 3 vfuture operations that each sleep 100ms
                     f1 (vf/vfuture (Thread/sleep 100) :a)
                     f2 (vf/vfuture (Thread/sleep 100) :b)
                     f3 (vf/vfuture (Thread/sleep 100) :c)
                     ;; Deref all
                     results [@f1 @f2 @f3]
                     elapsed (- (System/currentTimeMillis) start)]
                 ;; Should complete in ~100ms, not 300ms
                 (deliver result {:results results :elapsed elapsed})))]
      (let [r (deref result 2000 :timeout)]
        (is (= [:a :b :c] (:results r)))
        ;; Allow some slack, but should be much less than 300ms
        (is (< (:elapsed r) 250))))))

(deftest vfuture-exception-test
  (testing "deref rethrows exception when vfuture throws"
    (let [result (promise)
          pid (proc/spawn!
               (let [f (vf/vfuture (throw (ex-info "test error" {:code 42})))]
                 (try
                   @f
                   (deliver result :no-exception)
                   (catch Exception e
                     (deliver result {:caught true
                                      :code (-> e ex-data :code)})))))]
      (let [r (deref result 1000 :timeout)]
        (is (:caught r))
        (is (= 42 (:code r)))))))

(deftest vfuture-timeout-test
  (testing "vfuture deref with timeout returns timeout value"
    (let [result (promise)
          pid (proc/spawn!
               (let [f (vf/vfuture (Thread/sleep 1000) :slow)]
                 (deliver result (deref f 50 :timed-out))))]
      (is (= :timed-out (deref result 1000 :timeout))))))

(deftest vfuture-realized-test
  (testing "realized? returns false for pending vfuture"
    (let [result (promise)
          pid (proc/spawn!
               (let [f (vf/vfuture (Thread/sleep 500) :done)]
                 (deliver result (realized? f))))]
      (is (false? (deref result 1000 :timeout)))))
  
  (testing "realized? returns true for completed vfuture"
    (let [result (promise)
          pid (proc/spawn!
               (let [f (vf/vfuture :immediate)]
                 @f  ;; Wait for completion
                 (Thread/sleep 10)
                 (deliver result (realized? f))))]
      (is (true? (deref result 1000 :timeout))))))
