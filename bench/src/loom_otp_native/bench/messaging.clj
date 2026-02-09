(ns loom-otp.bench.messaging
  "Messaging benchmarks for loom-otp: send, receive, selective-receive."
  (:require [loom-otp.process :as proc]
            [loom-otp.process.match :refer [receive! selective-receive!]]
            [loom-otp.process.receive :as recv]
            [loom-otp.bench.common :as c]
            [mount.lite :as mount]))

;; =============================================================================
;; Basic Messaging Benchmarks
;; =============================================================================

(defn ping-pong
  "N round-trips between two processes.
   Measures message passing latency."
  [n-roundtrips]
  (let [done (promise)
        pong (proc/spawn!
               (loop []
                 (receive!
                   [:ping from] (do (proc/send from :pong)
                                    (recur))
                   :stop :done)))
        time-ms (c/time-ms
                  (proc/spawn!
                    (let [self (proc/self)]
                      (dotimes [_ n-roundtrips]
                        (proc/send pong [:ping self])
                        (receive! :pong :ok))
                      (proc/send pong :stop)
                      (deliver done true)))
                  (deref done 60000 :timeout))
        msgs-per-sec (/ (* n-roundtrips 2) (/ time-ms 1000.0))
        latency-us (/ (* time-ms 1000) (* n-roundtrips 2))]
    (c/println-result (format "ping-pong n=%d" n-roundtrips)
                      latency-us
                      (format "us/msg (%.0f msg/sec)" msgs-per-sec))))

(defn send-throughput
  "Send N messages to one process without waiting for receive.
   Measures raw send rate."
  [n-messages]
  (let [done (promise)
        receiver (proc/spawn!
                   ;; Just wait for done signal
                   (receive! :done (deliver done true)))]
    ;; Time only the sending (inside a process to use proc/send)
    (proc/spawn!
      (let [start (System/nanoTime)]
        (dotimes [i n-messages]
          (proc/send receiver [:msg i]))
        (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
          (proc/send receiver :done)
          (deliver done elapsed-ms))))
    (let [time-ms (deref done 60000 :timeout)]
      (c/println-result (format "send-throughput n=%d" n-messages)
                        (/ n-messages (/ time-ms 1000.0))
                        "msg/sec"))))

(defn receive-throughput
  "Pre-fill queue with N messages, then receive all.
   Measures raw receive rate."
  [n-messages]
  (let [done (promise)
        filled (promise)
        receiver (proc/spawn!
                   ;; Wait for queue to be filled
                   @filled
                   ;; Receive all messages
                   (dotimes [_ n-messages]
                     (receive! [:msg _] :ok))
                   (deliver done true))]
    ;; Fill the queue
    (proc/spawn!
      (dotimes [i n-messages]
        (proc/send receiver [:msg i]))
      (deliver filled true))
    ;; Wait for fill
    (deref filled 60000 :timeout)
    ;; Time the receives
    (let [time-ms (c/time-ms
                    (deref done 60000 :timeout))]
      (c/println-result (format "receive-throughput n=%d" n-messages)
                        (/ n-messages (/ time-ms 1000.0))
                        "msg/sec"))))

;; =============================================================================
;; Selective Receive Benchmarks
;; =============================================================================

(defn selective-receive-first
  "Selective receive where match is first in queue.
   Best-case scenario for selective receive."
  [queue-size]
  (let [done (promise)
        ready (promise)
        receiver (proc/spawn!
                   ;; Wait for queue to be filled
                   @ready
                   ;; Time the selective receive
                   (let [start (System/nanoTime)
                         result (recv/selective-receive! #(= :target %) 5000)
                         elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                     (deliver done elapsed-ms)))]
    ;; Fill queue: target first, then filler messages
    (proc/spawn!
      (proc/send receiver :target)
      (dotimes [i queue-size]
        (proc/send receiver [:filler i]))
      (deliver ready true))
    (let [time-ms (deref done 10000 :timeout)]
      (c/println-result (format "selective-recv-first q=%d" queue-size)
                        time-ms
                        "ms"))))

(defn selective-receive-last
  "Selective receive where match is last in queue.
   Worst-case scenario requiring full queue scan."
  [queue-size]
  (let [done (promise)
        ready (promise)
        receiver (proc/spawn!
                   ;; Wait for queue to be filled
                   @ready
                   ;; Time the selective receive
                   (let [start (System/nanoTime)
                         result (recv/selective-receive! #(= :target %) 5000)
                         elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                     (deliver done elapsed-ms)))]
    ;; Fill queue: filler messages first, then target last
    (proc/spawn!
      (dotimes [i queue-size]
        (proc/send receiver [:filler i]))
      (proc/send receiver :target)
      (deliver ready true))
    (let [time-ms (deref done 10000 :timeout)]
      (c/println-result (format "selective-recv-last q=%d" queue-size)
                        time-ms
                        "ms"))))

(defn selective-receive-miss
  "Selective receive with no match (timeout).
   Measures scan overhead without match."
  [queue-size]
  (let [done (promise)
        ready (promise)
        receiver (proc/spawn!
                   ;; Wait for queue to be filled
                   @ready
                   ;; Time the selective receive (will timeout)
                   (let [start (System/nanoTime)
                         result (recv/selective-receive! #(= :nonexistent %) 100)
                         elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                     (deliver done elapsed-ms)))]
    ;; Fill queue with non-matching messages
    (proc/spawn!
      (dotimes [i queue-size]
        (proc/send receiver [:filler i]))
      (deliver ready true))
    (let [time-ms (deref done 5000 :timeout)]
      (c/println-result (format "selective-recv-miss q=%d (timeout=100ms)" queue-size)
                        time-ms
                        "ms"))))

(defn selective-receive-repeated
  "Repeated selective receives on a stable queue.
   Measures overhead of multiple scans."
  [queue-size n-receives]
  (let [done (promise)
        ready (promise)
        receiver (proc/spawn!
                   ;; Wait for setup
                   @ready
                   ;; Time the N selective receives
                   (let [start (System/nanoTime)]
                     (dotimes [i n-receives]
                       (recv/selective-receive! #(= [:target i] %) 5000))
                     (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
                       (deliver done elapsed-ms))))]
    ;; Fill queue: targets interleaved with fillers
    (proc/spawn!
      ;; Add all targets first
      (dotimes [i n-receives]
        (proc/send receiver [:target i]))
      ;; Then add filler
      (dotimes [i queue-size]
        (proc/send receiver [:filler i]))
      (deliver ready true))
    (let [time-ms (deref done 60000 :timeout)]
      (c/println-result (format "selective-recv-repeated q=%d n=%d" queue-size n-receives)
                        (/ time-ms n-receives)
                        "ms/recv"))))

;; =============================================================================
;; Concurrency Benchmarks
;; =============================================================================

(defn many-to-one
  "N senders each send M messages to 1 receiver.
   Measures mailbox contention."
  [n-senders msgs-each]
  (let [done (promise)
        total-msgs (* n-senders msgs-each)
        receiver (proc/spawn!
                   (loop [count 0]
                     (if (< count total-msgs)
                       (do (receive! [:msg _ _] :ok)
                           (recur (inc count)))
                       (deliver done true))))]
    ;; Spawn senders
    (let [time-ms (c/time-ms
                    (dotimes [sender-id n-senders]
                      (proc/spawn!
                        (dotimes [msg-id msgs-each]
                          (proc/send receiver [:msg sender-id msg-id]))))
                    (deref done 120000 :timeout))]
      (c/println-result (format "many-to-one senders=%d msgs-each=%d" n-senders msgs-each)
                        (/ total-msgs (/ time-ms 1000.0))
                        "msg/sec"))))

(defn one-to-many
  "1 sender sends M messages to each of N receivers.
   Measures fan-out performance."
  [n-receivers msgs-each]
  (let [done (promise)
        counter (atom 0)
        total-msgs (* n-receivers msgs-each)
        receivers (doall
                    (repeatedly n-receivers
                      #(proc/spawn!
                         (dotimes [_ msgs-each]
                           (receive! [:msg _] :ok))
                         (when (= total-msgs (swap! counter + msgs-each))
                           (deliver done true)))))]
    ;; Single sender
    (let [time-ms (c/time-ms
                    (proc/spawn!
                      (dotimes [msg-id msgs-each]
                        (doseq [r receivers]
                          (proc/send r [:msg msg-id]))))
                    (deref done 120000 :timeout))]
      (c/println-result (format "one-to-many receivers=%d msgs-each=%d" n-receivers msgs-each)
                        (/ total-msgs (/ time-ms 1000.0))
                        "msg/sec"))))

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
  (mount/start)
  (try
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
      (mount/stop)
      (mount/start)
      (receive-throughput n))
    
    (c/println-subheader "Selective Receive - Best Case (first)")
    (doseq [q [100 1000 10000]]
      (mount/stop)
      (mount/start)
      (selective-receive-first q))
    
    (c/println-subheader "Selective Receive - Worst Case (last)")
    (doseq [q [100 1000 10000]]
      (mount/stop)
      (mount/start)
      (selective-receive-last q))
    
    (c/println-subheader "Selective Receive - Miss (timeout)")
    (doseq [q [100 1000 10000]]
      (mount/stop)
      (mount/start)
      (selective-receive-miss q))
    
    (c/println-subheader "Selective Receive - Repeated")
    (mount/stop)
    (mount/start)
    (selective-receive-repeated 1000 100)
    
    (c/println-subheader "Many-to-One")
    (mount/stop)
    (mount/start)
    (many-to-one 10 10000)
    (mount/stop)
    (mount/start)
    (many-to-one 100 1000)
    
    (c/println-subheader "One-to-Many")
    (mount/stop)
    (mount/start)
    (one-to-many 10 10000)
    (mount/stop)
    (mount/start)
    (one-to-many 100 1000)
    
    (finally
      (mount/stop))))
