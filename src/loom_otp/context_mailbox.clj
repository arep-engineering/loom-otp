(ns loom-otp.context-mailbox
  "Context-aware mailbox: stores [ctx msg] pairs.
   
   This is a pure wrapper around mailbox that handles the [ctx msg] format.
   It does NOT know about processes - context merging is the caller's responsibility.
   
   Messages are stored as [ctx msg] pairs to enable context propagation
   for distributed tracing through message chains and exit signals."
  (:require [loom-otp.mailbox :as mb]))

;; =============================================================================
;; Creation
;; =============================================================================

(defn make-mailbox
  "Create a new empty context-aware mailbox."
  []
  (mb/make-mailbox))

;; =============================================================================
;; Send
;; =============================================================================

(defn send!
  "Send message to mailbox, wrapped as [ctx msg]."
  [mb ctx msg]
  (mb/mailbox-send! mb [ctx msg]))

;; =============================================================================
;; Receive
;; =============================================================================

(defn receive!
  "Blocking receive from mailbox with optional timeout.
   Returns [ctx msg] pair, or timeout-val if timeout expires."
  ([mb]
   (mb/mailbox-receive! mb))
  ([mb timeout-ms timeout-val]
   (mb/mailbox-receive! mb timeout-ms timeout-val)))

(defn maybe-receive!
  "Non-blocking receive from mailbox.
   Returns [ctx msg] pair, or nil if no message available."
  [mb]
  (mb/maybe-mailbox-receive! mb))
