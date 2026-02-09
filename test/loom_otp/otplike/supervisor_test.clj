(ns loom-otp.otplike.supervisor-test
  "Tests for loom-otp.otplike.supervisor compatibility layer.
   
   Adapted from otplike.supervisor-test with these changes:
   - Uses loom-otp.otplike.process and test utilities
   - Exhaustive tests use core.async for event coordination (test infrastructure only)"
  (:require [clojure.test :refer [is deftest use-fixtures]]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<!! <! >! >!!]]
            [clojure.math.combinatorics :as combinatorics]
            [clojure.pprint :as pprint]
            [loom-otp.otplike.process :as process :refer [!]]
            [loom-otp.otplike.test-util :refer :all]
            [loom-otp.otplike.proc-util :as proc-util]
            [loom-otp.otplike.supervisor :as sup]
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
;; (start-link [sup-fn args])
;; =============================================================================

(def-proc-test ^:parallel start-link--no-children
  (let [done (promise)
        init-fn (fn []
                  (deliver done true)
                  [:ok [{} []]])]
    (match (sup/start-link! init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (= [:ok true] (await-completion!! done 50))
            "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel start-link--single-child
  (let [sup-init-done (promise)
        server-init-done (promise)
        sup-flags {}
        server {:init (fn []
                        (deliver server-init-done true)
                        [:ok :state])}
        children-spec [{:id 1
                        :start [gs/start-link [server]]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")
        (is (= [:ok true] (await-completion!! server-init-done 50))
            "supervisor must call child's start-fn to start it")))))

(def-proc-test ^:parallel start-link--multiple-children
  (let [sup-init-done (promise)
        s1-init-done (promise)
        s2-init-done (promise)
        s3-init-done (promise)
        sup-flags {}
        make-child
        (fn [await-prom done-prom id]
          {:id id
           :start
           [gs/start-link
            [{:init (fn []
                      (process/async
                        (is (= [:ok true] (await-completion!! await-prom 50))
                            "supervisor must start children in spec's order")
                        (deliver done-prom true)
                        [:ok :state]))}]]})
        children-spec [(make-child sup-init-done s1-init-done 0)
                       (make-child s1-init-done s2-init-done 1)
                       (make-child s2-init-done s3-init-done 2)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:ok pid]
      (do
        (is (process/pid? pid)
            "start-link must return supervisor's pid")
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")
        (is (= [:ok true] (await-completion!! s3-init-done 100))
            "supervisor must call child's start-fn to start it")))))

(def-proc-test ^:parallel start-link--duplicate-child-id
  ;; Need to trap exits because start-link creates a linked process
  ;; which will send an exit signal when it fails
  (process/flag :trap-exit true)
  (let [sup-init-done (promise)
        sup-flags {}
        make-child (fn [id]
                     {:id id
                      :start [gs/start-link
                              [{:init (fn []
                                        (is false "child must not be started")
                                        [:ok :state])}]]})
        children-spec [(make-child :id1) (make-child :id2) (make-child :id2)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok sup-spec])]
    (match (process/ex-catch (sup/start-link! init-fn))
      [:error [:bad-child-specs [:duplicate-child-id :id2]]]
      (is (= [:ok true] (await-completion!! sup-init-done 50))
          "supervisor must call init-fn to get its spec"))))

(def-proc-test ^:parallel start-link--bad-return--not-allowed-return
  (process/flag :trap-exit true)
  (let [sup-init-done (promise)
        init-fn (fn []
                  (deliver sup-init-done true)
                  {:ok []})]
    (match (sup/start-link! init-fn)
      [:error [:bad-return _]]
      (do
        (Thread/sleep 50)
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel start-link--bad-return--no-child-id
  (let [sup-init-done (promise)
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok [{} [{:start
                             [gs/start-link
                              [{:init (fn []
                                        (is false "child must not be started")
                                        [:ok :state])}]]}]]])]
    (process/flag :trap-exit true)
    (match (sup/start-link! init-fn)
      [:error [:bad-child-specs _]]
      (is (= [:ok true] (await-completion!! sup-init-done 50))
          "supervisor must call init-fn to get its spec"))))

(def-proc-test ^:parallel start-link--invalid-arguments
  (is (thrown? Exception (sup/start-link! "not-fn"))
      "start-link must throw on invalid arguments"))

;; =============================================================================
;; Child init returns error
;; =============================================================================

(defn test-start-link--child-init-returns-error [init-fn reason]
  (proc-util/execute-proc!!
    ;; Trap exits so we don't get killed when supervisor exits
    (process/flag :trap-exit true)
    (let [sup-init-done (promise)
          sup-flags {}
          children-spec [{:id :child-id
                          :start [gs/start-link [{:init init-fn}]]}]
          sup-spec [sup-flags children-spec]
          init-fn (fn []
                    (deliver sup-init-done true)
                    [:ok sup-spec])]
      (match (sup/start-link! init-fn)
        [:error [:shutdown [:failed-to-start-child :child-id reason]]]
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")))))

(deftest ^:parallel start-link--child-init-returns-error--exit-normal
  (test-start-link--child-init-returns-error
    (fn [] (process/exit :normal))
    :normal))

(deftest ^:parallel start-link--child-init-returns-error--exit-abnormal
  (test-start-link--child-init-returns-error
    (fn [] (process/exit :abnormal))
    :abnormal))

(deftest ^:parallel start-link--child-init-returns-error--stop-with-reason
  (test-start-link--child-init-returns-error
    (fn [] [:stop :some-reason])
    :some-reason))

;; =============================================================================
;; Multiple children - init errors
;; =============================================================================

(def-proc-test ^:parallel
  start-link--multiple-children--first-child-init-returns-error
  (process/flag :trap-exit true)
  (let [child1-done (promise)
        sup-init-done (promise)
        sup-flags {}
        error-child-init (fn []
                           (deliver child1-done true)
                           [:stop :abnormal])
        healthy-child-init (fn []
                             (is false "child must not be started")
                             [:ok :state])
        child-spec (fn [id init-fn]
                     {:id id
                      :start [gs/start-link [{:init init-fn}]]})
        children-spec [(child-spec :id1 error-child-init)
                       (child-spec :id2 healthy-child-init)
                       (child-spec :id3 healthy-child-init)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:error [:shutdown [:failed-to-start-child :id1 :abnormal]]]
      (do
        (is (= [:ok true] (await-completion!! child1-done 50))
            "first child must be started")
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel
  start-link--multiple-children--middle-child-init-returns-error
  (process/flag :trap-exit true)
  (let [child1-init-done (promise)
        child1-terminate-done (promise)
        error-child-done (promise)
        sup-init-done (promise)
        sup-flags {}
        healthy-child1-init (fn []
                              (deliver child1-init-done true)
                              [:ok :state])
        child1-terminate
        (fn [reason _]
          (deliver child1-terminate-done true)
          (is (= :shutdown reason)
              "child must be stopped with :shutdown reason"))
        error-child-init (fn []
                           (deliver error-child-done true)
                           [:stop :abnormal])
        healthy-child2-init (fn []
                              (is false "child must not be started")
                              [:ok :state])
        child-spec (fn [id init-fn terminate-fn]
                     {:id id
                      :start [gs/start-link
                              [{:init init-fn :terminate terminate-fn}
                               []
                               {:spawn-opt {:flags {:trap-exit true}}}]]})
        children-spec [(child-spec :id1 healthy-child1-init child1-terminate)
                       (child-spec :id2 error-child-init nil)
                       (child-spec :id3 healthy-child2-init nil)]
        sup-spec [sup-flags children-spec]
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok sup-spec])]
    (match (sup/start-link! init-fn)
      [:error [:shutdown [:failed-to-start-child :id2 :abnormal]]]
      (do
        (is (= [:ok true] (await-completion!! child1-init-done 50))
            "first child must be started")
        (is (= [:ok true] (await-completion!! error-child-done 50))
            "error child must be started")
        (is (= [:ok true] (await-completion!! child1-terminate-done 100))
            "first child must be terminated")
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")))))

;; =============================================================================
;; Already registered
;; =============================================================================

(def-proc-test ^:parallel start-link--returns-error-when-already-registered
  (let [reg-name (uuid-keyword)
        done (promise)
        sup-fn (fn [] [:ok [{} []]])
        pfn (process/proc-fn [] (is (= [:ok true] (await-completion!! done 1000))))
        pid (process/spawn-opt pfn {:register reg-name})]
    (is
      (= [:error [:already-registered pid]]
         (sup/start-link! reg-name sup-fn [])))
    (deliver done true)))

;; Alias with original typo for compatibility
(def-proc-test ^:parallel start-link--returns-error-when-already-registred
  (let [reg-name (uuid-keyword)
        done (promise)
        sup-fn (fn [] [:ok [{} []]])
        pfn (process/proc-fn [] (is (= [:ok true] (await-completion!! done 1000))))
        pid (process/spawn-opt pfn {:register reg-name})]
    (is
      (= [:error [:already-registered pid]]
         (sup/start-link! reg-name sup-fn [])))
    (deliver done true)))

(def-proc-test ^:parallel start-link--doesnt-call-init-when-already-registered
  (let [reg-name (uuid-keyword)
        done (promise)
        sup-fn
        (fn []
          (is false
              "proc fn must not be called if the name is already registered")
          [:ok [{} []]])
        pfn (process/proc-fn [] (is (= [:ok true] (await-completion!! done 1000))))]
    (process/spawn-opt pfn {:register reg-name})
    (sup/start-link! reg-name sup-fn [])
    (Thread/sleep 50)
    (deliver done true)))

;; =============================================================================
;; Bad child specs / supervisor flags tests
;; =============================================================================

(def-proc-test ^:parallel start-link--bad-return--bad-child-start-fn
  (let [sup-init-done (promise)
        init-fn (fn []
                  (deliver sup-init-done true)
                  [:ok [{} [{:id :child1
                             :start ["not a function" []]}]]])]
    (process/flag :trap-exit true)
    (match (sup/start-link! init-fn)
      [:error [:bad-child-specs _]]
      (do
        (is (match (await-message 50)
              [:exit [_ [:bad-child-specs _]]] :ok)
            "process must exit after supervisor/init error")
        (is (= [:ok true] (await-completion!! sup-init-done 50))
            "supervisor must call init-fn to get its spec")))))

(def-proc-test ^:parallel start-link--bad-return--bad-supervisor-flags
  (let [make-child (fn [id]
                     {:id id
                      :start [gs/start-link
                              [{:init (fn []
                                        (is false "child must not be started")
                                        [:ok :state])}]]})]
    ;; Test bad strategy (string instead of keyword)
    (proc-util/execute-proc!!
      (process/flag :trap-exit true)
      (let [sup-init-done (promise)
            sup-flags {:strategy "one-for-one"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (deliver sup-init-done true)
                      [:ok sup-spec])]
        (match (sup/start-link! init-fn)
          [:error [:bad-supervisor-flags _]]
          (is (= [:ok true] (await-completion!! sup-init-done 50))
              "supervisor must call init-fn to get its spec"))))
    ;; Test bad intensity (string instead of integer)
    (proc-util/execute-proc!!
      (process/flag :trap-exit true)
      (let [sup-init-done (promise)
            sup-flags {:intensity "2"}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (deliver sup-init-done true)
                      [:ok sup-spec])]
        (match (sup/start-link! init-fn)
          [:error [:bad-supervisor-flags _]]
          (is (= [:ok true] (await-completion!! sup-init-done 50))
              "supervisor must call init-fn to get its spec"))))
    ;; Test bad period (keyword instead of integer)
    (proc-util/execute-proc!!
      (process/flag :trap-exit true)
      (let [sup-init-done (promise)
            sup-flags {:period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (deliver sup-init-done true)
                      [:ok sup-spec])]
        (match (sup/start-link! init-fn)
          [:error [:bad-supervisor-flags _]]
          (is (= [:ok true] (await-completion!! sup-init-done 50))
              "supervisor must call init-fn to get its spec"))))
    ;; Test multiple bad flags
    (proc-util/execute-proc!!
      (process/flag :trap-exit true)
      (let [sup-init-done (promise)
            sup-flags {:strategy "one-for-one"
                       :intensity "2"
                       :period :1}
            children-spec (map make-child [:id1])
            sup-spec [sup-flags children-spec]
            init-fn (fn []
                      (deliver sup-init-done true)
                      [:ok sup-spec])]
        (match (sup/start-link! init-fn)
          [:error [:bad-supervisor-flags _]]
          (is (= [:ok true] (await-completion!! sup-init-done 50))
              "supervisor must call init-fn to get its spec"))))))

;; =============================================================================
;; Missing tests from original otplike - async value returned
;; =============================================================================

(def-proc-test ^:parallel init--async-value-returned
  (process/flag :trap-exit true)
  (let [done (promise)
        sup-flags {}
        server {:init (fn [] (process/async [:ok :init]))
                :handle-info (fn [msg state]
                               (match [msg state]
                                 [:msg :init] (deliver done true)))}
        reg-name (gensym "test-gs")
        children-spec [{:id :gs1
                        :start [gs/start-link [reg-name server [] {}]]}]
        sup-spec [sup-flags children-spec]
        init-fn (fn [] (process/async [:ok sup-spec]))]
    (match (sup/start-link! init-fn)
      [:ok _] (! reg-name :msg))
    (is (= [:ok true] (await-completion!! done 50)) "child must be started")))

;; =============================================================================
;; Exhaustive strategy tests - helpers
;; =============================================================================

(defn- map-vals
  "Apply f to each value in map m."
  [f m]
  (into {} (for [[k v] m] [k (f v)])))

(defn- log!
  "Log a message to a channel with timestamp."
  [log fmt & args]
  (let [ms (mod (System/currentTimeMillis) 100000)]
    (async/put! log [ms fmt args])))

(defn- await-events
  "Wait for expected events on events-chan.
   Returns a channel that closes when all events are received or on error."
  [events-chan log timeout-ms {:keys [in-order unordered] :as expected}]
  (async/go
    (let [timeout (async/timeout timeout-ms)]
      (loop [expect-in-order in-order
             other-expected (set unordered)]
        (if (and (empty? expect-in-order) (empty? other-expected))
          :ok
          (let [[event port] (async/alts! [events-chan timeout])]
            (cond
              (= port timeout)
              (log! log "!ERROR timeout, expected in order %s, other %s"
                    (pr-str expect-in-order) (pr-str other-expected))

              (nil? event)
              (log! log "!ERROR events-chan closed, expected in order %s, other %s"
                    (pr-str expect-in-order) (pr-str other-expected))

              (= event (first expect-in-order))
              (do
                (log! log "in order %s" event)
                (recur (rest expect-in-order) other-expected))

              (contains? other-expected event)
              (do
                (log! log "other expected %s" event)
                (recur expect-in-order (disj other-expected event)))

              :else
              (log! log "!ERROR unexpected event %s, expected in order %s, other %s"
                    event (pr-str expect-in-order) (pr-str other-expected)))))))))

(defn- report-progress
  "Report a test progress event to the events channel."
  [events-chan msg]
  (if-not (async/put! events-chan msg)
    (printf "!ERROR event after test is finished: %s%n" msg)))

(defn- child-proc
  "Child process that responds to exit commands.
   Adapted from otplike's proc-defn to a regular function returning a proc-fn."
  [id log {:keys [exit-delay exit-failure] :as problem}]
  (process/proc-fn []
    (report-progress log [id :process-start])
    (try
      (process/spawn-opt
        (process/proc-fn []
          (process/receive!
            [:EXIT _ reason] (report-progress log [id :exit-watcher reason])
            msg (printf "!!! unexpected message %s%n" msg)))
        {:link true
         :flags {:trap-exit true}
         :register (str "exit-watcher-" id)})
      (catch Exception e
        (report-progress log [id :exit-watcher :killed])))
    
    (process/receive!
      [:exit reason] (do
                       (report-progress log [id :exit-command reason])
                       (process/exit reason))
      [:EXIT _ reason] (do
                         (report-progress log [id :exit reason])
                         (if exit-delay
                           (Thread/sleep exit-delay))
                         (if exit-failure
                           (process/exit exit-failure)
                           (process/exit reason))))))

(defn- start-child
  "Start a child process, potentially failing if configured to do so."
  [{id :id} log problems]
  (report-progress log [id :start-fn])
  (let [{:keys [start-failure] :as problem} (first @problems)]
    (swap! problems rest)
    (if start-failure
      [:error start-failure]
      [:ok (process/spawn-opt
             (child-proc id log problem)
             {:link true
              :register id
              :flags {:trap-exit true}})])))

(defn- ->expected-events
  "Create expected events structure."
  [in-order unordered]
  {:in-order in-order
   :unordered unordered})

(defn- expected-exits
  "Calculate expected exit events for children being shut down."
  [children]
  (let [children (reverse children)]
    (->expected-events
      (->> children
           (filter #(not= :brutal-kill (:shutdown %)))
           (map #(vector (:id %) :exit :shutdown)))
      (map (fn [{:keys [id shutdown type] [current-problem & _] :problems}]
             (case shutdown
               :brutal-kill [id :exit-watcher :killed]
               (if (:exit-delay current-problem)
                 (if (= :supervisor type)
                   [id :exit-watcher :shutdown]
                   [id :exit-watcher :killed])
                 (if-let [reason (:exit-failure current-problem)]
                   [id :exit-watcher reason]
                   [id :exit-watcher :shutdown]))))
           children))))

(defn- exit-command-events
  "Events expected when sending an exit command."
  [{:keys [id reason]}]
  (->expected-events [[id :exit-command reason]]
                     [[id :exit-watcher reason]]))

(defn- start-events
  "Events expected when starting children."
  [children]
  (->expected-events (map #(vector (:id %) :start-fn) children)
                     (map #(vector (:id %) :process-start) children)))

(defn- concat-events
  "Concatenate multiple expected event structures."
  [& events]
  (apply merge-with concat events))

(defn- delete-child
  "Remove a child from the list by id."
  [children id]
  (remove #(= id (:id %)) children))

(defn- get-child
  "Find a child by id."
  [children id]
  (some #(if (= id (:id %)) %) children))

(defn- replace-child
  "Replace a child in the list."
  [children {id :id :as child}]
  (match (split-with #(not= id (:id %)) children)
    [before ([_ & after] :seq)] (concat before [child] after)))

(defn- survive-exit?
  "Check if a child should survive an exit with given reason."
  [{:keys [restart] :as _child} reason]
  (or (= :permanent restart)
      (and (= :transient restart)
           (not= :normal reason))))

;; =============================================================================
;; Exhaustive strategy tests - restart processing
;; =============================================================================

(defn- process-restart--one-for-one
  "Process restart for one-for-one strategy."
  [id children restarts {:keys [intensity] :as test}]
  (let [child (update (get-child children id) :problems rest)
        [start-failures other-problems]
        (split-with :start-failure (:problems child))
        child (assoc child :problems other-problems)
        failures-count (count start-failures)
        extra-restarts-count (min failures-count (- intensity restarts))
        restarts (+ restarts failures-count)
        expected-restarts (->expected-events
                            (repeat (inc extra-restarts-count) [id :start-fn]) [])
        expected (concat-events expected-restarts
                                (->expected-events [] [[id :process-start]]))]
    (if (> restarts intensity)
      [:exit expected-restarts (delete-child children id)]
      [:ok expected restarts (replace-child children child)])))

(defn- process-restart--one-for-all
  "Process restart for one-for-all strategy."
  [id children restarts {:keys [intensity] :as test}]
  (let [expected (expected-exits (delete-child children id))
        children-left (filter #(survive-exit? % :shutdown) children)
        children-left (map #(update % :problems rest) children-left)]
    (loop [restarts restarts
           children-left children-left
           expected expected]
      (let [[started not-started]
            (split-with #(-> % :problems first :start-failure not) children-left)
            failed (first not-started)
            expected (concat-events
                       expected
                       (start-events started)
                       (->expected-events
                         (if-let [{id :id} failed] [[id :start-fn]] []) []))]
        (if (seq not-started)
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              [:exit expected started]
              (let [expected (concat-events expected (expected-exits started))
                    new-started (map #(update % :problems rest) started)
                    new-children
                    (concat new-started
                            (if failed [(update failed :problems rest)])
                            (rest not-started))]
                (recur restarts new-children expected))))
          [:ok expected restarts children-left])))))

(defn- process-restart--rest-for-one
  "Process restart for rest-for-one strategy."
  [id children restarts {:keys [intensity] :as test}]
  (let [[running [child & rest-children]]
        (split-with #(not= (:id %) id) children)
        events (expected-exits rest-children)
        rest-children-left (filter #(survive-exit? % :shutdown) rest-children)
        to-start (cons child rest-children-left)
        to-start (map #(update % :problems rest) to-start)]
    (loop [restarts restarts
           to-start to-start
           running running
           events events]
      (let [[started [failed & not-started]]
            (split-with #(-> % :problems first :start-failure not) to-start)
            events (concat-events
                     events
                     (start-events started)
                     (->expected-events
                       (if-let [{id :id} failed] [[id :start-fn]] []) []))
            running (concat running started)
            failed (if failed (update failed :problems rest))
            to-start (if failed (cons failed not-started))]
        (if failed
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              [:exit events running]
              (recur restarts to-start running events)))
          [:ok events restarts running])))))

;; =============================================================================
;; Exhaustive strategy tests - test execution
;; =============================================================================

(defn- execute-test-commands!!
  "Execute exit commands and verify restarts.
   Called from a virtual thread, uses <!! for blocking channel operations.
   Returns the remaining children directly (not in a channel)."
  [await-events-fn children {:keys [strategy exits intensity] :as test}]
  (loop [exits exits
         children children
         restarts 0]
    (if (or (empty? exits) (empty? children))
      children
      (let [process-restart (case strategy
                              :one-for-one process-restart--one-for-one
                              :one-for-all process-restart--one-for-all
                              :rest-for-one process-restart--rest-for-one)
            {:keys [id reason] :as exit} (first exits)
            expected (exit-command-events exit)]
        (! id [:exit reason])
        (if-not (survive-exit? (get-child children id) reason)
          (do
            (<!! (await-events-fn 5000 expected))
            (recur (rest exits) (delete-child children id) restarts))
          (let [restarts (inc restarts)]
            (if (> restarts intensity)
              (do
                (<!! (await-events-fn
                       5000
                       (concat-events
                         expected
                         (expected-exits (delete-child children id)))))
                [])
              (match (process-restart id children restarts test)
                [:exit expected-1 children-left]
                (do
                  (<!! (await-events-fn
                         5000
                         (concat-events expected
                                        expected-1
                                        (expected-exits children-left))))
                  [])
                [:ok expected-1 new-restarts children-left]
                (do
                  (<!! (await-events-fn
                         5000 (concat-events expected expected-1)))
                  (recur (rest exits) children-left new-restarts))))))))))

(defn- run-test-process
  "Run a single test process.
   The supervisor is started in a virtual thread (via execute-proc).
   Channel operations use <!! since we're blocking a virtual thread.
   Returns a go channel that completes when the test is done."
  [events-chan log children {:keys [strategy intensity period] :as test}]
  (let [await-events-fn (partial await-events events-chan log)
        sup-flags {:strategy strategy
                   :intensity intensity
                   :period period}
        add-start
        #(assoc % :start [start-child [% events-chan (atom (:problems %))]])
        children-spec (map add-start children)
        ;; Start the test in a process context (virtual thread) - non-blocking
        proc-result
        (proc-util/execute-proc
          (try
            (process/flag :trap-exit true)
            (match (sup/start-link! (constantly [:ok [sup-flags children-spec]]))
              [:ok pid]
              (do
                (<!! (await-events-fn 5000 (start-events children)))
                (let [children-left
                      (execute-test-commands!! await-events-fn children test)]
                  (process/exit pid :normal)
                  (<!! (await-events-fn 5000 (expected-exits children-left)))))
              res
              (log! log "!ERROR %s" res))
            (catch Throwable t
              (log! log "!ERROR exception in test: %s" (pr-str t)))))]
    ;; Return a channel that completes when the test process is done
    (async/go
      ;; Wait for the process to complete (with timeout)
      (let [result (deref proc-result 30000 :timeout)]
        (when (= result :timeout)
          (log! log "!ERROR test process timed out"))))))

(defn- run-test
  "Run a single test with logging."
  [strategy {:keys [children problems] :as test}]
  (async/go
    (let [test (assoc test :strategy strategy)
          log (async/chan 100)  ;; Buffered channel to avoid blocking
          events-chan (async/chan 100)
          problems-map (->> problems
                            (group-by :id)
                            (map-vals #(if (:start-failure (first %)) (cons {} %) %)))
          children (map #(assoc % :problems (problems-map (:id %))) children)]
      (try
        (log! log "RUN test%n%s" (pprint/write test :stream nil))
        (<! (run-test-process events-chan log children test))
        (log! log "DONE run-test")
        log
        (catch Throwable t
          (log! log "!ERROR %s" (pr-str t))
          log)
        (finally
          (async/close! events-chan)
          (async/close! log))))))

;; =============================================================================
;; Exhaustive strategy tests - test generation
;; =============================================================================

(defn- gen-tests
  "Generate all test combinations for exhaustive testing."
  []
  (let [possible-exits [:normal :abnormal]
        exit-combinations
        (mapcat #(combinatorics/selections possible-exits %) [1])
        possible-problems
        (for [start-failure-reason [nil :abnormal]
              exit-delay (if start-failure-reason
                           [nil]
                           [nil 300])
              exit-failure-reason (if (or start-failure-reason exit-delay)
                                    [nil]
                                    [nil :normal :abnormal])]
          (cond
            start-failure-reason {:start-failure start-failure-reason}
            exit-delay {:exit-delay exit-delay}
            exit-failure-reason {:exit-failure exit-failure-reason}
            :else {}))
        problem-combinations
        (mapcat #(combinatorics/selections possible-problems %) [0 1])
        problem-combinations
        (filter #(or (empty? %) (not (every? empty? %))) problem-combinations)
        possible-children
        (for [restart-type [:permanent :transient :temporary]
              child-type [:worker :supervisor]
              shutdown (if (= child-type :supervisor)
                         [nil]
                         [:brutal-kill 250])]
          (merge {:restart restart-type
                  :type child-type}
                 (if shutdown
                   {:shutdown shutdown})))
        add-id
        (fn [children] (map #(assoc %1 :id %2) children (map inc (range))))
        children-combinations
        (mapcat #(combinatorics/selections possible-children %) [1 2 3])
        children-combinations
        (map add-id children-combinations)
        tests
        (for [intensity [0 1 2]
              period [1000]
              cc children-combinations
              problems problem-combinations
              exits exit-combinations
              problem-ids (combinatorics/selections (map :id cc) (count problems))
              exit-ids (combinatorics/selections (map :id cc) (count exits))]
          {:children cc
           :problems (map #(assoc %1 :id %2) problems problem-ids)
           :exits (map (fn [e id] {:reason e :id id}) exits exit-ids)
           :intensity intensity
           :period period})
        tests
        (map #(assoc %1 :n %2) tests (map inc (range)))
        tests
        (map (fn [t]
               (let [children (:children t)
                     ids (map :id children)
                     uids (->> ids (map #(str % "_")) (map gensym))
                     id-map (->> (map vector ids uids) (into {}))
                     update-id #(update % :id id-map)]
                 (-> t
                     (update :children #(map update-id %))
                     (update :problems #(map update-id %))
                     (update :exits #(map update-id %)))))
             tests)]
    tests))

;; =============================================================================
;; Exhaustive strategy tests - test runner
;; =============================================================================

(defn- test-strategy
  "Run all tests for a given strategy."
  [strategy tests]
  (time
    (with-open [writer (clojure.java.io/writer
                         (str "sup_test." (name strategy) ".log"))]
      (binding [*out* writer]
        ;; Run tests sequentially to avoid resource contention
        (doseq [test tests]
          (println "---")
          (let [res (<!! (run-test strategy test))
                log res]
            (loop [x (<!! log)]
              (when-let [[ms fmt args] x]
                (printf "log: %05d - %s%n" ms (apply format fmt args))
                (recur (<!! log)))))
          (flush))))))

;; =============================================================================
;; Exhaustive strategy tests - test definitions
;; =============================================================================

;; Quick sanity test - just run a few test cases to verify infrastructure works
(deftest ^:exhaustive test-one-for-one-sanity
  (mount/start)
  (try
    (let [tests (take 500 (gen-tests))]
      (println "Running" (count tests) "tests for one-for-one sanity check")
      (test-strategy :one-for-one tests))
    (finally
      (mount/stop))))

(deftest ^:exhaustive test-one-for-all-sanity
  (mount/start)
  (try
    (let [tests (take 500 (gen-tests))]
      (println "Running" (count tests) "tests for one-for-all sanity check")
      (test-strategy :one-for-all tests))
    (finally
      (mount/stop))))

(deftest ^:exhaustive test-rest-for-one-sanity
  (mount/start)
  (try
    (let [tests (take 500 (gen-tests))]
      (println "Running" (count tests) "tests for rest-for-one sanity check")
      (test-strategy :rest-for-one tests))
    (finally
      (mount/stop))))

(deftest ^:exhaustive test-one-for-one
  (mount/start)
  (try
    (let [tests (gen-tests)]
      (test-strategy :one-for-one tests))
    (finally
      (mount/stop))))

(deftest ^:exhaustive test-one-for-all
  (mount/start)
  (try
    (let [tests (gen-tests)]
      (test-strategy :one-for-all tests))
    (finally
      (mount/stop))))

(deftest ^:exhaustive test-rest-for-one
  (mount/start)
  (try
    (let [tests (gen-tests)]
      (test-strategy :rest-for-one tests))
    (finally
      (mount/stop))))
