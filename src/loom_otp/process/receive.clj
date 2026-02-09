(ns loom-otp.process.receive
  "Message receive functions.
   
   This namespace provides function-based receive operations:
   - receive! - FIFO receive, returns message or :timeout
   - selective-receive! - predicate-based receive, scans mailbox
   
   For pattern-matching macros, see loom-otp.process.match."
  (:require [loom-otp.context-mailbox :as cmb]
            [loom-otp.process.core :as core]))

;; =============================================================================
;; FIFO Receive Function
;; =============================================================================

(defn receive!
  "Receive the next message from the mailbox (FIFO order).
   
   Arities:
   - [] - block forever until message arrives
   - [timeout-ms timeout-val] - wait up to timeout-ms, return timeout-val on timeout
   
   Message context is automatically merged into the process's context.
   Throws InterruptedException if the process is killed while waiting."
  ([]
   (receive! nil nil))
  ([timeout-ms timeout-val]
   (let [proc (core/self-proc)
         mailbox (:mailbox proc)
         ;; :infinity means wait forever, same as nil
         effective-timeout (if (= timeout-ms :infinity) nil timeout-ms)]
     (let [result (cmb/receive! mailbox effective-timeout ::timeout)]
       (if (= result ::timeout)
         timeout-val
         (let [[ctx msg] result]
           (core/update-message-context! ctx)
           msg))))))

;; =============================================================================
;; Selective Receive Helpers
;; =============================================================================

(defn- find-first-match
  "Find first entry in queue where (match-fn msg) is true.
   Queue entries are [ctx msg] pairs.
   Returns [[ctx msg] remaining-queue] or nil."
  [match-fn q]
  (loop [checked clojure.lang.PersistentQueue/EMPTY
         remaining (seq q)]
    (if-let [[ctx msg :as entry] (first remaining)]
      (if (match-fn msg)
        [entry (into checked (rest remaining))]
        (recur (conj checked entry) (rest remaining)))
      nil)))

(defn- try-extract-match!
  "Attempt to extract first matching entry from mailbox.
   Returns [ctx msg] if found, nil otherwise.
   Mutates mailbox to remove the matched entry."
  [mailbox match-fn]
  (let [result (atom nil)]
    (swap! mailbox
      (fn [q]
        (if-let [[matched remaining] (find-first-match match-fn q)]
          (do (reset! result matched)
              remaining)
          q)))
    @result))

;; =============================================================================
;; Selective Receive Function
;; =============================================================================

(defn selective-receive!
  "Receive first message matching predicate, with optional timeout.
   
   Unlike receive!, this scans the mailbox for a matching message
   rather than taking FIFO. Non-matching messages remain in the mailbox
   in their original order.
   
   match-fn is called with the unwrapped message (not [ctx msg]).
   Returns the unwrapped message, or :timeout if no match found within timeout.
   
   Message context is automatically merged into the process's context.
   Throws InterruptedException if the process is killed while waiting."
  ([match-fn] (selective-receive! match-fn nil))
  ([match-fn timeout-ms]
   (let [proc (core/self-proc)
         mailbox (:mailbox proc)
         ;; :infinity means wait forever, same as nil
         effective-timeout (if (= timeout-ms :infinity) nil timeout-ms)
         match-arrived (promise)
         watch-key (Object.)]
     
     ;; Add watch for new messages
     (add-watch mailbox watch-key
       (fn [_ _ _ new-q]
         ;; Check if any [ctx msg] entry matches on msg
         (when (some (fn [[_ctx msg]] (match-fn msg)) (seq new-q))
           (deliver match-arrived true))))
     
     ;; Try to extract from existing queue
     (if-let [[ctx msg] (try-extract-match! mailbox match-fn)]
       (do (remove-watch mailbox watch-key)
           (core/update-message-context! ctx)
           msg)
        ;; Block on promise, then retry
        (let [result (if effective-timeout
                       (deref match-arrived effective-timeout :timeout)
                       (deref match-arrived))]
         (remove-watch mailbox watch-key)
         (if (= result :timeout)
           :timeout
           (when-let [[ctx msg] (try-extract-match! mailbox match-fn)]
             (core/update-message-context! ctx)
             msg)))))))
