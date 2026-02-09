(ns loom-otp.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.core :as otp]
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

(deftest basic-spawn-test
  (testing "spawn and message passing via core namespace"
    (let [result (promise)
          pid (proc/spawn!
               (match/receive!
                [:ping from] (core/send from [:pong (core/self)])))]
      (proc/spawn!
       (core/send pid [:ping (core/self)])
       (match/receive!
        [:pong sender-pid] (deliver result sender-pid)))
      (is (= pid (deref result 1000 :timeout))))))
