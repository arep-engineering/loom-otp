(ns loom-otp.mailbox
  "Mailbox implementation for message passing.
   
   A mailbox is an atom wrapping a persistent queue with watch-based
   notification for efficient blocking receive.")

;; =============================================================================
;; Mailbox Creation and Send
;; =============================================================================

(defn make-mailbox
  "Create a new empty mailbox."
  []
  (atom clojure.lang.PersistentQueue/EMPTY))

(defn mailbox-send!
  "Send a message to a mailbox (non-blocking)."
  [mb msg]
  (swap! mb conj msg))

;; =============================================================================
;; Mailbox Receive
;; =============================================================================

(defn maybe-mailbox-receive!
  "Non-blocking receive from mailbox. Returns message if available, nil otherwise."
  [mb]
  (when-let [msg (peek @mb)]
    (swap! mb pop)
    msg))

(defn mailbox-receive!
  "Blocking receive from mailbox with optional timeout.
   Uses atom watch for efficient wake-up on message arrival.
   
   Arities:
   - [mb] - block forever until message arrives
   - [mb timeout-ms timeout-val] - wait up to timeout-ms, return timeout-val on timeout"
  ([mb]
   (mailbox-receive! mb nil nil))
  ([mb timeout-ms timeout-val]
   (or (maybe-mailbox-receive! mb)
       (let [p (promise)
             watch-key (Object.)]
         (add-watch mb watch-key
                    (fn [_ _ _ _]
                      (remove-watch mb watch-key)
                      (deliver p :wake)))
         ;; Double-check after adding watch (race condition prevention)
         (if (or (peek @mb)
                 (not= :timeout (if timeout-ms
                                  (deref p timeout-ms :timeout)
                                  (deref p))))
           (maybe-mailbox-receive! mb)
           (do
             (remove-watch mb watch-key)
             timeout-val))))))
