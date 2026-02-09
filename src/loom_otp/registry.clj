(ns loom-otp.registry
  "Process name registry.
   
   Allows processes to be registered under symbolic names for
   location-transparent messaging.
   
   Uses ConcurrentHashMap for thread-safe operations."
  (:require [loom-otp.types :as t]
            [loom-otp.state :as state])
  (:import [java.util.concurrent ConcurrentHashMap]))

;; =============================================================================
;; Registration
;; =============================================================================

(defn whereis
  "Return pid (Thread) registered under name, or nil."
  [name]
  (when name
    (.get (state/registry-forward) name)))

(defn registered
  "Return set of all registered names."
  []
  (set (keys (state/registry-forward))))

(defn get-registered-name
  "Get the registered name for a pid, or nil."
  [^Thread pid]
  (.get (state/registry-reverse) pid))

(defn register!
  "Register name for pid. Throws if name already registered or process already has a name."
  [name ^Thread pid]
  (let [^ConcurrentHashMap forward (state/registry-forward)
        ^ConcurrentHashMap reverse (state/registry-reverse)
        ;; Check if process already has a registered name
        existing-name (.get reverse pid)]
    (when existing-name
      (throw (ex-info "registration failed"
                      {:type :already-has-name
                       :pid pid
                       :existing-name existing-name
                       :attempted-name name})))
    ;; Use putIfAbsent for atomic check-and-set
    (let [existing (.putIfAbsent forward name pid)]
      (if existing
        (throw (ex-info "registration failed" 
                        {:type :already-registered 
                         :name name
                         :existing-pid existing}))
        (do
          (.put reverse pid name)
          true)))))

(defn unregister!
  "Remove registration for pid."
  [^Thread pid]
  (let [^ConcurrentHashMap forward (state/registry-forward)
        ^ConcurrentHashMap reverse (state/registry-reverse)]
    (when-let [name (.remove reverse pid)]
      (.remove forward name)))
  true)

;; =============================================================================
;; Resolve Destination
;; =============================================================================

(defn resolve-pid
  "Resolve destination to pid (Thread). Accepts Thread or registered name.
   Returns nil if name not found, or throws if throw? is true."
  ([dest] (resolve-pid dest false))
  ([dest throw?]
   (let [pid (if (t/pid? dest) dest (whereis dest))]
     (if (and throw? (nil? pid))
       (throw (ex-info "unknown destination" {:dest dest}))
       pid))))
