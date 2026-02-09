(ns loom-otp.otplike.gen-server-ns-test
  "Tests for namespace-based gen-server implementation.
   
   Adapted from otplike.gen-server-ns-test with these changes:
   - No core.async (uses promises for synchronization)
   - Uses mount.lite fixtures"
  (:require [clojure.test :refer [is deftest use-fixtures]]
            [clojure.core.match :refer [match]]
            [loom-otp.otplike.process :as process :refer [!]]
            [loom-otp.otplike.test-util :refer :all]
            [loom-otp.otplike.gen-server :as gs]
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
;; Namespace-based gen-server implementation callbacks
;; =============================================================================

(defn init [[n done :as state]]
  (is (= 0 n))
  [:ok state])

(defn terminate [reason [_ done]]
  (is (= :normal reason))
  (deliver done true))

(defn handle-call [request from [n _ :as state]]
  (match request
    :get-async (do
                 (gs/reply from n)
                 [:noreply state])
    :get-sync [:reply n state]
    :stop [:stop :normal :ok state]))

(defn handle-cast [message [n done]]
  (match message
    :dec [:noreply [(dec n) done]]
    :inc [:noreply [(inc n) done]]))

(defn handle-info [message state]
  [:noreply state])

;; =============================================================================
;; Test
;; =============================================================================

(def-proc-test gen-server-ns
  (let [done (promise)]
    (match (gs/start-ns! [[0 done]])
      [:ok pid]
      (do
        (gs/cast pid :inc)
        (is (= 1 (gs/call! pid :get-async)))
        (is (= 1 (gs/call! pid :get-sync)))
        (gs/cast pid :dec)
        (is (= 0 (gs/call! pid :get-async)))
        (is (= 0 (gs/call! pid :get-sync)))
        (is (= :ok (gs/call! pid :stop)))
        (await-completion!! done 50)))))
