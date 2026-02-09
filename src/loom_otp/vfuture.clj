(ns loom-otp.vfuture
  "Virtual thread future - like clojure.core/future but always uses virtual threads.
   
   Usage:
   (let [f (vfuture (expensive-computation))]
     ;; do other work
     @f)  ; blocks until computation completes
   
   Exceptions thrown in the body are re-thrown when dereferencing.
   Dynamic bindings from the calling thread are preserved.")

(deftype VFuture [^clojure.lang.IPending p]
  clojure.lang.IDeref
  (deref [_]
    (let [{:keys [value error]} @p]
      (if error
        (throw error)
        value)))
  
  clojure.lang.IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (let [result (deref p timeout-ms ::timeout)]
      (if (= result ::timeout)
        timeout-val
        (let [{:keys [value error]} result]
          (if error
            (throw error)
            value)))))
  
  clojure.lang.IPending
  (isRealized [_]
    (.isRealized p)))

(defmacro vfuture
  "Execute body on a virtual thread. Returns a derefable future.
   
   Dereferencing blocks until the computation completes and returns the result.
   If the body throws an exception, it is re-thrown when dereferencing.
   
   Dynamic bindings from the calling thread are preserved.
   Supports timeout via (deref f timeout-ms timeout-val)."
  [& body]
  `(let [p# (promise)
         f# (bound-fn [] (do ~@body))]
     (Thread/startVirtualThread
       (fn []
         (try
           (deliver p# {:value (f#)})
           (catch Throwable t#
             (deliver p# {:error t#})))))
     (->VFuture p#)))
