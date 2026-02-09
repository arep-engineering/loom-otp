(ns loom-otp.process.monitor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.monitor :as monitor]
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
;; Monitor Tests
;; =============================================================================

(deftest monitor-basic-test
  (testing "monitor receives DOWN when target exits"
    (let [result (promise)]
      (proc/spawn!
       (let [[target ref] (proc/spawn-monitor! (exit/exit :target-exit))]
         (match/receive!
          [:DOWN r :process _ reason] (when (= r ref)
                                        (deliver result {:ref r :reason reason})))))
      (let [r (deref result 1000 :timeout)]
        (is (= :target-exit (:reason r)))))))

(deftest monitor-nonexistent-test
  (testing "monitor nonexistent process sends immediate DOWN"
    (let [result (promise)
          pid (proc/spawn!
               (let [ref (monitor/monitor 999999)]
                 (match/receive!
                  [:DOWN r :process _ reason]
                  (deliver result {:ref r :reason reason}))))]
      (let [r (deref result 1000 :timeout)]
        (is (= :noproc (:reason r)))))))

(deftest monitor-by-name-test
  (testing "monitor by registered name"
    (let [result (promise)
          target (proc/spawn!
                  (proc/register :target-server)
                  (Thread/sleep 100)
                  (exit/exit :bye))]
      (Thread/sleep 50)
      (proc/spawn!
       (let [ref (monitor/monitor :target-server)]
         (match/receive!
          [:DOWN r :process name reason]
          (deliver result {:name name :reason reason}))))
      (let [r (deref result 1000 :timeout)]
        (is (= :target-server (:name r)))
        (is (= :bye (:reason r)))))))

(deftest multiple-monitors-test
  (testing "multiple monitors to same target all receive DOWN"
    (let [results (atom [])
          latch (java.util.concurrent.CountDownLatch. 3)
          target (proc/spawn! (Thread/sleep 200) (exit/exit :target-done))]
      (dotimes [i 3]
        (proc/spawn!
         (let [ref (monitor/monitor target)]
           (match/receive!
            [:DOWN _ :process _ reason]
            (do
              (swap! results conj reason)
              (.countDown latch))))))
      (.await latch 2 java.util.concurrent.TimeUnit/SECONDS)
      (is (= [:target-done :target-done :target-done] @results)))))

(deftest demonitor-test
  (testing "demonitor prevents DOWN message"
    (let [result (promise)
          pid (proc/spawn!
               (let [target (proc/spawn! (Thread/sleep 200))
                     ref (monitor/monitor target)]
                 (monitor/demonitor ref)
                 (exit/exit target :kill)
                 (match/receive!
                  [:DOWN _ _ _ _] (deliver result :got-down)
                  (after 300 (deliver result :no-down)))))]
      (is (= :no-down (deref result 1000 :timeout))))))

(deftest monitor-vs-link-test
  (testing "monitor doesn't cause watcher to die"
    (let [result (promise)]
      (proc/spawn!
       (let [[target ref] (proc/spawn-monitor! (exit/exit :crash))]
         (match/receive!
          [:DOWN r :process _ _] (when (= r ref) nil))
         (deliver result :still-alive)))
      (is (= :still-alive (deref result 1000 :timeout))))))
