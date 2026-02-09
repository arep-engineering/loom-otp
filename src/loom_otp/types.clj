(ns loom-otp.types
  "Core types: TRef, Async.
   
   Pids are now Thread objects directly - no wrapper type needed.
   
   Types defined here:
   - TRef: Reference (for monitors, timers, etc.)
   - Async: Asynchronous computation result")

;; =============================================================================
;; Pid Functions (Thread-based)
;; =============================================================================

(defn pid?
  "Returns true if x is a process identifier (Thread)."
  [x]
  (instance? Thread x))

(defn pid->str
  "Returns a string representation of the pid (Thread).
   Warning: intended for debugging, not application use."
  [^Thread pid]
  (when-not (pid? pid)
    (throw (IllegalArgumentException. "argument must be a Thread (pid)")))
  (format "Pid<%d>" (.threadId pid)))

;; =============================================================================
;; TRef Type
;; =============================================================================

(deftype TRef [^long id]
  Object
  (toString [_]
    (format "TRef<%d>" id))
  (hashCode [_]
    (Long/hashCode id))
  (equals [_ other]
    (and (instance? TRef other)
         (= id (.id ^TRef other)))))

(defn ref?
  "Returns true if x is a reference (TRef)."
  [x]
  (instance? TRef x))

(defn ref->id
  "Extract the internal id from a TRef for use as map key.
   Returns nil if argument is not a TRef."
  [ref]
  (when (ref? ref)
    (.id ^TRef ref)))

(defn ->ref
  "Create a TRef from a long id."
  [^long id]
  (->TRef id))

;; =============================================================================
;; Async Type
;; =============================================================================

(deftype Async [promise value]
  Object
  (toString [_]
    (if promise
      (if (realized? promise)
        (format "Async<completed>")
        (format "Async<pending>"))
      (format "Async<value=%s>" value))))

(defn async?
  "Returns true if x is an Async value."
  [x]
  (instance? Async x))

(defn ->async
  "Create an Async from a promise or value.
   If promise is provided, value should be nil.
   If value is provided, promise should be nil."
  [promise value]
  (->Async promise value))

(defn async-promise
  "Get the promise from an Async, or nil if it wraps a value."
  [^Async a]
  (.promise a))

(defn async-value
  "Get the value from an Async, or nil if it wraps a promise."
  [^Async a]
  (.value a))
