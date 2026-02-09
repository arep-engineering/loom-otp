(ns loom-otp.context-test
  "Tests for message context propagation.
   
   Context flows through:
   - Messages (sender's context -> receiver)
   - Exit signals (dying process's context -> linked processes)
   - DOWN messages (dying process's context -> monitors)
   - Child processes (parent's context -> child's initial context)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.link :as link]
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
;; Message Context Propagation Tests
;; =============================================================================

(deftest message-context-propagation-test
  (testing "sender's context propagates to receiver"
    (let [result (promise)
          receiver (proc/spawn!
                    (match/receive!
                     :ping (deliver result (core/message-context))))]
      ;; Sender sets context and sends message
      (proc/spawn!
       (core/update-message-context! {:trace-id "abc123" :source :sender})
       (core/send receiver :ping))
      (let [ctx (deref result 1000 :timeout)]
        (is (map? ctx))
        (is (= "abc123" (:trace-id ctx)))
        (is (= :sender (:source ctx))))))
  
  (testing "empty context propagates as empty map"
    (let [result (promise)
          receiver (proc/spawn!
                    (match/receive!
                     :ping (deliver result (core/message-context))))]
      ;; Sender with no context set
      (proc/spawn!
       (core/send receiver :ping))
      (let [ctx (deref result 1000 :timeout)]
        (is (= {} ctx)))))
  
  (testing "context propagates through message chain"
    (let [result (promise)
          ;; C receives from B, reports context
          proc-c (proc/spawn!
                  (match/receive!
                   :from-b (deliver result (core/message-context))))
          ;; B receives from A, forwards to C
          proc-b (proc/spawn!
                  (match/receive!
                   :from-a (core/send proc-c :from-b)))
          ;; A sets context, sends to B
          proc-a (proc/spawn!
                  (core/update-message-context! {:origin :proc-a :step 1})
                  (core/send proc-b :from-a))]
      (let [ctx (deref result 1000 :timeout)]
        (is (= :proc-a (:origin ctx)))
        (is (= 1 (:step ctx)))))))

;; =============================================================================
;; Context Merge Tests
;; =============================================================================

(deftest context-merge-test
  (testing "receiving merges context, doesn't replace"
    (let [result (promise)
          receiver (proc/spawn!
                    ;; Set initial context
                    (core/update-message-context! {:receiver-key :original})
                    ;; Receive message (should merge sender's context)
                    (match/receive!
                     :msg1 nil)
                    ;; Check merged context
                    (deliver result (core/message-context)))]
      ;; Sender with different context
      (proc/spawn!
       (core/update-message-context! {:sender-key :from-sender})
       (core/send receiver :msg1))
      (let [ctx (deref result 1000 :timeout)]
        (is (= :original (:receiver-key ctx)) "Receiver's original context preserved")
        (is (= :from-sender (:sender-key ctx)) "Sender's context merged in"))))
  
  (testing "later messages can update context values"
    (let [result (promise)
          receiver (proc/spawn!
                    (match/receive! :msg1 nil)
                    (match/receive! :msg2 nil)
                    (deliver result (core/message-context)))]
      ;; First sender
      (proc/spawn!
       (core/update-message-context! {:key :first-value})
       (core/send receiver :msg1))
      (Thread/sleep 50)
      ;; Second sender with same key
      (proc/spawn!
       (core/update-message-context! {:key :second-value})
       (core/send receiver :msg2))
      (let [ctx (deref result 1000 :timeout)]
        (is (= :second-value (:key ctx)) "Later message overwrites same key")))))

;; =============================================================================
;; Child Process Context Inheritance Tests
;; =============================================================================

(deftest child-inherits-parent-context-test
  (testing "spawned process inherits parent's context"
    (let [result (promise)]
      (proc/spawn!
       (core/update-message-context! {:parent-trace "xyz789"})
       (proc/spawn!
        (deliver result (core/message-context))))
      (let [ctx (deref result 1000 :timeout)]
        (is (= "xyz789" (:parent-trace ctx))))))
  
  (testing "spawn-link inherits parent's context"
    (let [result (promise)]
      (proc/spawn-trap!
       (core/update-message-context! {:parent-id 42})
       (proc/spawn-link!
        (deliver result (core/message-context))))
      (let [ctx (deref result 1000 :timeout)]
        (is (= 42 (:parent-id ctx))))))
  
  (testing "nested spawn inherits accumulated context"
    (let [result (promise)]
      (proc/spawn!
       (core/update-message-context! {:level 1})
       (proc/spawn!
        (core/update-message-context! {:level 2})
        (proc/spawn!
         (deliver result (core/message-context)))))
      (let [ctx (deref result 1000 :timeout)]
        ;; Child gets parent's context at spawn time (level 2)
        ;; Note: the :level 1 was overwritten by :level 2 before spawning grandchild
        (is (= 2 (:level ctx)))))))

;; =============================================================================
;; Exit Signal Context Propagation Tests
;; =============================================================================

(deftest exit-signal-context-propagation-test
  (testing "dying process's context propagates to linked process via EXIT message"
    (let [result (promise)]
      (proc/spawn-trap!
       (let [child (proc/spawn-link!
                    (core/update-message-context! {:dying-process-trace "crash-123"})
                    (exit/exit :intentional-crash))]
         (match/receive!
          [:EXIT pid reason]
          (deliver result {:reason reason
                           :context (core/message-context)}))))
      (let [r (deref result 1000 :timeout)]
        (is (= :intentional-crash (:reason r)))
        (is (= "crash-123" (get-in r [:context :dying-process-trace]))))))
  
  (testing "context propagates through chain of exits"
    (let [result (promise)]
      (proc/spawn-trap!
       ;; Create chain: grandparent -> parent -> child
       ;; Child crashes with context, parent crashes (propagating context),
       ;; grandparent receives EXIT with original context
       (let [parent (proc/spawn-link!
                     ;; Parent spawns child and links
                     (proc/spawn-link!
                      (core/update-message-context! {:original-crash-source "child"})
                      (exit/exit :child-crash))
                     ;; Parent will die when child dies (not trapping)
                     (Thread/sleep 5000))]
         (match/receive!
          [:EXIT pid reason]
          (deliver result {:reason reason
                           :context (core/message-context)}))))
      (let [r (deref result 2000 :timeout)]
        ;; The context from the original crash should propagate
        (is (= "child" (get-in r [:context :original-crash-source])))))))

;; =============================================================================
;; DOWN Message Context Propagation Tests
;; =============================================================================

(deftest down-message-context-propagation-test
  (testing "dying process's context propagates via DOWN message"
    (let [result (promise)]
      (proc/spawn!
       (let [target (proc/spawn!
                     (core/update-message-context! {:monitored-trace "mon-456"})
                     (Thread/sleep 50)
                     (exit/exit :monitored-exit))
             ref (monitor/monitor target)]
         (match/receive!
          [:DOWN r :process _ reason]
          (deliver result {:ref-match (= r ref)
                           :reason reason
                           :context (core/message-context)}))))
      (let [r (deref result 1000 :timeout)]
        (is (:ref-match r))
        (is (= :monitored-exit (:reason r)))
        (is (= "mon-456" (get-in r [:context :monitored-trace]))))))
  
  (testing "monitor by name preserves context"
    (let [result (promise)]
      ;; Start target with a name
      (let [target (proc/spawn!
                    (proc/register :ctx-test-server)
                    (core/update-message-context! {:server-ctx "named-server"})
                    (Thread/sleep 100)
                    (exit/exit :server-done))]
        (Thread/sleep 30)
        ;; Monitor by name
        (proc/spawn!
         (let [ref (monitor/monitor :ctx-test-server)]
           (match/receive!
            [:DOWN r :process name reason]
            (deliver result {:name name
                             :reason reason
                             :context (core/message-context)})))))
      (let [r (deref result 1000 :timeout)]
        (is (= :ctx-test-server (:name r)))
        (is (= :server-done (:reason r)))
        (is (= "named-server" (get-in r [:context :server-ctx])))))))

;; =============================================================================
;; Context API Tests
;; =============================================================================

(deftest message-context-api-test
  (testing "message-context returns {} outside process"
    (is (= {} (core/message-context))))
  
  (testing "message-context returns current context inside process"
    (let [result (promise)]
      (proc/spawn!
       (core/update-message-context! {:key :value})
       (deliver result (core/message-context)))
      (is (= {:key :value} (deref result 1000 :timeout)))))
  
  (testing "update-message-context! is no-op outside process"
    ;; Should not throw
    (core/update-message-context! {:test :value})
    (is (= {} (core/message-context))))
  
  (testing "update-message-context! merges into existing context"
    (let [result (promise)]
      (proc/spawn!
       (core/update-message-context! {:a 1})
       (core/update-message-context! {:b 2})
       (core/update-message-context! {:c 3})
       (deliver result (core/message-context)))
      (is (= {:a 1 :b 2 :c 3} (deref result 1000 :timeout))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest context-edge-cases-test
  (testing "nil context values are preserved"
    (let [result (promise)
          receiver (proc/spawn!
                    (match/receive!
                     :msg (deliver result (core/message-context))))]
      (proc/spawn!
       (core/update-message-context! {:nil-val nil :other "present"})
       (core/send receiver :msg))
      (let [ctx (deref result 1000 :timeout)]
        (is (contains? ctx :nil-val))
        (is (nil? (:nil-val ctx)))
        (is (= "present" (:other ctx))))))
  
  (testing "deeply nested context values work"
    (let [result (promise)
          receiver (proc/spawn!
                    (match/receive!
                     :msg (deliver result (core/message-context))))]
      (proc/spawn!
       (core/update-message-context! {:deep {:nested {:value 42}}})
       (core/send receiver :msg))
      (let [ctx (deref result 1000 :timeout)]
        (is (= 42 (get-in ctx [:deep :nested :value])))))))

;; =============================================================================
;; Concurrent Signal and Message Context Tests
;; =============================================================================

(deftest concurrent-signal-and-message-context-test
  (testing "message received before interrupt uses message context"
    ;; Process receives user message with its context, then gets interrupted.
    ;; The message context should be merged, then control context merged on interrupt.
    (let [result (promise)]
      (proc/spawn-trap!
       (let [target (proc/spawn-link!
                     ;; Wait for message to arrive
                     (Thread/sleep 100)
                     ;; Now receive it - this will merge message context
                     (match/receive!
                      :user-msg nil)
                     ;; Keep running until killed
                     (Thread/sleep 5000))]
         ;; Send user message with context
         (proc/spawn!
          (core/update-message-context! {:user-msg-ctx "from-user"})
          (core/send target :user-msg))
         ;; Wait for message to be received
         (Thread/sleep 200)
         ;; Now send exit signal with different context
         (proc/spawn!
          (core/update-message-context! {:exit-signal-ctx "from-killer"})
          (exit/exit target :killed-after-msg))
         ;; Receive EXIT with target's final context
         (match/receive!
          [:EXIT pid reason]
          (deliver result (core/message-context)))))
      (let [ctx (deref result 2000 :timeout)]
        ;; Should have both contexts merged
        (is (= "from-user" (:user-msg-ctx ctx)) "User message context should be present")
        (is (= "from-killer" (:exit-signal-ctx ctx)) "Exit signal context should be merged"))))
  
  (testing "interrupt before message receive uses control context"
    ;; Process is interrupted before receiving any user message.
    ;; Context should come from control signal.
    (let [result (promise)]
      (proc/spawn-trap!
       (let [target (proc/spawn-link! (Thread/sleep 5000))]
         ;; Wait for target to start sleeping
         (Thread/sleep 50)
         ;; Kill it with context
         (proc/spawn!
          (core/update-message-context! {:killer-ctx "killed-early"})
          (exit/exit target :early-kill))
         ;; Receive EXIT
         (match/receive!
          [:EXIT pid reason]
          (deliver result (core/message-context)))))
      (let [ctx (deref result 2000 :timeout)]
        (is (= "killed-early" (:killer-ctx ctx))))))
  
  (testing "rapid concurrent signal and message - both contexts preserved"
    ;; Send message and exit signal nearly simultaneously.
    ;; The dying process should have both contexts (order may vary).
    (let [results (atom [])
          iterations 10]
      (dotimes [i iterations]
        (let [result (promise)]
          (proc/spawn-trap!
           (let [target (proc/spawn-link!
                         ;; Try to receive, will either get message or be interrupted
                         (try
                           (match/receive!
                            :msg nil
                            (after 1000 nil))
                           (catch Exception _))
                         ;; Sleep a bit more
                         (Thread/sleep 500))]
             ;; Launch message sender and killer concurrently
             (let [msg-sender (proc/spawn!
                               (core/update-message-context! {:msg-ctx (str "msg-" i)})
                               (core/send target :msg))
                   killer (proc/spawn!
                           (core/update-message-context! {:kill-ctx (str "kill-" i)})
                           (exit/exit target :concurrent-kill))]
               ;; Receive EXIT
               (match/receive!
                [:EXIT pid reason]
                (deliver result (core/message-context))))))
          (let [ctx (deref result 3000 :timeout)]
            (swap! results conj ctx))))
      ;; At least some should have the kill context
      ;; (message context may or may not be present depending on timing)
      (is (every? #(string? (:kill-ctx %)) @results)
          "All results should have kill context")))
  
  (testing "process receives message, then traps exit with context"
    ;; Process receives a user message (gets its context),
    ;; then receives EXIT message (gets signal's context).
    ;; Final context should have both.
    (let [result (promise)
          target (proc/spawn-trap!
                  ;; Receive user message
                  (match/receive!
                   :setup nil)
                  ;; Now wait for EXIT message
                  (match/receive!
                   [:EXIT _ _] nil)
                  ;; Report combined context
                  (deliver result (core/message-context)))]
      ;; Send setup message with context
      (Thread/sleep 50)
      (proc/spawn!
       (core/update-message-context! {:setup-ctx "setup-value"})
       (core/send target :setup))
      ;; Wait for setup to be received
      (Thread/sleep 100)
      ;; Send exit signal with context
      (proc/spawn!
       (core/update-message-context! {:exit-ctx "exit-value"})
       (exit/exit target :gentle-exit))
      (let [ctx (deref result 2000 :timeout)]
        (is (= "setup-value" (:setup-ctx ctx)) "Setup message context present")
        (is (= "exit-value" (:exit-ctx ctx)) "Exit signal context present")))))
