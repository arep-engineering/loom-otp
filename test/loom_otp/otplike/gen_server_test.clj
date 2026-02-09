(ns loom-otp.otplike.gen-server-test
  "Tests for loom-otp.otplike.gen-server compatibility layer.
   
   Adapted from otplike.gen-server-test with these changes:
   - No core.async (uses promises for synchronization)
   - await-message is now a macro called from process context
   - Channels replaced with promises"
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [clojure.core.match :refer [match]]
            [loom-otp.otplike.process :as process :refer [!]]
            [loom-otp.otplike.test-util :refer :all]
            [loom-otp.otplike.proc-util :as proc-util]
            [loom-otp.otplike.gen-server :as gs]
            [mount.lite :as mount])
  (:import [loom_otp.otplike.gen_server IGenServer]))

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

;; =============================================================================
;; Helper to spawn an exit watcher
;; =============================================================================

(defn spawn-exit-watcher
  "Spawns a linked process that watches for :EXIT messages.
   When received, delivers [:reason reason] to the done promise."
  [done timeout]
  (let [pid (process/self)]
    (process/spawn-opt
      (process/proc-fn []
        (process/receive!
          [:EXIT pid reason] (deliver done [:reason reason])
          (after timeout
            (deliver done :timeout))))
      {:link true :flags {:trap-exit true}})))

;; ====================================================================
;; (start [server-impl args options])

(deftest ^:parallel start--no-trap-exit--linked-parent-exits-abnormally
  (let [done (promise)
        server {:init (fn []
                        (is (spawn-exit-watcher done 50))
                        [:ok :state])
                :handle-info
                (fn [request _state]
                  (is false "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate
                (fn [reason _state]
                  (is false "terminate must not be called on parent exit"))}
        parent (process/proc-fn []
                 (gs/start-link! server)
                 (process/exit :abnormal))
        parent-pid (process/spawn parent)]
    (is (match (await-completion!! done 100) [:ok [:reason :abnormal]] :ok)
        "gen-server must exit with the same reason as parent")))

(deftest ^:parallel start--trap-exit--linked-parent-exits-normally
  (let [terminate-prom (promise)
        done (promise)
        server {:init (fn []
                        (is (spawn-exit-watcher done 150))
                        [:ok :state])
                :handle-info
                (fn [request state]
                  (is false "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (deliver terminate-prom true))}
        parent (process/proc-fn []
                 (gs/start-link!
                   server [] {:spawn-opt {:flags {:trap-exit true}}}))]
    (process/spawn parent)
    (is (await-completion!! terminate-prom 500)
        "reason passed to terminate must be the same as parent's exit reason")
    (is (match (await-completion!! done 500) [:ok [:reason :normal]] :ok)
        "gen-server's process must exit with the same reason as parent")))

(deftest ^:parallel start--trap-exit--linked-parent-exits-abnormally
  (let [terminate-prom (promise)
        done (promise)
        server {:init (fn []
                        (is (spawn-exit-watcher done 150))
                        [:ok :state])
                :handle-info
                (fn [request state]
                  (is false "handle-info must not be called on parent exit")
                  [:stop :TEST-FAILED])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (deliver terminate-prom true))}
        parent (process/proc-fn []
                 (gs/start-link!
                   server [] {:spawn-opt {:flags {:trap-exit true}}})
                 (process/exit :abnormal))]
    (process/spawn parent)
    (is (await-completion!! terminate-prom 500)
        "gen-server must exit on bad return from handle-call")
    (is (match (await-completion!! done 500) [:ok [:reason :abnormal]] :ok)
        "gen-server must exit on bad return from handle-call")))

(def-proc-test ^:parallel start--illegal-arguments
  (is (thrown? Exception (gs/start! 1 [] {})))
  (is (thrown? Exception (gs/start! "server" [] {})))
  (is (thrown? Exception (gs/start! [] [] {})))
  (is (thrown? Exception (gs/start! #{} [] {})))
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :terminate (fn [_ _] (deliver done true))}]
    (is (thrown? Exception (await-completion!! done 50))
        (str "terminate must not be called when illegal arguments were passed"
             " to start"))))

(def-proc-test ^:parallel start--start-returns-pid
  (let [server {:init (fn [] [:ok nil])}]
    (match (gs/start! server)
      [:ok (pid :guard process/pid?)]
      (match (process/exit pid :abnormal) true :ok))))

(def-proc-test ^:parallel start--returns-error-when-already-registred
  (let [reg-name (uuid-keyword)
        done (promise)
        server {:init (fn [] [:ok nil])}
        pfn (process/proc-fn [] (is (await-completion!! done 50)))
        pid (process/spawn-opt pfn [] {:register reg-name})]
    (is
      (= [:error [:already-registered pid]] (gs/start! reg-name server [] {})))
    (deliver done true)))

(def-proc-test ^:parallel start--doesnt-call-init-when-already-registered
  (let [reg-name (uuid-keyword)
        done (promise)
        server
        {:init
         (fn []
           (is false
             "proc fn must not be called if the name is already registered")
           [:ok nil])}
        pfn (process/proc-fn [] (is (await-completion!! done 100)))]
    (process/spawn-opt pfn [] {:register reg-name})
    (gs/start! reg-name server [] {})
    (sleep 50)
    (deliver done true)))

(def-proc-test ^:eftest/seqential
  start--doesnt-start-process-when-already-registred
  (let [reg-name (uuid-keyword)
        done (promise)
        server {:init (fn [] [:ok nil])}
        pfn (process/proc-fn [] (is (await-completion!! done 50)))]
    (process/spawn-opt pfn [] {:register reg-name})
    (let [procs (process/processes)]
      (gs/start! reg-name server [] {})
      (is (= procs (process/processes))))
    (deliver done true)))

;; ====================================================================
;; (init [& args])

(def-proc-test ^:parallel init--start-calls-init
  (let [done (promise)
        server {:init (fn [] (deliver done true) [:ok nil])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (await-completion!! done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel init--start-passes-arguments
  (let [done (promise)
        server {:init
                (fn [& args]
                  (is (= [:a 1 "str" {:a 1 :b 2} '()] args)
                      "args passed to init must be the same as passed to start")
                  (deliver done true)
                  [:ok args])}]
    (match (gs/start! server [:a 1 "str" {:a 1 :b 2} '()])
      [:ok pid]
      (do
        (await-completion!! done 50)
        (match (process/exit pid :abnormal) true :ok))))
  (let [done (promise)
        server {:init
                (fn [args]
                  (is (nil? args)
                      "args passed to init must be the same as passed to start")
                  (deliver done true)
                  [:ok args])}]
    (match (gs/start! server [nil] {})
      [:ok pid]
      (do
        (await-completion!! done 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel init--undefined-callback
  (is (= [:error [:undef ['init [1]]]] (gs/start! {} 1)))
  (is (= [:error [:undef ['init [1]]]] (gs/start! (create-ns 'test-ns) 1)))
  (let [done (promise)
        server {:terminate (fn [_ _] (deliver done :val))}]
    (is (= [:error [:undef ['init [1]]]] (gs/start! server 1)))
    (is (not (realized? done))
        "terminate must not be called if init is undefined")))

(def-proc-test ^:parallel init--callback-throws
  (let [done (promise)
        server {:init (fn [] (throw (Exception. "TEST")))
                :terminate (fn [_ _] (deliver done :val))}]
    (is (match (gs/start! server)
          [:error [:exception {:message "TEST" :class "java.lang.Exception"}]]
          :ok)
        "error returned by start must contain exception thrown from callback")
    (is (not (realized? done))
        "terminate must not be called if init throws")))

(def-proc-test ^:parallel init--bad-return
  (let [done (promise)
        server {:init (fn [] :bad-return)
                :terminate (fn [_ _] (deliver done :val))}]
    (is (match (gs/start! server)
          [:error [:bad-return-value 'init :bad-return]] :ok)
        "error returned by start must contain value returned by callback")
    (is (not (realized? done))
        "terminate must not be called if init returns bad value")))

(def-proc-test ^:parallel init--invalid-timeout
  (let [server {:init (fn [] [:ok nil])}]
    (is (thrown? Exception (gs/start! server [] {:timeout -1}))
        "start must throw on invali timeout")
    (is (thrown? Exception (gs/start! server [] {:timeout :t}))
        "start must throw on invali timeout")))

(def-proc-test ^:parallel init--infinite-timeout
  (let [done (promise)
        server {:init (fn []
                        (deliver done true)
                        [:ok nil])
                :terminate (fn [_ _] :ok)}]
    (is (match (gs/start-link! server [] {:timeout :infinity}) [:ok _pid] :ok)
        "error returned by start must contain :timeout")
    (is (await-completion!! done 100)
        "gen-server process must be started")))

(def-proc-test ^:parallel init--timeout--not-linked-to-parent
  (let [done (promise)
        done1 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 200)
                        (process/async
                          (do
                            (sleep 100)
                            [:ok nil])))
                :terminate (fn [_ _] (deliver done1 :val))}]
    (is (match (gs/start! server [] {:timeout 50}) [:error :timeout] :ok)
        "error returned by start must contain :timeout")
    (is (= (await-completion!! done 200) [:ok [:reason :killed]])
        "gen-server process must be killed after init timeout")
    (is (not (realized? done1))
        "terminate must not be called if init returns bad value")))

(def-proc-test ^:parallel init--timeout--linked-to-parent
  (let [server {:init (fn []
                        (process/async
                          (do
                            (sleep 100)
                            [:ok nil])))}]
    (is (match (gs/start-link! server [] {:timeout 50}) [:error :timeout] :ok)
        "error returned by start must contain :timeout")
    (is (= :timeout (await-message 200))
        (str "process must stay alive after gen-server/start-link fails"
             " with timeout"))))

(def-proc-test ^:parallel init--timeout-returned--0
  (let [done (promise)
        server {:init (fn [] [:ok nil 0])
                :handle-info (fn [msg state]
                               (match msg
                                      :timeout
                                      (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server) [:ok pid] :ok)
    (is (await-completion!! done 100)
        ":timeout message must be sent to gen-server")))

(def-proc-test ^:parallel init--timeout-returned--100
  (let [done (promise)
        server {:init (fn [] [:ok nil 100])
                :handle-info (fn [msg state]
                               (match msg
                                      :timeout
                                      (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server) [:ok pid] :ok)
    (is (thrown? Exception (await-completion!! done 50))
        ":timeout message must not be sent to gen-server before timeout")
    (is (await-completion!! done 150)
        ":timeout message must be sent to gen-server after timeout")))

(def-proc-test ^:parallel init--async-value-returned
  (process/flag :trap-exit true)
  (let [done (promise)
        server {:init (fn [] (process/async [:ok :init]))
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:msg :init]
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
      [:ok pid] (! pid :msg))
    (is (await-completion!! done 50)
        "state of a server must be set as returned from init"))
  (let [done (promise)
        server {:init (fn [] (process/async [:ok :init 100]))
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:timeout :init]
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
      [:ok pid] :ok)
    (is (await-completion!! done 150)
        "timeout returned from init must occur"))
  (let [server {:init (fn [] (process/async [:stop :test-reason]))}]
    (is (match (gs/start-link! server) [:error :test-reason] :ok)
        "start-link must return the reason returned from init"))
  (let [server {:init (fn [] (process/async (process/exit :test)))}]
    (is (match (gs/start-link! server) [:error :test] :ok)
        "start-link must return the reason init exited with"))
  (let [server {:init (fn [] (process/async :my-bad-return))}]
    (is (match (gs/start-link! server)
          [:error [:bad-return-value init :my-bad-return]] :ok)
        "start-link's error must contain the value returned from init")))

;; ====================================================================
;; (handle-call [request from state])

(def-proc-test ^:parallel handle-call--call-delivers-message
  (let [server {:init (fn [] [:ok :state])
                :handle-call
                (fn [x _  state]
                  (is (= x 123)
                      "handle-call must receive message passed to call")
                  [:reply :ok state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/call! pid 123 50)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--undefined-callback
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (process/spawn-opt
                          (process/proc-fn []
                            (process/receive!
                              [:EXIT pid reason]
                              (deliver done2 [:reason reason])
                              (after 50
                                     (deliver done2 :timeout))))
                          {:link true :flags {:trap-exit true}})
                        [:ok :state])
                :terminate (fn [reason _] (deliver done1 [:reason reason]))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (match (process/ex-catch [:ok (gs/call! pid 1 50)])
               [:EXIT [[:undef ['handle-call [1 _ :state]]]
                       [`gs/call [pid 1 50]]]]
               :ok)
            "call must exit on absent handle-call callback")
        (is (match (await-completion!! done1 50)
                   [:ok [:reason [:undef ['handle-call [1 _ :state]]]]] :ok)
          (str "terminate must be called on bad return from handle-call"
               " with reason containing name and arguments of handle-call"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:undef ['handle-call [1 _ :state]]]]] :ok)
            (str "gen-server must exit on bad return from handle-call with"
                 " reason containing name and arguments of handle-call"))))))

(def-proc-test ^:parallel handle-call--bad-return
  (process/flag :trap-exit true)
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [_ _ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value 'handle-call :bad-return]
                                    reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-call"))
                             (deliver done true))}]
    (match (gs/start-link! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:bad-return-value 'handle-call :bad-return]
                       [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            "call must exit on bad return from handle-call")
        (is (await-completion!! done 50)
            "terminate must be called on bad return from handle-call")
        (is (match (await-message 50)
                   [:exit [pid [:bad-return-value 'handle-call :bad-return]]]
                   :ok))))))

(def-proc-test ^:parallel handle-call--callback-throws
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 50)
                        [:ok nil])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST" :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace :class)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST" :data {:test 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace :class)] f]]))
            "call must exit after exit called in handle-call")
        (is (await-completion!! done1 50)
            "terminate must be called on bad return from handle-call")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST"}]]] :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--exit-abnormal
  (let [done1 (promise) done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 50)
                        [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            "call must exit after exit called in handle-call")
        (is (await-completion!! done1 50)
            "terminate must be called after exit called in  handle-call")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after exit called in handle-call")))))

(def-proc-test ^:parallel handle-call--exit-normal
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 50)
                        [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            "call must exit after exit called in handle-call")
        (is (await-completion!! done1 50)
            "terminate must be called after exit called in handle-call")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason :normal]] :ok)
            "gen-server must exit after exit called in handle-call")))))

(def-proc-test ^:parallel handle-call--stop-normal
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 50)
                        [:ok nil])
                :handle-call (fn [_ _ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            "call must exit if :stop returned by handle-call")
        (is (await-completion!! done1 50)
            "terminate must be called after :stop returned by handle-call")
        (is (match (await-completion!! done2 100)
                   [:ok [:reason :normal]] :ok)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call--stop-abnormal
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 50)
                        [:ok nil])
                :handle-call (fn [_ _ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            "call must exit if :stop returned by handle-call")
        (is (await-completion!! done1 50)
            "terminate must be called after :stop returned by handle-call")
        (is (match (await-completion!! done2 100)
                   [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call--return-reply
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [x _from state] [:reply (inc x) state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= 2 (gs/call! pid 1 50)) "call must return response from server")
        (is (= 5 (gs/call! pid 4 50)) "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--nil-return-reply
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [_ _from state] [:reply nil state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (nil? (gs/call! pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--delayed-reply-before-return
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from :ok)
                               [:noreply state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= :ok (gs/call! pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--return-reply-after-delayed-reply
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from :ok)
                               [:reply :error state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= :ok (gs/call! pid nil 50))
            "call must return first response from server")
        (is (= :ok (gs/call! pid nil 50))
            "call must return first response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--nil-delayed-reply
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [_ from state]
                               (gs/reply from nil)
                               [:noreply state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (nil? (gs/call! pid nil 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--delayed-reply-after-return
  (let [done (promise)
        done1 (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [x from state]
                               (match [x state]
                                 [1 nil] (do (deliver done true)
                                             [:noreply from])
                                 [2 from1] (do (gs/reply from1 :ok1)
                                               [:reply :ok2 nil])))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (process/spawn
          (process/proc-fn []
            (is (= :ok1 (gs/call! pid 1 50))
                "call must return response from server")
            (deliver done1 true)))
        (is (await-completion!! done 50))
        (is (= :ok2 (gs/call! pid 2 50))
            "call must return response from server")
        (is (await-completion!! done1 50))
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--stop-normal-reply
  (let [done1 (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal (inc x) state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (deliver done1 true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= 2 (gs/call! pid 1 50)) "call must return response from server")
        (is (await-completion!! done1 50)
            "terminate must be called after :stop returned by handle-call")
        (is (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call--stop-abnormal-reply
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [x _ state] [:stop :abnormal (inc x) state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-call"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= 2 (gs/call! pid 1 50)) "call must return response from server")
        (is (await-completion!! done 50)
            "terminate must be called after :stop returned by handle-call")
        (is (match (await-completion!! done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after :stop returned by handle-call")))))

(def-proc-test ^:parallel handle-call--call-to-exited-pid
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal :ok state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (gs/call! pid nil 50) :ok :ok)
        (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
        (is (= [:EXIT [:noproc [`gs/call [pid nil 10]]]]
               (process/ex-catch [:ok (gs/call! pid nil 10)]))
            "call to exited server must exit with :noproc reason")))))

(def-proc-test ^:parallel handle-call--timeout
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [x _ state]
                               (process/async
                                 (sleep 50)))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:timeout [`gs/call [pid nil 10]]]]
               (process/ex-catch [:ok (gs/call! pid nil 10)]))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--update-state
  (let [server {:init (fn [] [:ok 1])
                :handle-call
                (fn [[old-state new-state] _from state]
                  (is (= old-state state)
                      "return from handle-call must update server state")
                  [:reply :ok new-state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= :ok (gs/call! pid [1 2] 50))
            "call must return response from server")
        (is (= :ok (gs/call! pid [2 4] 50))
            "call must return response from server")
        (is (= :ok (gs/call! pid [4 0] 50))
            "call must return response from server")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-call--timeout-returned--0
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [msg _ state]
                               [:reply msg state 0])
                :handle-info (fn [msg state]
                               (match msg
                                 :timeout
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
           [:ok pid] (match (gs/call! pid :msg) :msg :ok))
    (is (await-completion!! done 100)
        ":timeout message must be sent to gen-server")))

(def-proc-test ^:parallel handle-call--timeout-returned--100
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [msg _ state]
                               [:reply msg state 100])
                :handle-info (fn [msg state]
                               (match msg
                                 :timeout
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
           [:ok pid] (match (gs/call! pid :msg) :msg :ok))
    (is (thrown? Exception (await-completion!! done 50))
        ":timeout message must not be sent to gen-server before timeout")
    (is (await-completion!! done 150)
        ":timeout message must be sent to gen-server after timeout")))

;; ====================================================================
;; (handle-cast [request state])

(def-proc-test ^:parallel handle-cast--cast-delivers-message
  (let [done (promise)
        server {:init (fn [] [:ok :state])
                :handle-cast
                (fn [x state]
                  (is (= x 123)
                      "handle-cast must receive message passed to cast")
                  (deliver done true)
                  [:noreply state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid 123)
        (await-completion!! done 50)
        (is (process/exit pid :abnormal))))))

(def-proc-test ^:parallel handle-cast--undefined-callback
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok :state])
                :terminate (fn [reason _]
                             (is (= [:undef ['handle-cast [1 :state]]] reason)
                                 (str "reason passed to terminate must contain"
                                      " name and arguments of handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid 1))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called on undefined handle-cast callback")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:undef ['handle-cast [1 :state]]]]] :ok)
            "gen-server must exit on  undefined handle-cast callback")))))

(def-proc-test ^:parallel handle-cast--bad-return
  (process/flag :trap-exit true)
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value 'handle-cast :bad-return]
                                    reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-cast"))
                             (deliver done true))}]
    (match (gs/start-link! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called on bad return from handle-cast")
        (is (match (await-completion!! done2 50)
              [:ok [:reason [:bad-return-value 'handle-cast :bad-return]]] :ok)
            "gen-server must exit on bad return from handle-cast")))))

(def-proc-test ^:parallel handle-cast--callback-throws
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST"
                                      :class "clojure.lang.ExceptionInfo"
                                      :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called on bad return from handle-cast")
        (is (match (await-completion!! done2 50)
              [:ok [:reason [:exception {:message "TEST" :data {:test 1}}]]]
              :ok)
            "gen-server must exit on bad return from handle-cast")))))

(def-proc-test ^:parallel handle-cast--exit-abnormal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called after exit called in  handle-cast")
        (is (match (await-completion!! done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after exit called in handle-cast")))))

(def-proc-test ^:parallel handle-cast--exit-normal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called after exit called in handle-cast")
        (is (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit after exit called in handle-cast")))))

(def-proc-test ^:parallel handle-cast--stop-normal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called after :stop returned by handle-cast")
        (is (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit after :stop returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast--stop-abnormal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-cast"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (await-completion!! done 50)
            "terminate must be called after :stop returned by handle-cast")
        (is (match (await-completion!! done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after :stop returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast--cast-to-exited-pid
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ state] [:stop :normal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (match (await-completion!! done2 50) [:ok _] :ok)
        (is (= false (gs/cast pid nil))
            "cast must return false if server is not alive")))))

(def-proc-test ^:parallel handle-cast--update-state
  (let [server {:init (fn [] [:ok 1])
                :handle-cast
                (fn [[old-state new-state] state]
                  (is (= old-state state)
                      "return from handle-cast must update server state")
                  [:noreply new-state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid [1 2]))
            "cast must return true if server is alive")
        (is (= true (gs/cast pid [2 4]))
            "cast must return true if server is alive")
        (is (= true (gs/cast pid [4 0]))
            "cast must return true if server is alive")
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-cast--timeout-returned--0
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-cast (fn [msg state]
                               [:noreply state 0])
                :handle-info (fn [msg state]
                               (match msg
                                 :timeout
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
           [:ok pid] (gs/cast pid :msg))
    (is (await-completion!! done 100)
        ":timeout message must be sent to gen-server")))

(def-proc-test ^:parallel handle-cast--timeout-returned--100
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-cast (fn [msg state]
                               [:noreply state 100])
                :handle-info (fn [msg state]
                               (match msg
                                 :timeout
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
           [:ok pid] (gs/cast pid :msg))
    (is (thrown? Exception (await-completion!! done 50))
        ":timeout message must not be sent to gen-server before timeout")
    (is (await-completion!! done 150)
        ":timeout message must be sent to gen-server after timeout")))

;; ====================================================================
;; (handle-info [message state])

(def-proc-test ^:parallel handle-info--call-delivers-message
  (let [done (promise)
        server {:init (fn [] [:ok :state])
                :handle-info
                (fn [x state]
                  (is (= x 123)
                      "handle-info must receive message passed to !")
                  (deliver done true)
                  [:noreply state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (! pid 123))
        (await-completion!! done 50)
        (is (process/exit pid :shutdown))))))

(def-proc-test ^:parallel handle-info--undefined-callback
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok :state])
                :terminate (fn [reason _]
                             (is (= [:undef ['handle-info [1 :state]]] reason)
                                 (str "reason passed to terminate must contain"
                                      " name and arguments of handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called on undefined handle-info callback")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:undef ['handle-info [1 :state]]]]] :ok)
            "gen-server must exit on undefined handle-info callback")))))

(def-proc-test ^:parallel handle-info--bad-return
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] :bad-return)
                :terminate (fn [reason _]
                             (is (= [:bad-return-value 'handle-info :bad-return]
                                    reason)
                                 (str "reason passed to terminate must contain"
                                      " the value returned from handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called on bad return from handle-info")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:bad-return-value 'handle-info :bad-return]]]
                   :ok)
            "gen-server must exit on bad return from handle-info")))))

(def-proc-test ^:parallel handle-info--callback-throws
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:test 1})))
                :terminate (fn [[reason ex] _]
                             (is (= [:exception
                                     {:message "TEST"
                                      :class "clojure.lang.ExceptionInfo"
                                      :data {:test 1}}]
                                    [reason (dissoc ex :stack-trace)])
                                 (str "reason passed to terminate must contain"
                                      " exception thrown from handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called on bad return from handle-info")
        (is (match (await-completion!! done2 50)
              [:ok [:reason [:exception {:message "TEST" :data {:test 1}}]]]
              :ok)
            "gen-server must exit on bad return from handle-info")))))

(def-proc-test ^:parallel handle-info--exit-abnormal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] (process/exit :abnormal))
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called after exit called in  handle-info")
        (is (match (await-completion!! done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after exit called in handle-info")))))

(def-proc-test ^:parallel handle-info--exit-normal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as passed to exit in handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called after exit called in handle-info")
        (is (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit after exit called in handle-info")))))

(def-proc-test ^:parallel handle-info--stop-normal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ state] [:stop :normal state])
                :terminate (fn [reason _]
                             (is (= :normal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called after :stop returned by handle-info")
        (is (match (await-completion!! done2 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit after :stop returned by handle-info")))))

(def-proc-test ^:parallel handle-info--stop-abnormal
  (let [done (promise)
        done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ state] [:stop :abnormal state])
                :terminate (fn [reason _]
                             (is (= :abnormal reason)
                                 (str "reason passed to terminate must be the"
                                      " same as returned by handle-info"))
                             (deliver done true))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (await-completion!! done 50)
            "terminate must be called after :stop returned by handle-info")
        (is (match (await-completion!! done2 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit after :stop returned by handle-info")))))

(def-proc-test ^:parallel handle-info--update-state
  (let [server {:init (fn [] [:ok 1])
                :handle-info
                (fn [[old-state new-state] state]
                  (is (= old-state state)
                      "return from handle-info must update server state")
                  [:noreply new-state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid [1 2]) true :ok)
        (match (! pid [2 4]) true :ok)
        (match (! pid [4 0]) true :ok)
        (match (process/exit pid :abnormal) true :ok)))))

(def-proc-test ^:parallel handle-info--timeout-returned--0
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:msg :init] [:noreply :timeout 0]
                                 [:timeout :timeout] (do (deliver done true)
                                                         [:noreply :done])))}]
    (match (gs/start-link! server)
           [:ok pid] (! pid :msg))
    (is (await-completion!! done 100)
        ":timeout message must be sent to gen-server")))

(def-proc-test ^:parallel handle-info--timeout-returned--100
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:msg :init] [:noreply :timeout 100]
                                 [:timeout :timeout] (do (deliver done true)
                                                         [:noreply :done])))}]
    (match (gs/start-link! server)
           [:ok pid] (! pid :msg))
    (is (thrown? Exception (await-completion!! done 50))
        ":timeout message must not be sent to gen-server before timeout")
    (is (await-completion!! done 150)
        ":timeout message must be sent to gen-server after timeout")))

;; ====================================================================
;; Tests for terminate combinations (--terminate-throws variants)
;;
;; These tests verify behavior when terminate callback throws an exception
;; ====================================================================

(def-proc-test ^:parallel handle-call--bad-return--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [_ _ _] :bad-return)
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--callback-throws--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:a 1})))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:b 2}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
(is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:b 2}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--exit-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :abnormal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--exit-normal--terminate-throws
  (let [server {:init (fn [] [:ok nil])
                :handle-call (fn [_ _ _] (process/exit :normal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (process/flag :trap-exit true)
    (match (gs/start-link! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-message 50) [:exit [pid _]] :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--stop-normal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [_ _ state] [:stop :normal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--stop-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [_ _ state] [:stop :abnormal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--stop-normal-reply--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [x _ state] [:stop :normal (inc x) state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= 2 (gs/call! pid 1 50))
            "call must return response even if terminate throws")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--stop-abnormal-reply--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-call (fn [x _ state] [:stop :abnormal (inc x) state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= 2 (gs/call! pid 1 50))
            "call must return response even if terminate throws")
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

(def-proc-test ^:parallel handle-call--undefined-callback--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok :state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:a 1}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " terminate"))
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit on bad return from handle-call")))))

;; ====================================================================
;; handle-cast terminate-throws tests
;; ====================================================================

(def-proc-test ^:parallel handle-cast--bad-return--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] :bad-return)
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-cast--callback-throws--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:a 1})))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:b 2}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-cast--exit-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ _] (process/exit :abnormal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-cast--stop-normal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ state] [:stop :normal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-cast--stop-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-cast (fn [_ state] [:stop :abnormal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-cast--undefined-callback--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok :state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (gs/cast pid nil)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

;; ====================================================================
;; handle-info terminate-throws tests
;; ====================================================================

(def-proc-test ^:parallel handle-info--bad-return--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] :bad-return)
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-info--callback-throws--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:a 1})))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:b 2}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-info--exit-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ _] (process/exit :abnormal))
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-info--stop-normal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ state] [:stop :normal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-info--stop-abnormal--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok nil])
                :handle-info (fn [_ state] [:stop :abnormal state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

(def-proc-test ^:parallel handle-info--undefined-callback--terminate-throws
  (let [done2 (promise)
        server {:init (fn []
                        (spawn-exit-watcher done2 100)
                        [:ok :state])
                :terminate (fn [_reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (! pid :message)
        (is (match (await-completion!! done2 50)
                   [:ok [:reason [:exception {:message "TEST" :data {:a 1}}]]]
                   :ok)
            "gen-server must exit with exception from terminate")))))

;; ====================================================================
;; handle-call terminate-undefined tests
;; These test what happens when terminate callback is not defined
;; ====================================================================

(def-proc-test ^:parallel handle-call--bad-return--terminate-undefined
  (let [done (promise)
        server {:init
                (fn []
                  (process/spawn-opt
                    (process/proc-fn []
                      (process/receive!
                        [:EXIT pid reason] (deliver done [:reason reason])
                        (after 50 (deliver done :timeout))))
                    {:link true :flags {:trap-exit true}})
                  [:ok :state])
                :handle-call (fn [_ _ _] :bad-return)}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:bad-return-value 'handle-call :bad-return]
                       [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            (str "call must exit with reason containing bad-value returned from"
                 " handle-call"))
        (is (match (await-completion!! done 50)
              [:ok [:reason [:bad-return-value 'handle-call :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-call"))))))

(def-proc-test ^:parallel handle-call--callback-throws--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-call (fn [_ _ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [[:exception {:message "TEST"
                                    :class "clojure.lang.ExceptionInfo"
                                    :data {:b 2}}]
                       [`gs/call [pid nil 50]]]]
               (let [[kind [[reason ex] f]] (process/ex-catch
                                              [:ok (gs/call! pid nil 50)])]
                 [kind [[reason (dissoc ex :stack-trace)] f]]))
            (str "call must exit with reason containing exception thrown from"
                 " handle-call"))
        (is (match (await-completion!! done 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-call"))))))

(def-proc-test ^:parallel handle-call--exit-abnormal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-call (fn [_ _ _] (process/exit :abnormal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            (str "call must exit with reason containing reason passed to exit"
                 " in handle-call"))
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-call"))))))

(def-proc-test ^:parallel handle-call--exit-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-call (fn [_ _ _] (process/exit :normal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            (str "call must exit with reason containing reason passed to exit"
                 " in handle-call"))
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-call"))))))

(def-proc-test ^:parallel handle-call--stop-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-call (fn [_ _ state] [:stop :normal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:normal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            (str "call must exit with reason containing reason returned by"
                 " handle-call"))
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-call")))))

(def-proc-test ^:parallel handle-call--stop-abnormal--terminate-undefined
  (let [done (promise)
        server {:init
                (fn []
                  (spawn-exit-watcher done 50)
                  [:ok :state])
                :handle-call (fn [_ _ state] [:stop :abnormal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= [:EXIT [:abnormal [`gs/call [pid nil 50]]]]
               (process/ex-catch [:ok (gs/call! pid nil 50)]))
            (str "call must exit with reason containing reason returned by"
                 "  handle-call"))
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-call")))))

(def-proc-test ^:parallel handle-call--undefined-callback--terminate-undefined
  (let [done (promise)
        server {:init
                (fn []
                  (spawn-exit-watcher done 50)
                  [:ok :state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (match (process/ex-catch [:ok (gs/call! pid nil 50)])
                   [:EXIT [[:undef ['handle-call [nil _ :state]]]
                           [`gs/call [pid nil 50]]]] :ok)
            (str "call must exit with reason containing arguments passed to"
                 " handle-call"))
        (is (match (await-completion!! done 50)
              [:ok [:reason [:undef ['handle-call [nil _ :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments passed"
                 " to handle-call"))))))

;; ====================================================================
;; handle-cast terminate-undefined tests
;; ====================================================================

(def-proc-test ^:parallel handle-cast--bad-return--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ _] :bad-return)}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50)
              [:ok [:reason [:bad-return-value 'handle-cast :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-cast"))))))

(def-proc-test ^:parallel handle-cast--callback-throws--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-cast"))))))

(def-proc-test ^:parallel handle-cast--exit-abnormal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ _] (process/exit :abnormal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-cast"))))))

(def-proc-test ^:parallel handle-cast--exit-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ _] (process/exit :normal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-cast"))))))

(def-proc-test ^:parallel handle-cast--stop-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ state] [:stop :normal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast--stop-abnormal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ state] [:stop :abnormal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-cast")))))

(def-proc-test ^:parallel handle-cast--undefined-callback--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50)
              [:ok [:reason [:undef ['handle-cast [nil :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments passed"
                 " to handle-cast"))))))

;; ====================================================================
;; handle-info terminate-undefined tests
;; ====================================================================

(def-proc-test ^:parallel handle-info--bad-return--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ _] :bad-return)}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50)
              [:ok [:reason [:bad-return-value 'handle-info :bad-return]]] :ok)
            (str "gen-server must exit with reason containing bad value"
                 "returned from handle-info"))))))

(def-proc-test ^:parallel handle-info--callback-throws--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ _] (throw (ex-info "TEST" {:b 2})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:b 2}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from handle-info"))))))

(def-proc-test ^:parallel handle-info--exit-abnormal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ _] (process/exit :abnormal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-info"))))))

(def-proc-test ^:parallel handle-info--exit-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ _] (process/exit :normal))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            (str "gen-server must exit with reason passed to exit in"
                 " handle-info"))))))

(def-proc-test ^:parallel handle-info--stop-normal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ state] [:stop :normal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50) [:ok [:reason :normal]] :ok)
            "gen-server must exit with reason returned by handle-info")))))

(def-proc-test ^:parallel handle-info--stop-abnormal--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ state] [:stop :abnormal state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50) [:ok [:reason :abnormal]] :ok)
            "gen-server must exit with reason returned by handle-info")))))

(def-proc-test ^:parallel handle-info--undefined-callback--terminate-undefined
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50)
              [:ok [:reason [:undef ['handle-info [1 :state]]]]] :ok)
            (str "gen-server must exit with reason containing arguments"
                 " passed to handle-info"))))))

;; =============================================================================
;; Missing tests from original otplike - async value tests
;; =============================================================================

(def-proc-test ^:parallel handle-call--async-value-returned
  (process/flag :trap-exit true)
  (let [server {:init (fn [] [:ok :init])
                :handle-call (fn [msg _from state]
                               (process/async [:reply :test-response state]))}]
    (match (gs/start-link! server)
      [:ok pid]
      (is (= :test-response (gs/call! pid :call))
        "server must return response returned from handle-call")))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-call (fn [msg _from state]
                               (match [msg state]
                                 [:msg :init]
                                 (process/async [:reply :ok :timeout 100])))
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:timeout :timeout]
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
      [:ok pid] (gs/call! pid :msg))
    (is (= [:ok true] (await-completion!! done 150))
        "timeout returned from handle-call must occur"))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-call (fn [msg _from state]
                               (process/async [:stop :normal state]))
                :terminate (fn [reason state]
                             (match reason
                               :normal (deliver done true)))}]
    (match (gs/start-link! server)
      [:ok pid] (process/ex-catch (gs/call! pid :msg)))
    (is (= [:ok true] (await-completion!! done 50))
        "server must terminate with reason returned from handle-call")))

(def-proc-test ^:parallel handle-cast--async-value-returned
  (process/flag :trap-exit true)
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-cast (fn [msg state]
                               (process/async [:noreply :timeout 100]))
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:timeout :timeout]
                                 (deliver done true))
                               [:noreply state])}]
    (match (gs/start-link! server)
      [:ok pid] (gs/cast pid :msg))
    (is (= [:ok true] (await-completion!! done 150))
        "timeout returned from handle-cast must occur"))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-cast (fn [msg state]
                               (process/async [:stop :normal state]))
                :terminate (fn [reason state]
                             (match reason
                               :normal (deliver done true)))}]
    (match (gs/start-link! server)
      [:ok pid] (gs/cast pid :msg))
    (is (= [:ok true] (await-completion!! done 50))
        "server must terminate with reason returned from handle-cast"))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-cast (fn [msg state]
                               (process/async [:stop :abnormal state]))
                :terminate (fn [reason state]
                             (match reason
                               :abnormal (deliver done true)))}]
    (match (gs/start-link! server)
      [:ok pid] (gs/cast pid :msg))
    (is (= [:ok true] (await-completion!! done 50))
        "server must terminate with reason returned form handle-cast")))

(def-proc-test ^:parallel handle-info--async-value-returned
  (process/flag :trap-exit true)
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:msg :init]
                                 (process/async [:noreply :timeout 100])
                                 [:timeout :timeout]
                                 (do
                                   (deliver done true)
                                   [:stop :normal state])))}]
    (match (gs/start-link! server)
      [:ok pid] (! pid :msg))
    (is (= [:ok true] (await-completion!! done 150))
        "timeout returned from handle-info must occur"))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-info (fn [msg state]
                               (process/async [:stop :normal state]))
                :terminate (fn [reason state]
                             (match reason
                               :normal (deliver done true)))}]
    (match (gs/start-link! server)
      [:ok pid] (! pid :msg))
    (is (= [:ok true] (await-completion!! done 50))
        "server must terminate with reason returned from handle-info"))
  (let [done (promise)
        server {:init (fn [] [:ok :init])
                :handle-info (fn [msg state]
                               (process/async [:stop :abnormal state]))
                :terminate (fn [reason state]
                             (match reason
                               :abnormal (deliver done true)))}]
    (match (gs/start-link! server)
      [:ok pid] (! pid :msg))
    (is (= [:ok true] (await-completion!! done 50))
        "server must terminate with reason returned form handle-info")))

;; =============================================================================
;; Missing tests from original otplike - terminate-throws tests
;; =============================================================================

(def-proc-test ^:parallel handle-cast--exit-normal--terminate-throws
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-cast (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (is (= true (gs/cast pid nil))
            "cast must return true if server is alive")
        (is (match (await-completion!! done 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

(def-proc-test ^:parallel handle-info--exit-normal--terminate-throws
  (let [done (promise)
        server {:init (fn []
                        (spawn-exit-watcher done 50)
                        [:ok :state])
                :handle-info (fn [_ _] (process/exit :normal))
                :terminate (fn [reason _] (throw (ex-info "TEST" {:a 1})))}]
    (match (gs/start! server)
      [:ok pid]
      (do
        (match (! pid 1) true :ok)
        (is (match (await-completion!! done 50)
              [:ok [:reason [:exception {:message "TEST"
                                         :class "clojure.lang.ExceptionInfo"
                                         :data {:a 1}}]]]
              :ok)
            (str "gen-server must exit with reason containing exception thrown"
                 " from terminate"))))))

;; =============================================================================
;; Missing tests from original otplike - call edge cases
;; =============================================================================

(deftest ^:parallel call--exits-just-after-server-exited
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [x from state]
                               (process/exit (process/self) :test)
                               [:noreply state])}]
    (process/spawn
     (process/proc-fn []
       (match (gs/start! server)
         [:ok pid]
         (do
           (is (= [:EXIT [:test [`gs/call [pid :msg 500]]]]
                  (process/ex-catch [:ok (gs/call! pid :msg 500)]))
               "call must return the reason gen-server exited with")
           (deliver done true)))))
    (is (= [:ok true] (await-completion!! done 100))
        "call must exit just after gen-server exited")))

(deftest ^:parallel call--no-unexpected-monitor-mesages-arrive-after-the-call
  (let [done (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [_ _ state]
                               [:stop :test :ok state])}]
    (process/spawn
     (process/proc-fn []
       (match (gs/start! server)
         [:ok pid]
         (case (gs/call! pid :msg 500)
           :ok
           (do
             (is (= :timeout (await-message 100))
                 "no unexpected messages must arrive after the call")
             (deliver done true))))))
    (await-completion!! done 300))
  (let [done (promise)
        done1 (promise)
        server {:init (fn [] [:ok nil])
                :handle-call (fn [_ _ state]
                               [:reply :ok state 50])
                :handle-info (fn [_ _]
                               (deliver done1 true)
                               (process/exit :test))}]
    (process/spawn
     (process/proc-fn []
       (match (gs/start! server)
         [:ok pid]
         (case (gs/call! pid :msg 500)
           :ok
           (do
             (await-completion!! done1 100)
             (Thread/sleep 50)
             (is (= :timeout (await-message 100))
                 "no unexpected messages must arrive after the call")
             (deliver done true))))))
    (await-completion!! done 500)))
