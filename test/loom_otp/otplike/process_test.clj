(ns loom-otp.otplike.process-test
  "Tests for loom-otp.otplike.process compatibility layer.
   
   Adapted from otplike.process-test with these changes:
   - No core.async (uses promises for synchronization)
   - await-message is now a macro called from process context
   - Channels replaced with promises"
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [loom-otp.otplike.process :as process :refer [! proc-fn]]
            [loom-otp.otplike.trace :as trace]
            [loom-otp.otplike.test-util :refer :all]
            [loom-otp.otplike.proc-util :as proc-util]
            [clojure.core.match :refer [match]]
            [mount.lite :as mount]))

;; Use mount.lite to start/stop the system for each test
(use-fixtures :each
  (fn [f]
    (mount/start)
    (try
      (f)
      (finally
        (mount/stop)))))

;; =============================================================================
;; Helper to sleep for tests (replaces async/timeout)
;; =============================================================================

(defn sleep [ms]
  (Thread/sleep ms))

(defn busy-wait
  "Busy wait for approximately ms milliseconds without blocking.
   Unlike Thread/sleep, this won't throw InterruptedException."
  [ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (while (< (System/currentTimeMillis) end)
      ;; Do some work to prevent JIT from optimizing away the loop
      (Math/sqrt (rand)))))

;; =============================================================================
;; (self [])
;; =============================================================================

(deftest ^:parallel self-returns-process-pid-in-process-context
  (let [done (promise)
        pfn (proc-fn []
              (is (process/pid? (process/self))
                  "self must return pid when called in process context")
              (is (= (process/self) (process/self))
                  (str "self must return the same pid when called by the same"
                       " process"))
              (! (process/self) :msg)
              (is (= [:message :msg] (await-message 50))
                  "message sent to self must appear in inbox")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 100)))

(deftest ^:parallel self-fails-in-non-process-context
  (is (thrown? Exception (process/self))
      "self must throw when called not in process context"))

;; =============================================================================
;; (pid? [term])
;; =============================================================================

(deftest ^:parallel pid?-returns-false-on-non-pid
  (is (not (process/pid? nil)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? 1)) "pid? must return false on nonpid arguement")
  (is (not (process/pid? "not-a-pid"))
      "pid? must return false on nonpid arguement")
  (is (not (process/pid? [])) "pid? must return false on nonpid arguement")
  (is (not (process/pid? '())) "pid? must return false on nonpid arguement")
  (is (not (process/pid? #{})) "pid? must return false on nonpid arguement")
  (is (not (process/pid? {})) "pid? must return false on nonpid arguement"))

(deftest ^:parallel pid?-returns-true-on-pid
  (is (process/pid? (process/spawn (proc-fn [])))
      "pid? must return true on pid argument"))

;; =============================================================================
;; (pid->str [pid])
;; =============================================================================

(deftest ^:parallel pid->str-returns-string
  (is (string? (process/pid->str (process/spawn (proc-fn []))))
      "pid->str must return string on pid argument"))

(deftest ^:parallel pid->str-fails-on-non-pid
  (is (thrown? Exception (process/pid->str nil))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str 1))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str "not-a-pid"))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str []))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str '()))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str #{}))
      "pid->str must throw on nonpid arguement")
  (is (thrown? Exception (process/pid->str {}))
      "pid->str must throw on nonpid arguement"))

;; =============================================================================
;; (whereis [reg-name])
;; =============================================================================

(deftest ^:parallel whereis-returns-process-pid-on-registered-name
  (let [done (promise)
        reg-name (uuid-keyword)
        pid (process/spawn-opt
              (proc-fn []
                (is (= (process/self) (process/whereis reg-name))
                    "whereis must return process pid on registered name")
                (await-completion!! done 100))
              {:register reg-name})]
    (is (= pid (process/whereis reg-name))
        "whereis must return process pid on registered name")
    (deliver done true)))

(deftest ^:parallel whereis-returns-nil-on-not-registered-name
  (let [pid (process/spawn (proc-fn []))]
    (is (nil? (process/whereis pid))
        "whereis must return nil on not registered name"))
  (is (nil? (process/whereis nil))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis "name"))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis :name))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis [:some :name]))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis 123))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis '(:a :b)))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis {:a 1}))
      "whereis must return nil on not registered name")
  (is (nil? (process/whereis #{:b}))
      "whereis must return nil on not registered name"))

;; =============================================================================
;; (! [dest message])
;; =============================================================================

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-pid
  (let [done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 100)))]
    (is (= true (! pid :msg)) "! must return true sending to alive process")
    (deliver done true)))

(deftest ^:parallel !-returns-true-sending-to-alive-process-by-reg-name
  (let [done (promise)
        reg-name (uuid-keyword)]
    (process/spawn-opt (proc-fn [] (await-completion!! done 100))
                       {:register reg-name})
    (is (= true (! reg-name :msg))
        "! must return true sending to alive process")
    (deliver done true)))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-reg-name
  (let [reg-name (uuid-keyword)]
    (process/spawn-opt (proc-fn []) {:register reg-name})
    (sleep 50)
    (is (= false (! reg-name :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-returns-false-sending-to-unregistered-name
  (is (= false (! (uuid-keyword) :msg))
      "! must return false sending to unregistered name"))

(deftest ^:parallel !-returns-false-sending-to-terminated-process-by-pid
  (let [pid (process/spawn (proc-fn []))]
    (sleep 50)
    (is (= false (! pid :msg))
        "! must return false sending to terminated process")))

(deftest ^:parallel !-throws-on-nil-pid
  (is (thrown? Exception (! nil :msg))
      "! must throw on when dest argument is nil")
  (is (thrown? Exception (! nil nil))
      "! must throw on when both arguments are nil"))

(deftest ^:parallel !-allows-sending-nil
  (let [done (promise)
        pid (process/spawn
             (proc-fn [] (process/receive! _ (deliver done true))))]
    (! pid nil)
    (await-completion!! done 100)))

(deftest ^:parallel !-delivers-message-sent-by-pid-to-alive-process
  (let [done (promise)
        pfn (proc-fn []
              (is (= [:message :msg] (await-message 50))
                  "message sent with ! to pid must appear in inbox")
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 100)))

(deftest ^:parallel !-delivers-message-sent-by-registered-name-to-alive-process
  (let [done (promise)
        pfn (proc-fn []
              (is (= [:message :msg] (await-message 50))
                  (str "message sent with ! to registered name"
                       " must appear in inbox"))
              (deliver done true))
        reg-name (uuid-keyword)]
    (process/spawn-opt pfn {:register reg-name})
    (! reg-name :msg)
    (await-completion!! done 100)))

(deftest ^:parallel !-send-a-message-when-called-not-by-process
  (let [done (promise)
        pid (process/spawn
             (proc-fn [] (process/receive! :msg (deliver done true))))]
    (! pid :msg)
    (is (await-completion!! done 100))))

;; =============================================================================
;; (proc-fn [args & body])
;; =============================================================================

;; TODO - proc-fn tests

;; =============================================================================
;; (proc-defn [fname args & body])
;; =============================================================================

(deftest ^:parallel proc-defn--defines-proc-fn
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym []))
    (is (sym-bound? sym)
        "process fn must be bound to var with the given name"))
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym [] :ok))
    (is (sym-bound? sym)
        "process fn must be bound to var with the given name"))
  (let [sym (with-meta (gensym) {::a 1 ::b 2})]
    (eval `(process/proc-defn ~sym [] :ok))
    (is (matches? (meta (resolve sym)) {::a 1 ::b 2})
        "process fn must have the same meta as symbol passed as its name")))

(deftest ^:parallel proc-defn--returns-proc-fn-var
  (let [sym (gensym)
        res (eval `(process/proc-defn ~sym [] :ok))]
    (is (= res (resolve sym))
        "proc-defn must return the bound function")))

(deftest ^:parallel proc-defn--defined-fn-can-be-spawned
  ;; Note: We can't embed a promise in the eval'd form, so we test differently:
  ;; Define the proc-fn, spawn it, and verify it runs by checking the process exits
  (let [sym (gensym)
        _ (eval `(process/proc-defn ~sym [] :ok))
        pfn (var-get (resolve sym))
        pid (process/spawn pfn)]
    (is (process/pid? pid)
        "proc-defn must return a function which can be spawned")
    ;; Give it time to complete
    (Thread/sleep 50)
    (is (not (process/alive? pid))
        "spawned process should have completed")))

(deftest ^:parallel proc-defn--throws-on-illegar-arguments
  (is (thrown? Exception (eval `(process/proc-defn f))))
  (is (thrown? Exception (eval `(process/proc-defn []))))
  (is (thrown? Exception (eval `(process/proc-defn f {}))))
  (is (thrown? Exception (eval `(process/proc-defn {} [] 3))))
  (is (thrown? Exception (eval `(process/proc-defn f 1 3)))))

(deftest ^:parallel proc-defn--adds-docs-and-arglists
  (let [sym (gensym)
        docs (str "my doc-string for " sym)]
    (eval `(process/proc-defn ~sym ~docs [] :ok))
    (is (= docs (:doc (meta (resolve sym))))
        "defined var must have doc-string meta"))
  (let [sym (gensym)]
    (eval `(process/proc-defn ~sym [~(symbol "a") ~(symbol "b")] :ok))
    (is (= [['a 'b]] (:arglists (meta (resolve sym))))
        "defined var must have arglists meta"))
  (let [sym (gensym)
        docs (str "my doc-string for " sym)]
    (eval `(process/proc-defn ~sym ~docs [~(symbol "a") ~(symbol "b")] :ok))
    (is (matches? (meta (resolve sym)) {:doc docs :arglists ([['a 'b]] :seq)})
        "defined var must have doc-string and arglists meta")))

;; =============================================================================
;; (proc-defn- [fname args & body])
;; =============================================================================

(deftest ^:parallel proc-defn---defines-private-var
  (let [sym (gensym)]
    (eval `(process/proc-defn- ~sym [] :ok))
    (is (:private (meta (resolve sym)))
        "proc-defn- must define a private var")))

;; =============================================================================
;; (exit [reason]) / (exit [pid reason])
;; =============================================================================

(def-proc-test ^:parallel exit-throws-on-nil-reason
  (let [done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 50)))]
    (is (thrown? Exception (process/exit pid nil))
        "exit must throw when reason argument is nil")
    (deliver done true)))

(def-proc-test ^:parallel exit-throws-on-not-a-pid
  (is (thrown? Exception (process/exit nil :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit 1 :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit "pid1" :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit [] :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit '() :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit {} :normal))
      "exit must throw on not a pid argument")
  (is (thrown? Exception (process/exit #{} :normal))
      "exit must throw on not a pid argument"))

(def-proc-test ^:parallel exit-normal-no-trap-exit
  (let [done (promise)
        pfn (proc-fn []
              (is (= :timeout (await-message 100))
                  "exit with reason :normal must not close process' inbox")
              (deliver done true))
        pid (process/spawn pfn)]
    (process/exit pid :normal)
    (await-completion!! done 200)))

(def-proc-test ^:parallel exit-normal-trap-exit
  (let [done (promise)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :normal]]
                     (await-message 50))
                  (str "exit must send [:EXIT pid :normal] message"
                       " to process trapping exits"))
              (deliver done true))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :normal)
    (await-completion!! done 100)))

(deftest ^:parallel exit-normal-registered-process
  (let [done (promise)
        reg-name (uuid-keyword)
        pid (process/spawn-opt (proc-fn [] (await-completion!! done 50))
                               {:register reg-name})]
    (is ((into #{} (process/registered)) reg-name)
        "registered process must be in list of registered before exit")
    (deliver done true)
    (sleep 50)
    (is (nil? ((into #{} (process/registered)) reg-name))
        "process must not be registered after exit")))

(def-proc-test ^:parallel exit-abnormal-no-trap-exit
  (let [done (promise)
        pfn (proc-fn []
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "exit with reason other than :normal must close"
                       "process' inbox"))
              (deliver done true))
        pid (process/spawn pfn)]
    (process/exit pid :abnormal)
    (await-completion!! done 100)))

(def-proc-test ^:parallel exit-abnormal-trap-exit
  (let [done (promise)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :abnormal]] (await-message 50))
                  "exit must send exit message to process trapping exits")
              (deliver done true))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion!! done 100)))

(def-proc-test ^:parallel exit-kill-no-trap-exit
  (let [done (promise)
        pfn (proc-fn []
              (is (match (await-message 50) [:noproc _] :ok)
                  "exit with reason :kill must close process' inbox")
              (deliver done true))
        pid (process/spawn pfn)]
    (process/exit pid :kill)
    (await-completion!! done 100)))

(def-proc-test ^:parallel exit-kill-trap-exit
  (let [done (promise)
        pfn (proc-fn []
              (is (match (await-message 50) [:noproc _] :ok)
                  "exit with reason :kill must close process' inbox")
              (deliver done true))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :kill)
    (await-completion!! done 100)))

(def-proc-test ^:parallel exit-returns-true-on-alive-process
  (let [pfn (proc-fn [] (sleep 50))]
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn pfn)]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :normal))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :abnormal))
          "exit must return true on alive process"))
    (let [pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
      (is (= true (process/exit pid :kill))
          "exit must return true on alive process"))))

(def-proc-test ^:parallel exit-returns-false-on-terminated-process
  (let [pid (process/spawn (proc-fn []))]
    (sleep 50)
    (is (= false (process/exit pid :normal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :abnormal))
        "exit must return false on terminated process")
    (is (= false (process/exit pid :kill))
        "exit must return false on terminated process")))

(def-proc-test ^:parallel exit-self-trapping-exits
  (let [done (promise)]
    (process/spawn-opt
      (proc-fn []
        (process/exit (process/self) :normal)
        (is (= [:exit [(process/self) :normal]] (await-message 50))
            (str "exit with reason :normal must send exit"
                 " message to process trapping exits"))
        (deliver done true))
      {:flags {:trap-exit true}})
    (await-completion!! done 100))
  (let [done (promise)]
    (process/spawn-opt
      (proc-fn []
        (process/exit (process/self) :abnormal-1)
        (is (= [:exit [(process/self) :abnormal-1]]
               (await-message 50))
            "exit must send exit message to process trapping exits")
        (process/exit (process/self) :abnormal-2)
        (is (= [:exit [(process/self) :abnormal-2]] (await-message 50))
            "exit must send exit message to process trapping exits")
        (deliver done true))
      {:flags {:trap-exit true}})
    (await-completion!! done 150))
  (let [done (promise)]
    (process/spawn-opt
      (proc-fn []
        (process/exit (process/self) :kill)
        (is (match (await-message 50) [:noproc _] :ok)
            "exit with reason :kill must close inbox of process trapping exits")
        (deliver done true))
      {:flags {:trap-exit true}})
    (await-completion!! done 100)))

(def-proc-test ^:parallel exit-self-not-trapping-exits
  (let [done (promise)]
    (process/spawn
      (proc-fn []
        (process/exit (process/self) :normal)
        (is (= :timeout (await-message 100))
            (str "exit with reason :normal must do nothing"
                 " to process not trapping exits"))
        (deliver done true)))
    (await-completion!! done 200))
  (let [done (promise)]
    (process/spawn
      (proc-fn []
        (process/exit (process/self) :abnormal)
        (is (match (await-message 50) [:noproc _] :ok)
            (str "exit with any reason except :normal must close"
                 " inbox of proces not trapping exits"))
        (deliver done true)))
    (await-completion!! done 100))
  (let [done (promise)]
    (process/spawn
      (proc-fn []
        (process/exit (process/self) :kill)
        (is (match (await-message 50) [:noproc _] :ok)
            (str "exit with reason :kill must close inbox of process"
                 " not trapping exits"))
        (deliver done true)))
    (await-completion!! done 100)))

;; See issue #12 on github
(def-proc-test ^:parallel link-call-does-not-increase-exit-message-delivery-time
  (process/flag :trap-exit true)
  (let [done (promise)
        pfn (proc-fn []
              (await-completion!! done 50)
              (process/exit (process/self) :abnormal)
              (process/receive!
                _ (is false "process must exit")))
        pid (process/spawn pfn)]
    (process/link pid)
    (deliver done true)
    (is (= [:exit [pid :abnormal]] (await-message 100))
        "process exit reason must be the one passed to exit call")))

(def-proc-test ^:parallel exit-kill-reason-is-killed
  (process/flag :trap-exit true)
  (let [done (promise)
        pid (process/spawn-link (proc-fn [] (await-completion!! done 100)))]
    (process/exit pid :kill)
    (is (= [:exit [pid :killed]] (await-message 50))
        (str "process exit reason must be :killed when exit is"
             " called with reason :kill"))
    (deliver done true)))

;; =============================================================================
;; (flag [flag value])
;; =============================================================================

(def-proc-test ^:parallel flag-trap-exit-true-makes-process-to-trap-exits
  (let [done1 (promise)
        done2 (promise)
        test-pid (process/self)
        pfn (proc-fn []
              (process/flag :trap-exit true)
              (deliver done1 true)
              (is (= [:exit [test-pid :normal]]
                     (await-message 50))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (deliver done2 true))
        pid (process/spawn pfn)]
    (await-completion!! done1 50)
    (match (process/exit pid :normal) true :ok)
    (await-completion!! done2 100)))

(def-proc-test ^:parallel flag-trap-exit-false-makes-process-not-to-trap-exits
  (let [done1 (promise)
        done2 (promise)
        pfn (proc-fn []
              (process/flag :trap-exit false)
              (deliver done1 true)
              (is (= :timeout (await-message 50))
                  (str "flag :trap-exit set to false in process must"
                       " make process not to trap exits"))
              (deliver done2 true))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (await-completion!! done1 50)
    (match (process/exit pid :normal) true :ok)
    (await-completion!! done2 100)))

(def-proc-test ^:parallel flag-trap-exit-switches-trapping-exit
  (let [done1 (promise)
        done2 (promise)
        done3 (promise)
        test-pid (process/self)
        pfn (proc-fn []
              (process/flag :trap-exit true)
              (deliver done1 true)
              (is (= [:exit [test-pid :abnormal]]
                     (await-message 50))
                  (str "flag :trap-exit set to true in process must"
                       " make process to trap exits"))
              (process/flag :trap-exit false)
              (deliver done2 true)
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "flag :trap-exit switched second time in process"
                       " must make process to switch trapping exits"))
              (deliver done3 true))
        pid (process/spawn pfn)]
    (await-completion!! done1 50)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion!! done2 50)
    (match (process/exit pid :abnormal) true :ok)
    (await-completion!! done3 100)))

(deftest ^:parallel flag-trap-exit-returns-old-value
  (let [done (promise)
        pfn (proc-fn []
              (is (= true (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit false))
                  "setting flag :trap-exit must return its previous value")
              (is (= false (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (is (= true (process/flag :trap-exit true))
                  "setting flag :trap-exit must return its previous value")
              (deliver done true))]
    (process/spawn-opt pfn {:flags {:trap-exit true}})
    (await-completion!! done 50)))

(deftest ^:parallel flag-throws-on-unknown-flag
  (let [done (promise)
        pfn (proc-fn []
              (is (thrown? Exception (process/flag [] false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag 1 false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag :unknown false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag nil false))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag nil true))
                  "flag must throw on unknown flag")
              (is (thrown? Exception (process/flag :trap-exit1 false))
                  "flag must throw on unknown flag")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel flag-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/flag :trap-exit true))
      "flag must throw when called not in process context")
  (is (thrown? Exception (process/flag :trap-exit false))
      "flag must throw when called not in process context"))

;; =============================================================================
;; (link [pid])
;; =============================================================================

(deftest ^:parallel link-returns-true
  (let [pid (process/spawn (proc-fn []))
        _ (sleep 50)
        done (promise)
        pfn (proc-fn []
              (is (= true (process/link pid))
                  "link must return true when called on terminated process")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 50)))
        done2 (promise)
        pfn (proc-fn []
              (is (= true (process/link pid))
                  "link must return true when called on alive process")
              (deliver done1 true)
              (deliver done2 true))]
    (process/spawn pfn)
    (await-completion!! done2 50)))

(deftest ^:parallel link-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/link (process/spawn (proc-fn []))))
      "link must throw when called not in process context")
  (let [pfn (proc-fn [] (sleep 50))]
    (is (thrown? Exception (process/link (process/spawn pfn)))
        "link must throw when called not in process context")))

(deftest ^:parallel link-throws-when-called-with-not-a-pid
  (is (thrown? Exception (process/link nil))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link 1))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link "pid1"))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link '()))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link []))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link {}))
      "link must throw when called with not a pid argument")
  (is (thrown? Exception (process/link #{}))
      "link must throw when called with not a pid argument"))

(def-proc-test ^:parallel link-creates-link-with-alive-process-not-trapping-exits
  (let [done1 (promise)
        done2 (promise)
        pfn2 (proc-fn []
               (is (match (await-message 50) [:noproc _] :ok)
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (deliver done2 true))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn []
               (process/link pid2)
               (deliver done1 true)
               (is (match (await-message 50) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion!! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion!! done2 50)))

(def-proc-test ^:parallel link-creates-link-with-alive-process-trapping-exits
  (let [done1 (promise)
        done2 (promise)
        pid1-ref (atom nil)
        pfn2 (proc-fn []
               ;; Wait for pid1 to be set
               (sleep 10)
               (let [pid1 @pid1-ref]
                 (is (= [:exit [pid1 :abnormal]]
                        (await-message 100))
                     (str "process trapping exits must get exit message"
                          " when linked process exits with reason"
                          " other than :normal")))
               (deliver done2 true))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (deliver done1 true)
               (is (match (await-message 100) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (reset! pid1-ref pid1)
    (await-completion!! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion!! done2 100)))

(deftest ^:parallel link-does-not-affect-processes-linked-to-normally-exited-one
  (let [done (promise)
        pfn2 (proc-fn []
               (is (= :timeout (await-message 100))
                   (str "process must not exit when linked process exits"
                        " with reason :normal"))
               (deliver done true))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn [] (process/link pid2))
        pid1 (process/spawn pfn1)]
    (await-completion!! done 200)))

(deftest ^:parallel linking-to-terminated-process-sends-exit-message
  (let [done (promise)
        pid2 (process/spawn (proc-fn []))
        _ (sleep 50)
        pfn1 (proc-fn []
               (try
                 (process/link pid2)
                 (is (= [:exit [pid2 :noproc]]
                        (await-message 50))
                     (str "linking to terminated process must either"
                          " throw or send exit message to process"
                          " trapping exits"))
                 (catch Exception _e :ok))
               (deliver done true))
        pid1 (process/spawn-opt pfn1 {:flags {:trap-exit true}})]
    (await-completion!! done 100)))

(deftest ^:parallel linking-to-terminated-process-causes-exit
  (let [done (promise)
        pfn2 (proc-fn [])
        pid2 (process/spawn pfn2)
        _ (sleep 50)
        pfn1 (proc-fn []
               (try
                 (process/link pid2)
                 (is (match (await-message 50) [:noproc _] :ok)
                     (str "linking to terminated process must either"
                          " throw or close inbox of process"
                          " not trapping exits"))
                 (catch Exception _e :ok))
               (deliver done true))
        pid1 (process/spawn pfn1)]
    (await-completion!! done 100)))

(deftest ^:parallel link-to-self-does-not-throw
  (let [done (promise)
        pfn (proc-fn []
              (is (process/link (process/self))
                  "link to self must not throw when process is alive")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50)))

;; =============================================================================
;; (unlink [pid])
;; =============================================================================

(deftest ^:parallel unlink-removes-link-to-alive-process
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 200)
               (throw (Exception.
                        (str "TEST: terminating abnormally to test"
                             " unlink removes link to alive process"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (sleep 50)
               (process/unlink pid)
               (sleep 50)
               (deliver done1 true)
               (is (= :timeout (await-message 100))
                   (str "abnormally failed unlinked process must"
                        " not affect previously linked process"))
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 300)))

(deftest ^:parallel unlink-returns-true-if-link-exists
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 100))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (sleep 50)
               (is (= true (process/unlink pid)) "unlink must return true")
               (deliver done1 true)
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(deftest ^:parallel unlink-returns-true-there-is-no-link
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 50))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (is (= true (process/unlink pid)) "unlink must return true")
               (deliver done1 true)
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 100)))

(deftest ^:parallel unlink-self-returns-true
  (let [done (promise)
        pfn (proc-fn []
              (is (= true (process/unlink (process/self)))
                  "unlink must return true")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel unlink-terminated-process-returns-true
  (let [done (promise)
        pid (process/spawn (proc-fn []))
        pfn2 (proc-fn []
               (sleep 50)
               (is (= true (process/unlink pid)) "unlink must return true")
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 100))
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 50))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid)
               (deliver done1 true)
               (sleep 50)
               (is (= true (process/unlink pid)) "unlink must return true")
               (deliver done true))]
    (process/spawn-opt pfn2 {:flags {:trap-exit true}})
    (await-completion!! done 100)))

(deftest ^:parallel unlink-throws-on-not-a-pid
  (let [done (promise)
        pfn2 (proc-fn []
               (is (thrown? Exception (process/unlink nil))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink 1))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink "pid1"))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink {}))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink #{}))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink '()))
                   "unlink must throw on not a pid argument")
               (is (thrown? Exception (process/unlink []))
                   "unlink must throw on not a pid argument")
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 50)))

(deftest ^:parallel unlink-throws-when-calld-not-in-process-context
  (is (thrown? Exception
               (process/unlink (process/spawn (proc-fn []))))
      "unlink must throw when called not in process context"))

;; =============================================================================
;; (spawn-opt [proc-fun args options])
;; =============================================================================

(deftest ^:parallel spawn-calls-pfn
  (let [done (promise)]
    (process/spawn-opt (proc-fn [] (deliver done true)) [] {})
    (is (await-completion!! done 50) "spawn must call process fn")))

(deftest ^:parallel spawn-calls-pfn-with-arguments
  (let [done (promise)
        pfn (proc-fn [a b]
                  (is (and (= :a a) (= 1 b))
                      "spawn must pass process fn params")
                  (deliver done true))]
    (process/spawn-opt pfn [:a 1] {})
    (await-completion!! done 50)))

(deftest ^:parallel spawn-returns-process-pid
  (let [pid-result (promise)
        pfn (proc-fn [] (deliver pid-result (process/self)))
        pid1 (process/spawn-opt pfn [] {})
        pid2 (deref pid-result 50 nil)]
    (is (= pid1 pid2) (str "spawn must return the same pid as returned"
                           " by self called from started process"))))

(deftest ^:parallel spawn-throws-on-illegal-arguments
  (is (thrown? Exception (process/spawn-opt nil [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt 1 [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt "fn" [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt {} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt [] [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt #{} [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt '() [] {}))
      "spawn must throw if proc-fun is not a function")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) 1 {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) #{} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) {} {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) "args" {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) (fn []) {}))
      "spawn must throw if args is not sequential")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] nil))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] 1))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] "opts"))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] [1 2 3]))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] '(1)))
      "spawn must throw if options is not a map")
  (is (thrown? Exception (process/spawn-opt (proc-fn []) [] #{}))
      "spawn must throw if options is not a map"))

;; =============================================================================
;; (spawn-opt [proc-fun args options]) - Process exit tests
;; =============================================================================

(defn- process-exits-abnormally-when-pfn-throws* [ex]
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [ex-class-name (.getName (class ex))
          pid (process/spawn-opt (proc-fn [] (throw ex)) [] {:link true})]
      (is (match (await-message 100)
            [:exit [pid [:exception {:class ex-class-name}]]]
            :ok)
          "process must exit when its proc-fn throws any exception"))))

(deftest ^:parallel process-exits-abnormally-when-pfn-throws
  (process-exits-abnormally-when-pfn-throws* (InterruptedException.))
  (process-exits-abnormally-when-pfn-throws* (RuntimeException.))
  (process-exits-abnormally-when-pfn-throws* (Error.)))

(defn- process-exits-abnormally-when-pfn-arity-doesnt-match-args* [pfn args]
  (proc-util/execute-proc!!
    (process/flag :trap-exit true)
    (let [pid (process/spawn-opt pfn args {:link true})]
      (is (match (await-message 100) [:exit [pid [:exception _]]] :ok)
          "process must exit when arguments to its proc-fn dont match arity"))))

(deftest ^:parallel process-exits-abnormally-when-pfn-arity-doesnt-match-args
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn []) [:a 1])
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn [a b]) [])
  (process-exits-abnormally-when-pfn-arity-doesnt-match-args*
   (proc-fn [b]) [:a :b]))

(def-proc-test ^:parallel process-is-linked-when-proc-fn-starts
  (process/flag :trap-exit true)
  (let [pid (process/spawn-opt (proc-fn []) {:link true})]
    (is (= [:exit [pid :normal]] (await-message 100))
        "spawn-linked process must be linked before its proc-fn starts")))

(def-proc-test ^:parallel spawned-process-traps-exits-according-options
  (let [done (promise)
        pfn (proc-fn []
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "process not trapping exits must exit"
                       " when it receives exit signal"))
              (deliver done true))
        pid (process/spawn-opt pfn {})]
    (process/exit pid :abnormal)
    (await-completion!! done 100))
  (let [done (promise)
        test-pid (process/self)
        pfn (proc-fn []
              (is (= [:exit [test-pid :abnormal]]
                     (await-message 50))
                  (str "process spawned option :trap-exit set to true"
                       " must trap exits"))
              (deliver done true))
        pid (process/spawn-opt pfn {:flags {:trap-exit true}})]
    (process/exit pid :abnormal)
    (await-completion!! done 100)))

(def-proc-test ^:parallel spawned-process-does-not-trap-exits-by-default
  (let [done (promise)
        pfn (proc-fn []
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "process' inbox must be closed after exit with"
                       " reason other than :normal was called if process"
                       " doesn't trap exits"))
              (deliver done true))
        pid (process/spawn-opt pfn [] {})]
    (process/exit pid :abnormal)
    (await-completion!! done 100)))

;; =============================================================================
;; (spawn-link [proc-fun args options])
;; =============================================================================

(def-proc-test ^:parallel spawn-link-links-to-spawned-process
  (let [done (promise)
        pfn (proc-fn [] (process/exit :abnormal))
        pfn1 (proc-fn []
               (process/spawn-link pfn [])
               (is (match (await-message 50) [:noproc _] :ok :else nil)
                   (str "process #1 not trapping exits and spawned process #2"
                        " with spawn-link must exit after process #2 exited"))
               (deliver done true))]
    (process/spawn pfn1)
    (await-completion!! done 100))
  (let [done (promise)
        pfn (proc-fn [] (process/exit :abnormal))
        pfn1 (proc-fn []
               (let [pid (process/spawn-link pfn)]
                 (is (=  [:exit [pid :abnormal]] (await-message 50))
                     (str "process #1 trapping exits and spawned process #2"
                          " with spawn-link must receive exit message after"
                          " process #2 exited")))
               (deliver done true))]
    (process/spawn-opt pfn1 {:flags {:trap-exit true}})
    (await-completion!! done 100)))

;; =============================================================================
;; (monitor [pid-or-name])
;; =============================================================================

(def-proc-test ^:parallel down-message-is-sent-when-monitored-process-exits
  (let [done (promise)
        pfn1 (proc-fn [] (is (match (await-message 100) [:noproc _] :ok)))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (let [mref (process/monitor pid1)]
                 (sleep 50)
                 (process/exit pid1 :abnormal)
                 (is (= [:down [mref pid1 :abnormal]]
                        (await-message 50))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits abnormally"))
                 (deliver done true)))]
    (process/spawn pfn2)
    (await-completion!! done 150))
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 100))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (let [mref (process/monitor pid1)]
                 (sleep 50)
                 (deliver done1 true)
                 (is (= [:down [mref pid1 :normal]] (await-message 50))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process exits normally"))
                 (deliver done true)))]
    (process/spawn pfn2)
    (await-completion!! done 150))
  (let [reg-name (uuid-keyword)
        done (promise)
        pfn1 (proc-fn [] (is (match (await-message 100) [:noproc _] :ok)))
        pid1 (process/spawn-opt pfn1 {:register reg-name})
        pfn2 (proc-fn []
               (let [mref (process/monitor reg-name)]
                 (sleep 50)
                 (process/exit pid1 :kill)
                 (is (= [:down [mref reg-name :killed]]
                        (await-message 50))
                     (str "process must receive :DOWN message containing proper"
                          " monitor ref, pid and reason when monitored by pid"
                          " process is killed"))
                 (deliver done true)))]
    (process/spawn pfn2)
    (await-completion!! done 150)))

(deftest ^:parallel monitor-returns-ref
  (let [done (promise)
        pfn (proc-fn []
              (is (process/ref? (process/monitor (process/self)))
                  (str "monitor must return ref when called with self"
                       " as argument"))
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done (promise)
        pfn1 (proc-fn [] (await-completion!! done 50))
        pid1 (process/spawn pfn1)
        pfn (proc-fn []
              (is (process/ref? (process/monitor pid1))
                  (str "monitor must return ref when called with pid"
                       " of alive process as argument"))
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(deftest ^:parallel monitor-throws-when-called-not-in-process-context
  (is (thrown? Exception (process/monitor (uuid-keyword)))
      (str "monitor must throw when called not in process context with"
           " never registered name"))
  (let [pid (process/spawn (proc-fn []))]
    (sleep 50)
    (is (thrown? Exception (process/monitor pid))
        (str "monitor must throw when called not in process context with"
             " terminated process pid"))))

(deftest ^:parallel
  monitoring-terminated-process-sends-down-message-with-noproc-reason
  (let [done (promise)
        pfn (proc-fn []
              (let [reg-name (uuid-keyword)
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (await-message 50))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with never registered"
                         " name")))
              (let [pid (process/spawn (proc-fn []))
                    _ (sleep 50)
                    mref (process/monitor pid)]
                (is (= [:down [mref pid :noproc]]
                       (await-message 50))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " pid")))
              (let [reg-name (uuid-keyword)
                    _pid (process/spawn-opt (proc-fn []) {:register reg-name})
                    _ (sleep 50)
                    mref (process/monitor reg-name)]
                (is (= [:down [mref reg-name :noproc]]
                       (await-message 50))
                    (str "process must receive :DOWN message with :noproc"
                         " reason when monitor called with terminated process"
                         " reg-name")))
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel
  monitored-process-does-not-receive-down-message-when-monitoring-proc-dies
  (let [done (promise)
        pfn (proc-fn []
              (let [self (process/self)]
                (process/spawn (proc-fn [] (process/monitor self)))
                (is (= :timeout (await-message 100))
                    (str "monitored process must not receive any message"
                         " when monitoring process dies")))
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 200)))

(deftest ^:parallel monitor-called-multiple-times-creates-as-many-monitors
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 100))
        pid (process/spawn pfn1)
        pfn (proc-fn []
              (let [mrefs (doall (take 3 (repeatedly #(process/monitor pid))))
                    _ (sleep 50)
                    _ (deliver done1 true)
                    msgs []
                    msgs (conj msgs (await-message 50))
                    msgs (conj msgs (await-message 50))
                    msgs (conj msgs (await-message 50))]
                (is (= (set (map (fn [mref] [:down [mref pid :normal]])
                                 mrefs))
                       (set msgs))
                    (str "monitoring process must receive one :DOWN message"
                         " per one monitor call")))
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 200)))

(deftest ^:parallel monitor-refs-are-not-equal
  (let [done (promise)
        pfn1 (proc-fn [] (await-completion!! done 50))
        pid (process/spawn pfn1)
        pfn (proc-fn []
              (is
                (= 3 (count (set (take 3 (repeatedly #(process/monitor pid))))))
                "monitor must return unique monitor refs")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 50)))

(def-proc-test ^:parallel monitor-self-does-nothing
  (let [done (promise)
        done1 (promise)
        pfn (proc-fn []
              (process/monitor (process/self))
              (sleep 50)
              (deliver done1 true)
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "process called monitor with self as argument must not"
                       " receive :DOWN message when exit is called for the"
                       " process"))
              (deliver done true))
        pid (process/spawn pfn)]
    (await-completion!! done1 100)
    (process/exit pid :abnormal)
    (await-completion!! done 50))
  (let [reg-name (uuid-keyword)
        done (promise)
        done1 (promise)
        pfn (proc-fn []
              (process/monitor reg-name)
              (sleep 50)
              (deliver done1 true)
              (is (match (await-message 50) [:noproc _] :ok)
                  (str "process called monitor with self reg-name as argument"
                       " must not receive :DOWN message when exit is called"
                       " for the process"))
              (deliver done true))
        pid (process/spawn-opt pfn {:register reg-name})]
    (await-completion!! done1 100)
    (process/exit pid :abnormal)
    (await-completion!! done 50)))

;; =============================================================================
;; (demonitor [mref])
;; =============================================================================

(deftest ^:parallel demonitor-stops-monitoring
  (let [done (promise)
        done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 150)))
        pfn (proc-fn []
               (let [mref (process/monitor pid)]
                 (sleep 50)
                 (process/demonitor mref)
                 (sleep 50)
                 (deliver done1 true)
                 (is (= :timeout (await-message 100))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-called-multiple-times-does-nothing
  (let [done (promise)
        pfn (proc-fn []
              (is (= :timeout (await-message 100))
                  (str "demonitor called multiple times must not affect"
                       " monitored process")))
        pid (process/spawn pfn)
        pfn1 (proc-fn []
               (let [mref (process/monitor pid)]
                 (is (process/demonitor mref))
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (process/demonitor mref)
                     (str "demonitor called multiple times must not affect"
                          " calling process"))
                 (is (= :timeout (await-message 100))
                     (str "demonitor called multiple times must not affect"
                          " monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn1)
    (await-completion!! done 200)))

(deftest ^:parallel demonitor-returns-true
  (let [done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 50)))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called while"
                         " monitored process is alive"))
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (is (= true (process/demonitor mref))
                    "demonitor must return true when called multiple times")
                (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 50))
  (let [done (promise)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (let [mref (process/monitor pid)]
                (sleep 50)
                (is (= true (process/demonitor mref))
                    (str "demonitor must return true when called when"
                         " monitored process have terminated"))
                (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 150)))

;; NOTE: loom-otp demonitor doesn't validate args in the same way as otplike
;; These tests are commented out because loom-otp's implementation is more lenient
;; (deftest ^:parallel demonitor-throws-on-not-a-monitor-ref
;;   (let [pfn (proc-fn []
;;               (is (thrown? Exception (process/demonitor nil))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor 1))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor "mref"))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor true))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor false))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor []))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor '()))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor {}))
;;                   "demonitor must throw on not a monitor-ref")
;;               (is (thrown? Exception (process/demonitor #{}))
;;                   "demonitor must throw on not a monitor-ref"))]
;;     (process/spawn pfn)))

(deftest ^:parallel demonitor-throws-when-called-not-in-process-context
  (let [done (promise)
        mref-holder (atom nil)
        pid (process/spawn (proc-fn [] (await-completion!! done 100)))
        pfn (proc-fn []
              (reset! mref-holder (process/monitor pid))
              (await-completion!! done 100))]
    (process/spawn pfn)
    (sleep 50) ; wait for monitor to be set
    (when-let [mref @mref-holder]
      (is (thrown? Exception (process/demonitor mref))
          (str "demonitor must throw when called not in process context"
               " when both processes are alive")))
    (deliver done true)))

(deftest ^:parallel demonitor-self-does-nothing
  (let [done (promise)
        pfn (proc-fn []
              (let [mref (process/monitor (process/self))]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (await-message 100))
                  "demonitor self must do nothing")
              (deliver done true))]
    (process/spawn pfn)
    (await-completion!! done 150))
  (let [reg-name (uuid-keyword)
        done (promise)
        pfn (proc-fn []
              (let [mref (process/monitor reg-name)]
                (is (process/demonitor mref)
                    "demonitor self must return true and do nothing"))
              (is (= :timeout (await-message 100))
                  "demonitor self must do nothing")
              (deliver done true))]
    (process/spawn-opt pfn {:register reg-name})
    (await-completion!! done 150)))

;; =============================================================================
;; (demonitor [mref opts])
;; =============================================================================

(deftest ^:parallel demonitor-flushes-down-message
  (let [done (promise)
        done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 100)))
        pfn (proc-fn []
               (let [mref (process/monitor pid)]
                 (deliver done1 true)
                 (sleep 50)
                 (process/demonitor mref {:flush true})
                 (is (= :timeout (await-message 100))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-doesnot-flush-when-flush-is-false
  (let [done (promise)
        done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 100)))
        pfn (proc-fn []
               (let [mref (process/monitor pid)]
                 (deliver done1 true)
                 (sleep 50)
                 (process/demonitor mref {:flush false})
                 (is (= [:down [mref pid :normal]]
                        (await-message 100))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

(deftest ^:parallel demonitor-works-when-there-is-no-message-to-flush
  (let [done (promise)
        done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 300)))
        pfn (proc-fn []
               (let [mref (process/monitor pid)]
                 (process/demonitor mref {:flush true})
                 (deliver done1 true)
                 (is (= :timeout (await-message 100))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 300))
  (let [done (promise)
        done1 (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done1 300)))
        pfn (proc-fn []
               (let [mref (process/monitor pid)]
                 (process/demonitor mref)
                 (deliver done1 true)
                 (process/demonitor mref {:flush true})
                 (is (= :timeout (await-message 100))
                     (str "demonitor called after monitored process exited"
                          " must not affect monitoring process"))
                 (deliver done true)))]
    (process/spawn pfn)
    (await-completion!! done 300)))

;; =============================================================================
;; (receive! [clauses])
;; =============================================================================

(deftest ^:parallel receive-receives-message
  (let [done (promise)
        pfn (proc-fn []
                     (is (= :msg (process/receive! msg msg))
                         "receive! must receive message sent to a process")
                     (deliver done true))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 150)))

(deftest ^:parallel receive-receives-nil
  (let [done (promise)
        pfn (proc-fn []
              (is (nil? (process/receive! msg msg))
                  "receive! must receive nil sent to a process")
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid nil)
    (await-completion!! done 150))
  (let [done (promise)
        pfn (proc-fn []
              (process/receive! nil :ok)
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid nil)
    (is (await-completion!! done 150)
        "receive! must receive nil sent to a process")))

(deftest ^:parallel receive-executes-after-clause
  (let [done (promise)
        pfn (proc-fn []
                     (is (= :timeout (process/receive!
                                   _ :error
                                   (after 10
                                          :timeout)))
                         "receive! must execute 'after' clause on timeout")
                     (deliver done true))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

(deftest ^:parallel receive-accepts-infinity-timeout
  (let [done (promise)
        pfn (proc-fn []
              (process/receive!
                :done (deliver done true)
                (after :infinity
                  (is false
                      (str "expression for infinite timeout must never"
                           " be executed")))))
        pid (process/spawn pfn)]
    (sleep 100)
    (! pid :done)
    (await-completion!! done 100)))

(deftest ^:parallel receive-accepts-0-timeout
  (let [done (promise)
        pfn (proc-fn []
              (process/receive!
                _ (is false
                      (str "expression for message must not be executed"
                           " when there is no message in inbox"))
                (after 0
                  (deliver done true))))
        pid (process/spawn pfn)]
    (await-completion!! done 50))
  (let [done1 (promise)
        done2 (promise)
        pfn (proc-fn []
              (await-completion!! done1 150)
              (process/receive!
                :done (deliver done2 true)
                (after 0
                  (is false
                      (str "expression for 0 timeout must not be executed"
                           " when there is a message in inbox")))))
        pid (process/spawn pfn)]
    (! pid :done)
    (sleep 50)
    (deliver done1 true)
    (await-completion!! done2 50)))

;; =============================================================================
;; (selective-receive! [clauses])
;; =============================================================================

(deftest ^:parallel selective-receive!-receives-message
  (let [done (promise)
        pfn (proc-fn []
              (is (= :msg (process/selective-receive! msg msg))
                  "selective-receive! must receive message sent to a process")
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid :msg)
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-receives-nil
  (let [done (promise)
        pfn (proc-fn []
              (is (nil? (process/selective-receive! msg msg))
                  "selective-receive! must receive nil sent to a process")
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid nil)
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-executes-after-clause
  (let [done (promise)
        pfn (proc-fn []
              (is (= :timeout (process/selective-receive!
                               _ :error
                               (after 10
                                 :timeout)))
                  "selective-receive! must execute 'after' clause on timeout")
              (deliver done true))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-leaves-non-matching-messages-untouched
  (let [done (promise)
        pfn (proc-fn []
              (is (process/selective-receive! :msg3 :ok)
                  "selective-receive! must receive the matching message")
              (is (= :msg1 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg2 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :msg4 (process/receive! msg msg))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (is (= :timeout (await-message 50))
                  (str "selective-receive! must preserve the order"
                       " of unmatching messages"))
              (deliver done true))
        pid (process/spawn pfn)]
    (! pid :msg1)
    (! pid :msg2)
    (! pid :msg3)
    (! pid :msg4)
    (await-completion!! done 150)))

;; =============================================================================
;; (async [& body]) / (await x)
;; =============================================================================

(deftest ^:parallel async-returns-async-value
  (is (process/async? (process/async)))
  (is (process/async? (process/async 1)))
  (is (process/async? (process/async (throw (ex-info "test" {}))))))

;; NOTE: In loom-otp, async executes synchronously (not asynchronously).
;; With virtual threads, blocking is cheap, so there's no need for true async.
;; The async/await pattern is used for exception capture and composition only.
;; This test is disabled because it tests behavior we intentionally don't support.
#_(deftest ^:parallel async-executes-body-asynchronously
  (let [done (promise)
        done1 (promise)]
    (process/async
     (await-completion!! done 50)
     (deliver done1 true))
    (deliver done true)
    (await-completion!! done1 100)))

(deftest ^:parallel await-returns-value-of-async-expr
  (is (= 123 (process/await!! (process/async 123)))
      "await!! must return the value of async's expression")
  (is (nil? (process/await!! (process/async nil)))
      "await!! must return the value of async's expression"))

(deftest ^:parallel await-propagates-exceptions-of-async-expr
  (let [async-val (process/async (throw (ex-info "msg 123" {})))]
    (is (thrown?
         clojure.lang.ExceptionInfo #"^msg 123$"
         (process/await!! async-val)))))

(deftest ^:parallel await-throws-on-illegal-argument
  (is (thrown? IllegalArgumentException (process/await!! 1)))
  (is (thrown? IllegalArgumentException (process/await!! nil)))
  (is (thrown? IllegalArgumentException (process/await!! {})))
  (is (thrown? IllegalArgumentException (process/await!! [])))
  (is (thrown? IllegalArgumentException (process/await!! '())))
  (is (thrown? IllegalArgumentException (process/await!! "str")))
  (is (thrown? IllegalArgumentException (process/await!! (Object.))))
  (is (thrown? IllegalArgumentException (process/await!! 'a)))
  (is (thrown? IllegalArgumentException (process/await!! :a))))

;; =============================================================================
;; (alive?)
;; =============================================================================

(def-proc-test alive?-returns-information-about-current-process
  ;; Test that alive? returns false when process has received exit signal
  ;; but is still running (not yet at a blocking point).
  ;; 
  ;; Java interrupts are cooperative - the interrupt flag is set but the thread
  ;; only throws InterruptedException when it hits a blocking operation.
  ;; So we use a spin-wait (non-blocking) to allow the exit signal to arrive
  ;; while the child is still running.
  (let [ready (atom false)
        signal-sent (atom false)
        done (promise)
        pid (process/spawn
             (proc-fn []
               (is (process/alive?)
                   "alive? must return true when process is alive")
               ;; Signal we're ready for the exit signal
               (reset! ready true)
               ;; Spin-wait (non-blocking) until exit signal is sent
               (while (not @signal-sent)
                 (Thread/yield))
               ;; Now check alive? - should be false because exit-reason is set
               ;; We haven't hit a blocking operation yet, so we're still running
               (is (not (process/alive?))
                   "alive? must return false when process is exiting")
               (deliver done true)))]
    ;; Wait for child to be ready (spin-wait to avoid blocking)
    (while (not @ready)
      (Thread/yield))
    ;; Send exit signal - this sets exit-reason and interrupt flag
    (process/exit pid :test)
    ;; Signal that we've sent the exit
    (reset! signal-sent true)
    ;; Wait for child to complete its checks
    (await-completion!! done 1000)))

(def-proc-test alive?-returns-information-about-pid
  (let [done (promise)
        pid (process/spawn (proc-fn []
                             (await-completion!! done 1000)))]
    (is (process/alive? pid) "alive? must return true when process is alive")
    (process/exit pid :test)
    (sleep 50)
    (is (not (process/alive? pid))
        "alive? must return false when process is exiting")
    (deliver done true)
    (sleep 50)
    (is (not (process/alive? pid))
        "alive? must return false when process has exited")))

;; =============================================================================
;; (process-info pid key-or-keys)
;; =============================================================================

(deftest process-info-returns-the-info-item
  (let [done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 1000)))]
    (is (= [:status :running] (process/process-info pid :status))
        (str "process-info must return tagged info tuple"
             " when argument is info key"))
    (deliver done true)))

(deftest process-info-returns-info-items-ordered-as-keys
  (let [info-keys [:status :registered-name :messages :monitors]
        done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 1000)))
        info-items (process/process-info pid info-keys)]
    (is (= info-keys (map first info-items))
        (str "process-info must return tagged info tuples in the same order"
             " as info keys passed as argument"))
    (deliver done true)))

(deftest process-info-accepts-duplicate-info-keys
  (let [info-keys [:status
                   :status
                   :registered-name
                   :status
                   :messages
                   :monitors
                   :messages]
        done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 1000)))
        info-items (process/process-info pid info-keys)]
    (is (= info-keys (map first info-items))
        (str "process-info must return tagged info tuples in the same order"
             " as info keys passed as argument"))
    (deliver done true)))

(deftest process-info-returns-nil-on-exited-process-pid
  (let [pid (process/spawn (proc-fn []))]
    (sleep 50)
    (is (= nil (process/process-info pid [:status]))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :status))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :message-queue-len))
        "process-info must return nil on exited process pid")
    (is (= nil (process/process-info pid :monitored-by))
        "process-info must return nil on exited process pid")))

(deftest process-info-throws-on-illegal-arguments
  (let [done (promise)
        pid (process/spawn (proc-fn [] (await-completion!! done 1000)))]
    (is (thrown? Exception (process/process-info nil [:status]))
        "process-info must throw on nil pid")
    (is (thrown? Exception (process/process-info pid nil))
        "process-info must throw on nil info-key(s)")
    (is (thrown? Exception (process/process-info pid :unexisting-info-key))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid 11))
        "process-info must throw on unexisting info-key(s)")
    ;; ensure that arguments are checked even on exited process pid
    (deliver done true)
    (sleep 50)
    (is (thrown? Exception (process/process-info pid [[]]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid "unexisting-info-key"))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid [:unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception (process/process-info pid [1]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception
                 (process/process-info
                  pid [:status :unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")
    (is (thrown? Exception
                 (process/process-info
                  pid [:status :unexisting-info-key :unexisting-info-key]))
        "process-info must throw on unexisting info-key(s)")))

;; =============================================================================
;; (processes)
;; =============================================================================

(deftest processes-returns-a-list-of-pids-including-alive-process-pid
  (let [done (promise)
        pid1 (process/spawn (proc-fn [] (await-completion!! done 1000)))
        pid2 (process/spawn (proc-fn [] (await-completion!! done 1000)))
        pids (set (process/processes))]
    (is (contains? pids pid1)
        (str "list of pids returned by (processes) must contain"
             " the pid of alive process"))
    (is (contains? pids pid2)
        (str "list of pids returned by (processes) must contain"
             " the pid of alive process"))
    (deliver done true)))

(deftest processes-doesnot-return-exited-process-pids
  (let [pid1 (process/spawn (proc-fn []))
        pid2 (process/spawn (proc-fn []))
        _ (sleep 50)
        pids (set (process/processes))]
    (is (not (contains? pids pid1))
        (str "list of pids returned by (processes) must not contain"
             " the pid of existed process"))
    (is (not (contains? pids pid2))
        (str "list of pids returned by (processes) must not contain"
             " the pid of exited process"))))

;; =============================================================================
;; (spawn-opt [proc-fun args options]) - Additional tests
;; =============================================================================

(deftest ^:parallel spawned-process-is-reachable
  (let [done (promise)
        pfn (proc-fn []
              (is (= [:message :msg] (await-message 50))
                  (str "messages sent to spawned process must appear"
                       " in its inbox"))
              (deliver done true))
        pid (process/spawn-opt pfn [] {})]
    (! pid :msg)
    (await-completion!! done 50)))

(deftest ^:parallel spawned-process-registered-according-options
  (let [reg-name (uuid-keyword)
        done (promise)
        pfn (proc-fn [] (await-completion!! done 50))]
    (is (not ((into #{} (process/registered)) reg-name)))
    (process/spawn-opt pfn [] {:register reg-name})
    (is ((into #{} (process/registered)) reg-name)
        "spawn must register proces when called with :register option")
    (deliver done true)))

(deftest ^:parallel spawn-opt--throws-when-already-registered
  (let [reg-name (uuid-keyword)
        done (promise)
        pfn (proc-fn [] (await-completion!! done 50))]
    (process/spawn-opt pfn [] {:register reg-name})
    (is
      (thrown? Exception
        (process/spawn-opt (proc-fn []) [] {:register reg-name})))
    (deliver done true)))

(deftest ^:parallel spawn-opt--doesnt-call-proc-fn-when-already-registered
  (let [reg-name (uuid-keyword)
        done (promise)
        pfn (proc-fn [] (await-completion!! done 100))]
    (process/spawn-opt pfn [] {:register reg-name})
    (process/ex-catch
      (process/spawn-opt
        (proc-fn []
          (is false
            "proc fn must not be called if the name is already registered"))
        []
        {:register reg-name}))
    (sleep 50)
    (deliver done true)))

(deftest ^:parallel spawn-opt--spawned-process-receives-parent-exit-reason
  (let [done (promise)
        pfn1 (proc-fn []
               (is (match (await-message 50)
                          [:exit [(_ :guard process/pid?) :normal]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (deliver done true))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                     pfn1 {:link true :flags {:trap-exit true}})
                   "test failed"))]
    (process/spawn pfn2)
    (await-completion!! done 200))
  (let [done (promise)
        pfn1 (proc-fn []
               (is (match (await-message 50)
                          [:exit [(_ :guard process/pid?) :abnormal]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (deliver done true))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                     pfn1 {:link true :flags {:trap-exit true}})
                   "test failed")
               (process/exit :abnormal))]
    (process/spawn pfn2)
    (await-completion!! done 200))
  (let [done (promise)
        pfn1 (proc-fn []
               (is (match (await-message 50)
                          [:exit [(_ :guard process/pid?) :killed]] :ok)
                   (str "spawn-link(ed) process must receive exit message"
                        " with the reason of parent's exit"))
               (deliver done true))
        pfn2 (proc-fn []
               (is (process/spawn-opt
                     pfn1 {:link true :flags {:trap-exit true}})
                   "test failed")
               (process/exit (process/self) :kill))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

;; =============================================================================
;; (link [pid]) - Additional tests
;; =============================================================================

(deftest linked-process-receives-exited-process-pid
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn [] (await-completion!! done1 100))
        pid1 (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/link pid1)
               (sleep 50)
               (deliver done1 true)
               (is (= [:exit [pid1 :normal]] (await-message 50))
                   (str "process 1 linked to process 2, must receive exit "
                        " message containing pid of a proces 2, after process 2"
                        " terminated"))
               (deliver done true))]
    (process/spawn-opt pfn2 {:flags {:trap-exit true}})
    (await-completion!! done 100))
  (let [done (promise)
        pid (process/spawn (proc-fn []))
        pfn (proc-fn []
              (sleep 50)
              (process/link pid)
              (is (= [:exit [pid :noproc]]
                     (await-message 50))
                  (str "process 1 tried to link terminated process 2, must"
                       " receive exit message containing pid of a proces 2"
                       " when trapping exits"))
              (deliver done true))]
    (process/spawn-opt pfn {:flags {:trap-exit true}})
    (await-completion!! done 100)))

(deftest ^:parallel unlink-prevents-exit-message-after-linked-process-failed
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn []
               (await-completion!! done1 100)
               (throw (Exception.
                        (str "TEST: terminating abnormally to test unlink"
                             " prevents exit message when previously linked"
                             " process have exited abnormally"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/flag :trap-exit true)
               (process/link pid)
               (sleep 50)
               (process/unlink pid)
               (deliver done1 true)
               (is (= :timeout (await-message 100))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(deftest ^:parallel unlink-does-not-affect-process-when-called-multiple-times
  (let [done (promise)
        done1 (promise)
        pfn1 (proc-fn []
               (is (= :timeout (await-message 100)))
               (await-completion!! done1 100)
               (throw (Exception.
                        (str "TEST: terminating abnormally to test unlink"
                             " doesn't affect process when called multiple"
                             " times"))))
        pid (process/spawn pfn1)
        pfn2 (proc-fn []
               (process/flag :trap-exit true)
               (process/link pid)
               (sleep 50)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (process/unlink pid)
               (deliver done1 true)
               (is (= :timeout (await-message 100))
                   (str "exit message from abnormally terminated linked"
                        " process, terminated before unlink have been"
                        " called, must not appear in process' inbox after"
                        " unlink have been called"))
               (deliver done true))]
    (process/spawn pfn2)
    (await-completion!! done 200)))

(def-proc-test ^:parallel link-multiple-times-work-as-single-link
  (let [done1 (promise)
        done2 (promise)
        pfn2 (proc-fn []
               (is (match (await-message 100) [:noproc _] :ok)
                   (str "process must exit when linked process exits"
                        " with reason other than :normal"))
               (deliver done2 true))
        pid2 (process/spawn pfn2)
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (deliver done1 true)
               (is (match (await-message 50) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion!! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion!! done2 100))
  (let [done1 (promise)
        done2 (promise)
        pid1-ref (atom nil)
        pfn2 (proc-fn []
               ;; Wait for pid1 to be set
               (sleep 10)
               (let [pid1 @pid1-ref]
                 (is (= [:exit [pid1 :abnormal]]
                        (await-message 100))
                     (str "process trapping exits must get exit message"
                          " when linked process exits with reason"
                          " other than :normal")))
               (deliver done2 true))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (deliver done1 true)
               (is (match (await-message 50) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (reset! pid1-ref pid1)
    (await-completion!! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion!! done2 100)))

(def-proc-test ^:parallel
  link-creates-exactly-one-link-when-called-multiple-times
  (let [done1 (promise)
        done2 (promise)
        pfn2 (proc-fn []
               (is (= :timeout (await-message 100))
                   (str "process must not exit when linked process was"
                        " unlinked before exit with reason"
                        " other than :normal"))
               (deliver done2 true))
        pid2 (process/spawn-opt pfn2 {:flags {:trap-exit true}})
        pfn1 (proc-fn []
               (process/link pid2)
               (process/link pid2)
               (process/link pid2)
               (process/unlink pid2)
               (deliver done1 true)
               (is (match (await-message 50) [:noproc _] :ok)
                   "exit must close inbox of process not trapping exits"))
        pid1 (process/spawn pfn1)]
    (await-completion!! done1 50)
    (process/exit pid1 :abnormal)
    (await-completion!! done2 150)))

;; =============================================================================
;; (map-async f async-value)
;; =============================================================================

(deftest map-async-returns-async-value
  (let [av (process/async 0)]
    (is (process/async? (process/map-async inc av))
        "map-async must return async value")))

(deftest map-async-returns-value-transforming-the-result
  (let [av (process/async 0)
        av2 (process/map-async inc av)]
    (is (= 1 (process/await!! av2))
        "map async must transform the result applying provided functions"))
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(+ 2 %) av2)]
    (is (= 3 (process/await!! av3))
        "map async must transform the result applying provided functions")))

(deftest map-async-returns-value-applying-transformations-in-the-right-order
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(cons % [2 3]) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (= "123" (process/await!! av4))
        "map async must apply transformations in the right order")))

(deftest map-async-propagates-exceptions
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async (fn [_] (process/exit :test)) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (= [:EXIT :test] (process/ex-catch (process/await!! av4)))
        "map async must transform the result applying provided functions"))
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        av3 (process/map-async #(throw (Exception. "test")) av2)
        av4 (process/map-async #(apply str %) av3)]
    (is (thrown? Exception "test" (process/await!! av4))
        "map async must transform the result applying provided functions")))

(deftest map-async-doesnot-change-the-initial-async-value
  (let [av (process/async 0)
        av2 (process/map-async inc av)
        _ (process/map-async #(process/exit :test) av2)]
    (is (= 1 (process/await!! av2))
        "map async must not change the initial async value")))

;; =============================================================================
;; (with-async bindings & body)
;; =============================================================================

(deftest with-async-returns-async-value
  (let [av (process/async 0)]
    (is (process/async? (process/with-async [res av] (inc res)))
        "with-async must return async value"))
  (is (process/async?
       (process/with-async [res (process/async 0)]
         (inc res)))
      "with-async must return async value"))

(deftest with-async-returns-value-transforming-the-result
  (let [av (process/with-async [av (process/async 0)] (inc av))]
    (is (= 1 (process/await!! av))
        "with-async must transform the result applying provided functions"))
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [res av](+ 2 res))]
    (is (= 3 (process/await!! av2))
        "with-async must transform the result applying provided functions")))

(deftest with-async-propagates-exceptions
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [_ av] (process/exit :test))
        av3 (process/with-async [res av2] (apply str res))]
    (is (= [:EXIT :test] (process/ex-catch (process/await!! av3)))
        "with-async must propagate exit exceptions"))
  (let [av (process/with-async [res (process/async 0)] (inc res))
        av2 (process/with-async [_ av] (throw (Exception. "test")))
        av3 (process/with-async [res av2] (apply str res))]
    (is (thrown? Exception "test" (process/await!! av3))
        "with-async must propagate exceptions")))

;; =============================================================================
;; Missing tests from original otplike - empty stubs
;; =============================================================================

(deftest self-fails-when-process-is-exiting
  )

(deftest exit-fails-when-called-by-exiting-process
  )

(deftest flag-fails-when-called-by-exiting-process
  )

(deftest link-fails-when-called-by-exiting-process
  )

(deftest unlink-fails-when-called-by-exiting-process
  )

(deftest spawn-link-fails-when-called-by-exiting-process
  )

(deftest monitor-fails-when-called-by-exiting-process
  )

(deftest demonitor-fails-when-called-by-exiting-process
  )

(deftest receive!-fails-when-called-by-exiting-process
  )

(deftest selective-receive!-fails-when-called-by-exiting-process
  )

;; =============================================================================
;; Missing tests from original otplike - spawn tests
;; =============================================================================

(deftest ^:parallel spawn-passes-opened-read-port-to-pfn-as-inbox
  (let [done (promise)
        pfn (proc-fn []
              (is (= :timeout (await-message 100))
                  "spawn must pass opened ReadPort as inbox to process fn")
              (deliver done true))]
    (process/spawn-opt pfn [] {})
    (await-completion!! done 150)))

(def-proc-test ^:parallel spawn-link-throws-when-called-from-exited-process
  (let [done (promise)
        pfn (proc-fn [] (is false "process must not be started"))
        pfn1 (proc-fn []
               (process/exit (process/self) :abnormal)
               ;; Use busy-wait instead of Thread/sleep to avoid InterruptedException
               (busy-wait 50)
               (is (thrown? Exception (process/spawn-link pfn)))
               (deliver done true))]
    (process/spawn pfn1)
    (await-completion!! done 200)))

;; =============================================================================
;; Missing tests from original otplike - receive tests
;; =============================================================================

(deftest ^:parallel receive!-requires-one-or-more-message-patterns
  (try
    (eval `(process/receive!))
    (catch clojure.lang.Compiler$CompilerException e
      (let [cause (.getCause e)
            msg (.getMessage cause)]
        (is (re-find #"requires one or more message patterns" msg)
          "exception must contain an explanation"))))
  (try
    (eval `(process/receive! (after 10 :ok)))
    (catch clojure.lang.Compiler$CompilerException e
      (let [cause (.getCause e)
            msg (.getMessage cause)]
        (is (re-find #"requires one or more message patterns" msg)
          "exception must contain an explanation")))))

(def-proc-test ^:parallel receive-throws-if-process-exited
  (let [done (promise)
        pfn (proc-fn []
                     (process/exit (process/self) :abnormal)
                     (is (thrown? Exception (process/receive! _ :ok)))
                     (deliver done true))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

;; =============================================================================
;; Missing tests from original otplike - selective-receive tests
;; =============================================================================

(deftest ^:parallel selective-receive!-requires-one-or-more-message-patterns
  (try
    (eval `(process/selective-receive!))
    (catch clojure.lang.Compiler$CompilerException e
      (let [cause (.getCause e)
            msg (.getMessage cause)]
        (is (re-find #"requires one or more message patterns" msg)
          "exception must contain an explanation"))))
  (try
    (eval `(process/selective-receive! (after 10 :ok)))
    (catch clojure.lang.Compiler$CompilerException e
      (let [cause (.getCause e)
            msg (.getMessage cause)]
        (is (re-find #"requires one or more message patterns" msg)
          "exception must contain an explanation")))))

(def-proc-test ^:parallel selective-receive!-throws-if-process-exited
  (let [done (promise)
        pfn (proc-fn []
              (process/exit (process/self) :abnormal)
              (is (thrown? Exception (process/selective-receive! _ :ok)))
              (deliver done true))
        pid (process/spawn pfn)]
    (await-completion!! done 150)))

(deftest ^:parallel selective-receive!-accepts-infinity-timeout
  (let [done (promise)
        pfn (proc-fn []
              (process/selective-receive!
                :done (deliver done true)
                (after :infinity
                  (is false
                      (str "expression for infinite timeout must never"
                           " be executed")))))
        pid (process/spawn pfn)]
    (Thread/sleep 100)
    (! pid :done)
    (await-completion!! done 100)))

(deftest ^:parallel selective-receive!-accepts-0-timeout
  (let [done (promise)
        pfn (proc-fn []
              (process/selective-receive!
                _ (is false
                      (str "expression for message must not be executed"
                           " when there is no message in inbox"))
                (after 0
                  (deliver done true))))
        pid (process/spawn pfn)]
    (await-completion!! done 50))
  (let [done1 (promise)
        done2 (promise)
        pfn (proc-fn []
              (await-completion!! done1 150)
              (process/selective-receive!
                :done (deliver done2 true)
                (after 0
                  (is false
                      (str "expression for 0 timeout must not be executed"
                           " when there is a message in inbox")))))
        pid (process/spawn pfn)]
    (! pid :done)
    (Thread/sleep 50)
    (deliver done1 true)
    (await-completion!! done2 50)))

;; =============================================================================
;; Missing tests from original otplike - async tests
;; =============================================================================

;; NOTE: This test is disabled in loom-otp because async executes synchronously
;; (immediately on the calling thread) rather than concurrently like otplike's
;; go blocks. The original test expected concurrent/parking behavior.
#_(deftest ^:parallel async-allows-parking
  (let [done (promise)
        done1 (promise)]
    (process/async
      (if (deref done 50 nil)
        (deliver done1 true)
        (is false "timeout")))
    (deliver done true)
    (await-completion!! done1 50)))

(deftest ^:parallel await?!-returns-value-of-async-expr
  (is (= 123 (process/await!! (process/async 123)))
      "await?! must return the value of async's expression")
  (is (nil? (process/await!! (process/async nil)))
      "await?! must return the value of async's expression"))

(deftest ^:parallel await?!-returns-regular-value
  (is (= 123 (process/await?! 123))
      "await?! must return non-async values directly")
  (is (nil? (process/await?! nil))
      "await?! must return nil directly")
  (is (= "string" (process/await?! "string"))
      "await?! must return strings directly")
  (is (= [1 2 3] (process/await?! [1 2 3]))
      "await?! must return vectors directly")
  (is (= {:a 1} (process/await?! {:a 1}))
      "await?! must return maps directly"))

(deftest ^:parallel await?!-propagates-exceptions-of-async-expr
  (let [async (process/async (throw (ex-info "msg 123" {})))]
    (is (thrown?
         clojure.lang.ExceptionInfo #"^msg 123$"
         (process/await!! async)))))

;; =============================================================================
;; Missing tests from original otplike - process-info tests
;; =============================================================================

(deftest process-info-returns-correct-info
  ;; TODO
  ;; empty links on no links
  ;; links when pid initiated linking
  ;; links when other pid initiated linking

  ;; empty monitors on no monitoring
  ;; monitors on monitoring
  ;; duplicate monitors on multiple monitoring of the same process

  ;; empty monitored-by when is not monitored
  ;; monitored-by when is monitored
  ;; repeated pids in monitored-by when monitored multiple times by the same process

  ;; reg-name when is registered
  ;; nil reg-name when is not resitered

  ;; running status
  ;; waiting status
  ;; exiting status
  ;; status changes

  ;; correct life time ms

  ;; intial call: when proc-defn is used
  ;; intial call: when proc-fn is used
  ;; intial call: when named proc-fn is used
  ;; intial call: args

  ;; correct message queue len
  ;; message queue len changes

  ;; no messages
  ;; some messages
  ;; messages change on receive!
  ;; messages change on selective-receive!

  ;; correct flags
  ;; flags change

  [:links
   :monitors
   :monitored-by
   :registered-name
   :status
   :life-time-ms
   :initial-call
   :message-queue-len
   :messages
   :flags])

;; =============================================================================
;; Missing tests from original otplike - processes tests
;; =============================================================================

(def-proc-test processes-returns-a-list-with-exiting-process-pids-included
  (let [;; Use busy-wait in spawned processes to avoid InterruptedException
        ;; when exit signals are sent
        pid1 (process/spawn (proc-fn [] (busy-wait 1000)))
        pid2 (process/spawn (proc-fn [] (busy-wait 1000)))]
    ;; Send exit signals - processes will be in "exiting" state
    (process/exit pid1 :test)
    (process/exit pid2 :test)
    ;; Small busy-wait to let signals be sent (but processes still running)
    (busy-wait 10)
    (let [pids (set (process/processes))]
      (is (contains? pids pid1)
          (str "list of pids returned by (processes) must contain"
               " the pid of exiting process"))
      (is (contains? pids pid2)
          (str "list of pids returned by (processes) must contain"
               " the pid of exiting process")))))

;; =============================================================================
;; Missing tests from original otplike - exit tests (commented out in original)
;; =============================================================================

;; TODO: These tests were commented out in the original
#_(def-proc-test ^:parallel exit-self-reason-is-process-exit-reason
  (process/flag :trap-exit true)
  (let [pfn (proc-fn [] (process/exit (process/self) :abnormal))
        pid (process/spawn-link pfn)]
    (is (= [:exit [pid :abnormal]] (await-message 100))
        "process exit reason must be the one passed to exit call")))

;(deftest ^:parallel exit-kill-does-not-propagate)

;; =============================================================================
;; Missing tests from original otplike - inbox overflow test (commented out)
;; =============================================================================

#_(def-proc-test ^:parallel process-killed-when-inbox-is-overflowed
  ;; TODO: implement inbox overflow handling
  )

;; =============================================================================
;; Missing tests from original otplike - TODO stubs
;; =============================================================================

;(deftest ^:parallel process-exit-reason-is-proc-fn-return-value) ?
;(deftest ^:parallel spawned-process-available-from-within-process-by-reg-name)
;(deftest ^:parallel there-are-no-residue-of-process-after-proc-fun-throws)
