(ns loom-otp.process.match
  "Pattern-matching receive macros using core.match.
   
   This namespace provides macros for receiving messages with pattern matching:
   - receive! - FIFO receive with pattern matching
   - selective-receive! - selective receive with pattern matching
   
   For function-based receive without pattern matching, see loom-otp.process.receive."
  (:require [clojure.core.match :as cm]
            [loom-otp.process.receive :as recv]))

;; =============================================================================
;; FIFO Receive Macro
;; =============================================================================

(defmacro receive!
  "Receive a message with pattern matching.
   
   Usage:
   (receive!
     [:hello name] (println \"Hello\" name)
     [:add a b] (+ a b)
     (after 1000 :timeout))
   
   The last clause can be (after timeout-ms expr) for timeout handling.
   Without an after clause and no matching message, throws an exception.
   
   Message context is automatically merged into the process's context.
   EXIT signals (when trapping exits) are received as regular messages."
  [& clauses]
  (assert (> (count clauses) 1)
          "Receive requires one or more message patterns")
  (let [;; Check for (after timeout body) clause
        ;; Use seq? instead of list? because syntax-quoted forms may be Cons not PersistentList
        has-after? (and (seq clauses)
                        (seq? (last clauses))
                        (= 'after (first (last clauses))))
        after-clause (when has-after? (last clauses))
        timeout-ms (when after-clause (second after-clause))
        timeout-body (when after-clause (drop 2 after-clause))
        match-clauses (if has-after? (butlast clauses) clauses)]
    `(let [result# (recv/receive! ~timeout-ms ::timeout)]
       (if (= result# ::timeout)
         ~(if after-clause
            `(do ~@timeout-body)
            `(throw (ex-info "receive timeout with no after clause" {})))
         (cm/match result#
            ~@match-clauses
            :else (throw (ex-info "no matching clause for message" {:message result#})))))))

;; =============================================================================
;; Selective Receive Macro
;; =============================================================================

(defmacro selective-receive!
  "Receive first message matching any pattern, scanning the entire mailbox.
   Unlike receive!, this doesn't just take the first message - it finds
   the first message that matches any of the provided patterns.
   
   Usage:
   (selective-receive!
     [:hello name] (println \"Hello\" name)
     [:add a b] (+ a b)
     (after 1000 :timeout))
   
   The last clause can be (after timeout-ms expr) for timeout handling.
   Message context is automatically merged into the process's context."
  [& clauses]
  (assert (> (count clauses) 1)
          "Receive requires one or more message patterns")
  (let [;; Check for (after timeout body) clause
        ;; Use seq? instead of list? because syntax-quoted forms may be Cons not PersistentList
        has-after? (and (seq clauses)
                        (seq? (last clauses))
                        (= 'after (first (last clauses))))
        after-clause (when has-after? (last clauses))
        timeout-ms (when after-clause (second after-clause))
        timeout-body (when after-clause (drop 2 after-clause))
        match-clauses (if has-after? (butlast clauses) clauses)
        patterns (take-nth 2 match-clauses)
        ;; Build the match clauses for the predicate: pattern true, pattern true, ...
        match-check-clauses (vec (mapcat (fn [p] [p true]) patterns))]
    `(let [match-fn# (fn [msg#]
                       (try
                         (cm/match msg#
                           ~@match-check-clauses
                           :else false)
                         (catch Exception _# false)))
           result# (recv/selective-receive! match-fn# ~timeout-ms)]
       (if (= result# :timeout)
         ~(if after-clause
            `(do ~@timeout-body)
            `(throw (ex-info "selective receive timeout with no after clause" {})))
         (cm/match result#
           ~@match-clauses
           :else (throw (ex-info "no matching clause for message" {:message result#})))))))
