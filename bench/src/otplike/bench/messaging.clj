(ns otplike.bench.messaging
  "Messaging benchmarks for otplike: send, receive, selective-receive."
  (:require [otplike.process :as process :refer [! proc-fn]]
            [otplike.proc-util :as proc-util]
            [otplike.bench.common :as c]))

;; =============================================================================
;; Basic Messaging Benchmarks
;; =============================================================================

(defn ping-pong
  "N round-trips between two processes.
   Measures message passing latency."
  [n-roundtrips]
  (proc-util/execute-proc!!
    (let [pong (process/spawn
                 (proc-fn []
                   (loop []
                     (process/receive!
                       [:ping from] (do (! from :pong)
                                        (recur))
                       :stop :done))))
          self (process/self)
          start (System/nanoTime)]
      (dotimes [_ n-roundtrips]
        (! pong [:ping self])
        (process/receive! :pong :ok))
      (! pong :stop)
      (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)
            msgs-per-sec (/ (* n-roundtrips 2) (/ elapsed-ms 1000.0))
            latency-us (/ (* elapsed-ms 1000) (* n-roundtrips 2))]
        (c/println-result (format "ping-pong n=%d" n-roundtrips)
                          latency-us
                          (format "us/msg (%.0f msg/sec)" msgs-per-sec))))))

(defn send-throughput
  "Send N messages to one process without waiting for receive.
   Measures raw send rate."
  [n-messages]
  (proc-util/execute-proc!!
    (let [receiver (process/spawn
                     (proc-fn []
                       (process/receive! :done :ok)))
          start (System/nanoTime)]
      (dotimes [i n-messages]
        (! receiver [:msg i]))
      (! receiver :done)
      (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (c/println-result (format "send-throughput n=%d" n-messages)
                          (/ n-messages (/ elapsed-ms 1000.0))
                          "msg/sec")))))

(defn receive-throughput
  "Pre-fill queue with N messages, then receive all.
   Measures raw receive rate."
  [n-messages]
  (let [filled (atom false)
        done (promise)]
    ;; Receiver process
    (let [receiver (process/spawn
                     (proc-fn []
                       ;; Wait for queue to be filled
                       (while (not @filled)
                         (Thread/sleep 10))
                       ;; Time the receives
                       (let [start (System/nanoTime)]
                         (dotimes [_ n-messages]
                           (process/receive! [:msg _] :ok))
                         (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                           (deliver done elapsed-ms)))))]
      ;; Fill the queue
      (proc-util/execute-proc!!
        (dotimes [i n-messages]
          (! receiver [:msg i])))
      ;; Signal filled
      (reset! filled true)
      ;; Wait for result
      (let [elapsed-ms (deref done 120000 :timeout)]
        (if (= elapsed-ms :timeout)
          (c/println-result (format "receive-throughput n=%d" n-messages) 0 "TIMEOUT")
          (c/println-result (format "receive-throughput n=%d" n-messages)
                            (/ n-messages (/ elapsed-ms 1000.0))
                            "msg/sec"))))))

;; =============================================================================
;; Selective Receive Benchmarks
;; =============================================================================

(defn selective-receive-first
  "Selective receive where match is first in queue.
   Best-case scenario for selective receive."
  [queue-size]
  (let [done (promise)]
    (let [receiver (process/spawn
                     (proc-fn []
                       ;; Wait for all messages
                       (Thread/sleep 100)
                       (let [start (System/nanoTime)
                             result (process/selective-receive!
                                      :target :found
                                      (after 5000 :timeout))]
                         (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                           (deliver done elapsed-ms)))))]
      ;; Fill queue: target first, then filler messages
      (proc-util/execute-proc!!
        (! receiver :target)
        (dotimes [i queue-size]
          (! receiver [:filler i])))
      (let [time-ms (deref done 10000 :timeout)]
        (c/println-result (format "selective-recv-first q=%d" queue-size)
                          time-ms
                          "ms")))))

(defn selective-receive-last
  "Selective receive where match is last in queue.
   Worst-case scenario requiring full queue scan."
  [queue-size]
  (let [done (promise)]
    (let [receiver (process/spawn
                     (proc-fn []
                       ;; Wait for all messages
                       (Thread/sleep 100)
                       (let [start (System/nanoTime)
                             result (process/selective-receive!
                                      :target :found
                                      (after 5000 :timeout))]
                         (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                           (deliver done elapsed-ms)))))]
      ;; Fill queue: filler messages first, then target last
      (proc-util/execute-proc!!
        (dotimes [i queue-size]
          (! receiver [:filler i]))
        (! receiver :target))
      (let [time-ms (deref done 10000 :timeout)]
        (c/println-result (format "selective-recv-last q=%d" queue-size)
                          time-ms
                          "ms")))))

(defn selective-receive-miss
  "Selective receive with no match (timeout).
   Measures scan overhead without match."
  [queue-size]
  (let [done (promise)]
    (let [receiver (process/spawn
                     (proc-fn []
                       ;; Wait for messages
                       (Thread/sleep 100)
                       (let [start (System/nanoTime)
                             result (process/selective-receive!
                                      :nonexistent :found
                                      (after 100 :timeout))]
                         (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                           (deliver done elapsed-ms)))))]
      ;; Fill queue with non-matching messages
      (proc-util/execute-proc!!
        (dotimes [i queue-size]
          (! receiver [:filler i])))
      (let [time-ms (deref done 10000 :timeout)]
        (c/println-result (format "selective-recv-miss q=%d (timeout=100ms)" queue-size)
                          time-ms
                          "ms")))))

(defn selective-receive-repeated
  "Repeated selective receives on a stable queue.
   Measures overhead of multiple scans."
  [queue-size n-receives]
  (let [done (promise)]
    (let [receiver (process/spawn
                     (proc-fn []
                       ;; Wait for setup
                       (Thread/sleep 100)
                       ;; Do N selective receives (targets are numbered)
                       (let [start (System/nanoTime)]
                         (dotimes [i n-receives]
                           (process/selective-receive!
                             [:target i] :found
                             (after 5000 :timeout)))
                         (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                           (deliver done elapsed-ms)))))]
      ;; Fill queue: targets interleaved with fillers
      (proc-util/execute-proc!!
        ;; Add all targets first
        (dotimes [i n-receives]
          (! receiver [:target i]))
        ;; Then add filler
        (dotimes [i queue-size]
          (! receiver [:filler i])))
      (let [time-ms (deref done 60000 :timeout)]
        (c/println-result (format "selective-recv-repeated q=%d n=%d" queue-size n-receives)
                          (/ time-ms n-receives)
                          "ms/recv")))))

;; =============================================================================
;; Concurrency Benchmarks
;; =============================================================================

(defn many-to-one
  "N senders each send M messages to 1 receiver.
   Measures mailbox contention."
  [n-senders msgs-each]
  (let [done (promise)
        total-msgs (* n-senders msgs-each)]
    (proc-util/execute-proc!!
      (let [receiver (process/spawn
                       (proc-fn []
                         (let [start (System/nanoTime)]
                           (loop [count 0]
                             (if (< count total-msgs)
                               (do (process/receive! [:msg _ _] :ok)
                                   (recur (inc count)))
                               (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                                 (deliver done elapsed-ms)))))))]
        ;; Spawn senders
        (dotimes [sender-id n-senders]
          (process/spawn
            (proc-fn []
              (dotimes [msg-id msgs-each]
                (! receiver [:msg sender-id msg-id])))))
        ;; Wait for receiver to finish
        (let [time-ms (deref done 60000 :timeout)]
          (c/println-result (format "many-to-one senders=%d msgs-each=%d" n-senders msgs-each)
                            (/ total-msgs (/ time-ms 1000.0))
                            "msg/sec"))))))

(defn one-to-many
  "1 sender sends M messages to each of N receivers.
   Measures fan-out performance."
  [n-receivers msgs-each]
  (let [done (promise)
        counter (atom 0)
        total-msgs (* n-receivers msgs-each)]
    (proc-util/execute-proc!!
      (let [receivers (doall
                        (repeatedly n-receivers
                          #(process/spawn
                             (proc-fn []
                               (dotimes [_ msgs-each]
                                 (process/receive! [:msg _] :ok))
                               (when (= total-msgs (swap! counter + msgs-each))
                                 (deliver done true))))))]
        ;; Single sender - time the sends
        (let [start (System/nanoTime)]
          (dotimes [msg-id msgs-each]
            (doseq [r receivers]
              (! r [:msg msg-id])))
          ;; Wait for all receivers to finish
          (deref done 60000 :timeout)
          (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
            (c/println-result (format "one-to-many receivers=%d msgs-each=%d" n-receivers msgs-each)
                              (/ total-msgs (/ elapsed-ms 1000.0))
                              "msg/sec")))))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn- warm-up-messaging! []
  (doseq [n [1000 10000 100000]]
    (ping-pong n))
  (doseq [n [10000 100000 1000000]]
    (send-throughput n))
  (many-to-one 10 10000))

(defn run-all []
  (c/println-header "MESSAGING BENCHMARKS")
  
  ;; Warm-up phase
  (c/warm-up! warm-up-messaging!)
  
  (c/println-subheader "Ping-Pong (latency)")
  (doseq [n [1000 10000 100000]]
    (ping-pong n))
  
  (c/println-subheader "Send Throughput")
  (doseq [n [10000 100000 1000000]]
    (send-throughput n))
  
  (c/println-subheader "Receive Throughput")
  (doseq [n [10000 100000 1000000]]
    (c/force-gc!)
    (receive-throughput n))
  
  (c/println-subheader "Selective Receive - Best Case (first)")
  (doseq [q [100 1000 10000]]
    (c/force-gc!)
    (selective-receive-first q))
  
  (c/println-subheader "Selective Receive - Worst Case (last)")
  (doseq [q [100 1000 10000]]
    (c/force-gc!)
    (selective-receive-last q))
  
  (c/println-subheader "Selective Receive - Miss (timeout)")
  (doseq [q [100 1000 10000]]
    (c/force-gc!)
    (selective-receive-miss q))
  
  (c/println-subheader "Selective Receive - Repeated")
  (c/force-gc!)
  (selective-receive-repeated 1000 100)
  
  (c/println-subheader "Many-to-One")
  (c/force-gc!)
  (many-to-one 10 10000)
  (c/force-gc!)
  (many-to-one 100 1000)
  
  (c/println-subheader "One-to-Many")
  (c/force-gc!)
  (one-to-many 10 10000)
  (c/force-gc!)
  (one-to-many 100 1000))
