(ns loom-otp.otplike.process-extra-test
  "Extra tests for loom-otp.otplike.process that are NOT in the original otplike test suite.
   
   These tests were added to test loom-otp-specific functionality or cover
   additional cases not present in the original otplike.process-test."
  (:require [clojure.test :refer [is deftest use-fixtures]]
            [loom-otp.otplike.process :as process :refer [proc-fn]]
            [loom-otp.otplike.test-util :refer :all]
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
;; registered - Extra test not in original otplike
;; =============================================================================

(deftest registered-returns-registered-names
  (let [n1 (uuid-keyword)
        n2 (uuid-keyword)
        n3 (uuid-keyword)
        registered #{n1 n2 n3}
        done (promise)
        pfn (proc-fn [] (await-completion!! done 50))]
    (process/spawn-opt pfn {:register n1})
    (process/spawn-opt pfn {:register n2})
    (process/spawn-opt pfn {:register n3})
    (is (= registered (into #{} (filter registered (process/registered))))
        "registered must return registered names")
    (deliver done true)))
