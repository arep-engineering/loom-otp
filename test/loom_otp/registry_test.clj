(ns loom-otp.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.registry :as reg]
            [mount.lite :as mount]))

(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; Registration Tests
;; =============================================================================

(deftest register-whereis-test
  (testing "register and whereis work together"
    (let [result (promise)
          pid (proc/spawn!
               (proc/register :test-server)
               (deliver result (reg/whereis :test-server))
               (Thread/sleep 100))]
      (is (= pid (deref result 1000 :timeout))))))

(deftest register-duplicate-test
  (testing "registering same name twice throws"
    (let [result (promise)
          p1 (proc/spawn!
              (proc/register :unique-name)
              (Thread/sleep 500))]
      (Thread/sleep 50)
      (proc/spawn!
       (try
         (proc/register :unique-name)
         (deliver result :no-error)
         (catch Exception e
           (deliver result :threw))))
      (is (= :threw (deref result 1000 :timeout))))))

(deftest register-two-names-test
  (testing "process can only have one name"
    (let [result (promise)
          pid (proc/spawn!
               (proc/register :first-name)
               (try
                 (proc/register :second-name)
                 (deliver result :no-error)
                 (catch Exception e
                   (deliver result :threw))))]
      (is (= :threw (deref result 1000 :timeout))))))

(deftest unregister-on-exit-test
  (testing "name is unregistered when process exits"
    (let [done (promise)
          pid (proc/spawn!
               (proc/register :temp-server)
               (deliver done true))]
      (deref done 1000 :timeout)
      (Thread/sleep 50)
      (is (nil? (reg/whereis :temp-server))))))

(deftest registered-test
  (testing "registered returns set of all names"
    (let [latch (java.util.concurrent.CountDownLatch. 3)]
      (doseq [name [:server-a :server-b :server-c]]
        (proc/spawn!
         (proc/register name)
         (.countDown latch)
         (Thread/sleep 500)))
      (.await latch 1 java.util.concurrent.TimeUnit/SECONDS)
      (let [names (reg/registered)]
        (is (contains? names :server-a))
        (is (contains? names :server-b))
        (is (contains? names :server-c))))))
