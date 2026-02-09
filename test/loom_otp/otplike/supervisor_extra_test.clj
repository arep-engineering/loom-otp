(ns loom-otp.otplike.supervisor-extra-test
  "Extra tests for loom-otp.otplike.supervisor that are NOT in the original otplike test suite.
   
   These tests were added to test loom-otp-specific functionality or cover
   additional cases not present in the original otplike.supervisor-test."
  (:require [clojure.test :refer [is deftest use-fixtures]]
            [clojure.core.match :refer [match]]
            [loom-otp.otplike.process :as process :refer [!]]
            [loom-otp.otplike.test-util :refer :all]
            [loom-otp.otplike.proc-util :as proc-util]
            [loom-otp.otplike.supervisor :as sup]
            [loom-otp.otplike.gen-server :as gs]
            [mount.lite :as mount])
  (:import [loom_otp.otplike.gen_server IGenServer]))

;; Use mount.lite to start/stop the system for each test
(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; One-for-one basic restart test
;; =============================================================================

(def-proc-test ^:parallel one-for-one--child-restart
  (let [init-count (atom 0)
        done (promise)
        child-pid-atom (atom nil)
        sup-flags {:strategy :one-for-one :intensity 5 :period 1000}
        server {:init (fn []
                        (let [n (swap! init-count inc)]
                          ;; Store pid for test to find
                          (reset! child-pid-atom (process/self))
                          (if (= n 3)
                            (deliver done true))
                          [:ok n]))}
        ;; Register the child with the supervisor's id
        children-spec [{:id :child1
                        :start [gs/start-link [:child1 server [] {}]]
                        :restart :permanent}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [] [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:ok sup-pid]
      (do
        ;; Kill the child to trigger restart
        (Thread/sleep 50)
        (when-let [child-pid (process/whereis :child1)]
          (process/exit child-pid :kill))
        (Thread/sleep 50)
        (when-let [child-pid (process/whereis :child1)]
          (process/exit child-pid :kill))
        (is (= [:ok true] (await-completion!! done 500))
            "child must be restarted multiple times")))))

;; =============================================================================
;; Start/terminate/restart/delete child
;; =============================================================================

(def-proc-test ^:parallel start-child--adds-child
  (let [child-started (promise)
        sup-flags {}
        sup-spec [sup-flags []]
        init-fn (fn [] [:ok sup-spec])
        child-spec {:id :dynamic-child
                    :start [gs/start-link [{:init (fn []
                                                    (deliver child-started true)
                                                    [:ok :state])}]]}]
    (match (sup/start-link! init-fn)
      [:ok sup-pid]
      (do
        (match (sup/start-child! sup-pid child-spec)
          [:ok child-pid]
          (do
            (is (process/pid? child-pid)
                "start-child must return child pid")
            (is (= [:ok true] (await-completion!! child-started 50))
                "child must be started")))))))

(def-proc-test ^:parallel terminate-child--stops-child
  (let [child-terminated (promise)
        sup-flags {}
        server {:init (fn [] [:ok :state])
                :terminate (fn [reason _]
                             (deliver child-terminated reason))}
        children-spec [{:id :child1
                        :start [gs/start-link [server [] {:spawn-opt {:flags {:trap-exit true}}}]]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [] [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:ok sup-pid]
      (do
        (Thread/sleep 50)
        (match (sup/terminate-child! sup-pid :child1)
          :ok
          (is (= [:ok :shutdown] (await-completion!! child-terminated 100))
              "child must be terminated with :shutdown reason"))))))

(def-proc-test ^:parallel restart-child--restarts-stopped-child
  (let [init-count (atom 0)
        sup-flags {}
        server {:init (fn []
                        (swap! init-count inc)
                        [:ok :state])}
        children-spec [{:id :child1
                        :start [gs/start-link [server]]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [] [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:ok sup-pid]
      (do
        (Thread/sleep 50)
        (is (= 1 @init-count) "child should be started once")
        (sup/terminate-child! sup-pid :child1)
        (Thread/sleep 50)
        (match (sup/restart-child! sup-pid :child1)
          [:ok _pid]
          (is (= 2 @init-count) "child should be restarted"))))))
