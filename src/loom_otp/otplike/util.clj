(ns ^:no-doc loom-otp.otplike.util
  "Compatibility shim for otplike.util - NO core.async")

;; Overload the printer for queues so they look like other collections
(defmethod print-method clojure.lang.PersistentQueue [q w]
  (print-method '< w)
  (print-method (vec q) w)
  (print-method '= w))

;; =============================================================================
;; API
;; =============================================================================

(defmacro check-args [exprs]
  (assert (sequential? exprs))
  (when-let [expr (first exprs)]
    `(if ~expr
       (check-args ~(rest exprs))
       (throw (IllegalArgumentException.
                (str "require " '~expr " to be true"))))))

(defn stack-trace
  "Convert exception to a map with :class, :message, :stack-trace, and optionally
   :data (for ExceptionInfo) and :cause (recursive).
   This is the otplike-compatible exception format."
  [^Throwable e]
  (merge
   {:message (.getMessage e)
    :class (.getName (class e))
    :stack-trace (mapv str (.getStackTrace e))}
   (when (instance? clojure.lang.ExceptionInfo e)
     {:data (ex-data e)})
   (when-let [cause (.getCause e)]
     {:cause (stack-trace cause)})))

(defn queue []
  (clojure.lang.PersistentQueue/EMPTY))
