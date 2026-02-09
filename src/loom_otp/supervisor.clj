(ns loom-otp.supervisor
  "Supervisor behavior for managing child processes.
   
   Implements supervision trees with restart strategies."
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.match :as match]
            [clojure.core.match :as cm]))

;; =============================================================================
;; Child Spec
;; =============================================================================

;; Child spec format:
;; {:id       any              ; unique identifier
;;  :start    [f args]         ; function and args to start child
;;  :restart  :permanent       ; :permanent | :transient | :temporary
;;  :shutdown timeout          ; :brutal-kill | timeout-ms | :infinity
;;  :type     :worker}         ; :worker | :supervisor

(defn- normalize-child-spec
  "Fill in defaults for child spec."
  [spec]
  (merge {:restart :permanent
          :shutdown 5000
          :type :worker}
         spec))

(defn- validate-child-spec
  "Validate child spec. Returns {:ok spec} or {:error reason}."
  [spec]
  (let [spec (normalize-child-spec spec)]  ;; Apply defaults first
    (cond
      (not (:id spec))
      {:error {:reason :missing-id :spec spec}}
      
      (not (:start spec))
      {:error {:reason :missing-start :spec spec}}
      
      (not (vector? (:start spec)))
      {:error {:reason :invalid-start :spec spec}}
      
      (not (#{:permanent :transient :temporary} (:restart spec)))
      {:error {:reason :invalid-restart :spec spec}}
      
      (not (or (= :brutal-kill (:shutdown spec))
               (= :infinity (:shutdown spec))
               (and (number? (:shutdown spec)) (pos? (:shutdown spec)))))
      {:error {:reason :invalid-shutdown :spec spec}}
      
      (not (#{:worker :supervisor} (:type spec)))
      {:error {:reason :invalid-type :spec spec}}
      
      :else
      {:ok spec})))

(defn check-child-specs
  "Validate a list of child specs."
  [specs]
  (let [results (map validate-child-spec specs)
        errors (filter :error results)]
    (if (seq errors)
      {:error (map :error errors)}
      {:ok (map :ok results)})))

;; =============================================================================
;; Supervisor Flags
;; =============================================================================

;; Supervisor flags:
;; {:strategy  :one-for-one    ; :one-for-one | :one-for-all | :rest-for-one
;;  :intensity 1               ; max restarts
;;  :period    5000}           ; in period ms

(defn- normalize-sup-flags
  [flags]
  (merge {:strategy :one-for-one
          :intensity 1
          :period 5000}
         flags))

;; =============================================================================
;; Restart Logic
;; =============================================================================

(defn- should-restart?
  "Determine if child should be restarted based on policy and reason."
  [restart-policy reason]
  (case restart-policy
    :permanent true
    :temporary false
    :transient (not (or (= reason :normal)
                        (= reason :shutdown)
                        (and (vector? reason) (= :shutdown (first reason)))))))

(defn- check-restart-intensity
  "Check if restart intensity limit is exceeded.
   Returns {:ok new-restarts} or {:error :max-intensity}."
  [restarts intensity period]
  (let [now (System/currentTimeMillis)
        cutoff (- now period)
        recent (filter #(> % cutoff) restarts)
        new-restarts (cons now recent)]
    (if (> (count new-restarts) intensity)
      {:error :max-intensity}
      {:ok new-restarts})))

;; =============================================================================
;; Child Management
;; =============================================================================

(defn- start-child-process
  "Start a child process. Returns {:ok pid} or {:error reason}."
  [{:keys [start] :as spec}]
  (let [[f & args] start]
    (try
      (let [pid (apply proc/spawn-link f args)]
        {:ok pid})
      (catch Exception e
        {:error {:exception (Throwable->map e)}}))))

(defn- stop-child-process
  "Stop a child process."
  [pid shutdown]
  (when (and pid (proc/alive? pid))
    (case shutdown
      :brutal-kill
      (exit/exit pid :kill)
      
      :infinity
      (do
        (exit/exit pid :shutdown)
        ;; Wait indefinitely
        (loop []
          (when (proc/alive? pid)
            (Thread/sleep 100)
            (recur))))
      
      ;; Timeout
      (do
        (exit/exit pid :shutdown)
        (let [deadline (+ (System/currentTimeMillis) shutdown)]
          (loop []
            (when (proc/alive? pid)
              (if (> (System/currentTimeMillis) deadline)
                (exit/exit pid :kill)
                (do
                  (Thread/sleep 50)
                  (recur))))))))))

;; =============================================================================
;; Supervisor State
;; =============================================================================

;; Internal state:
;; {:flags      sup-flags
;;  :children   [{:spec spec :pid pid} ...]  ; ordered list
;;  :restarts   [timestamp ...]              ; recent restart times
;; }

(defn- init-children
  "Start all children in order. Returns {:ok children} or {:error reason}."
  [specs]
  (loop [specs specs
         started []]
    (if (empty? specs)
      {:ok started}
      (let [spec (first specs)
            result (start-child-process spec)]
        (if (:ok result)
          (recur (rest specs)
                 (conj started {:spec spec :pid (:ok result)}))
          ;; Failed - stop already started children in reverse order
          (do
            (doseq [{:keys [pid spec]} (reverse started)]
              (stop-child-process pid (:shutdown spec)))
            {:error {:failed-child (:id spec) :reason (:error result)}}))))))

(defn- find-child-by-pid
  "Find child by pid in children list."
  [children pid]
  (first (filter #(= pid (:pid %)) children)))

(defn- find-child-by-id
  "Find child by id in children list."
  [children id]
  (first (filter #(= id (get-in % [:spec :id])) children)))

(defn- child-index
  "Get index of child in list."
  [children pid]
  (first (keep-indexed (fn [i c] (when (= pid (:pid c)) i)) children)))

;; =============================================================================
;; Restart Strategies
;; =============================================================================

(defn- restart-one-for-one
  "Restart only the failed child."
  [state pid reason]
  (let [children (:children state)
        child (find-child-by-pid children pid)]
    (if-not child
      {:ok state}  ; Unknown child, ignore
      (let [spec (:spec child)]
        (if (should-restart? (:restart spec) reason)
          ;; Check intensity
          (let [intensity-check (check-restart-intensity 
                                 (:restarts state)
                                 (get-in state [:flags :intensity])
                                 (get-in state [:flags :period]))]
            (if (:error intensity-check)
              {:stop :max-intensity}
              ;; Restart child
              (let [result (start-child-process spec)]
                (if (:ok result)
                  {:ok (-> state
                           (assoc :restarts (:ok intensity-check))
                           (assoc :children
                                  (mapv (fn [c]
                                          (if (= pid (:pid c))
                                            {:spec spec :pid (:ok result)}
                                            c))
                                        children)))}
                  {:stop {:child-start-failed (:id spec)}}))))
          ;; Don't restart - just remove from children
          {:ok (assoc state :children
                      (vec (remove #(= pid (:pid %)) children)))})))))

(defn- restart-one-for-all
  "Restart all children when one fails."
  [state pid reason]
  (let [children (:children state)
        child (find-child-by-pid children pid)]
    (if-not child
      {:ok state}
      (let [spec (:spec child)]
        (if (should-restart? (:restart spec) reason)
          ;; Check intensity
          (let [intensity-check (check-restart-intensity
                                 (:restarts state)
                                 (get-in state [:flags :intensity])
                                 (get-in state [:flags :period]))]
            (if (:error intensity-check)
              {:stop :max-intensity}
              ;; Stop all children (reverse order)
              (do
                (doseq [{:keys [pid spec]} (reverse children)]
                  (stop-child-process pid (:shutdown spec)))
                ;; Restart all children
                (let [specs (map :spec children)
                      result (init-children specs)]
                  (if (:ok result)
                    {:ok (-> state
                             (assoc :restarts (:ok intensity-check))
                             (assoc :children (:ok result)))}
                    {:stop {:restart-failed (:error result)}})))))
          ;; Don't restart - remove child
          {:ok (assoc state :children
                      (vec (remove #(= pid (:pid %)) children)))})))))

(defn- restart-rest-for-one
  "Restart failed child and all children started after it."
  [state pid reason]
  (let [children (:children state)
        child (find-child-by-pid children pid)]
    (if-not child
      {:ok state}
      (let [spec (:spec child)
            idx (child-index children pid)]
        (if (should-restart? (:restart spec) reason)
          ;; Check intensity
          (let [intensity-check (check-restart-intensity
                                 (:restarts state)
                                 (get-in state [:flags :intensity])
                                 (get-in state [:flags :period]))]
            (if (:error intensity-check)
              {:stop :max-intensity}
              ;; Stop children from idx onwards (reverse order)
              (let [to-restart (subvec (vec children) idx)
                    to-keep (subvec (vec children) 0 idx)]
                (doseq [{:keys [pid spec]} (reverse to-restart)]
                  (stop-child-process pid (:shutdown spec)))
                ;; Restart children
                (let [specs (map :spec to-restart)
                      result (init-children specs)]
                  (if (:ok result)
                    {:ok (-> state
                             (assoc :restarts (:ok intensity-check))
                             (assoc :children (vec (concat to-keep (:ok result)))))}
                    {:stop {:restart-failed (:error result)}})))))
          ;; Don't restart - remove child
          {:ok (assoc state :children
                      (vec (remove #(= pid (:pid %)) children)))})))))

(defn- handle-child-exit
  "Handle child process exit."
  [state pid reason]
  (case (get-in state [:flags :strategy])
    :one-for-one (restart-one-for-one state pid reason)
    :one-for-all (restart-one-for-all state pid reason)
    :rest-for-one (restart-rest-for-one state pid reason)))

;; =============================================================================
;; Supervisor Loop
;; =============================================================================

(defn- supervisor-loop
  "Main supervisor loop."
  [state]
  (match/receive!
    [:EXIT pid reason]
    (let [result (handle-child-exit state pid reason)]
      (cm/match result
        {:ok new-state} (recur new-state)
        {:stop reason} (do
                         ;; Stop all remaining children
                         (doseq [{:keys [pid spec]} (reverse (:children state))]
                           (stop-child-process pid (:shutdown spec)))
                         (exit/exit reason))))
    
    [::which-children from]
    (do
      (core/send from [::children-reply
                    (mapv (fn [{:keys [spec pid]}]
                            {:id (:id spec)
                             :pid pid
                             :type (:type spec)
                             :restart (:restart spec)})
                          (:children state))])
      (recur state))
    
    [::start-child from spec]
    (let [validated (validate-child-spec spec)]
      (if (:error validated)
        (do
          (core/send from [::start-child-reply {:error (:error validated)}])
          (recur state))
        (let [result (start-child-process (:ok validated))]
          (if (:ok result)
            (do
              (core/send from [::start-child-reply {:ok (:ok result)}])
              (recur (update state :children conj {:spec (:ok validated) :pid (:ok result)})))
            (do
              (core/send from [::start-child-reply {:error (:error result)}])
              (recur state))))))
    
    [::terminate-child from id]
    (let [child (find-child-by-id (:children state) id)]
      (if child
        (do
          (stop-child-process (:pid child) (get-in child [:spec :shutdown]))
          (core/send from [::terminate-child-reply :ok])
          (recur (assoc state :children
                        (vec (remove #(= id (get-in % [:spec :id])) (:children state))))))
        (do
          (core/send from [::terminate-child-reply {:error :not-found}])
          (recur state))))
    
    [::delete-child from id]
    (let [child (find-child-by-id (:children state) id)]
      (cond
        (not child)
        (do
          (core/send from [::delete-child-reply {:error :not-found}])
          (recur state))
        
        (proc/alive? (:pid child))
        (do
          (core/send from [::delete-child-reply {:error :running}])
          (recur state))
        
        :else
        (do
          (core/send from [::delete-child-reply :ok])
          (recur (assoc state :children
                        (vec (remove #(= id (get-in % [:spec :id])) (:children state))))))))
    
    other
    ;; Ignore unknown messages
    (recur state)))

(defn- supervisor-init
  "Initialize supervisor."
  [sup-flags child-specs init-promise]
  (let [flags (normalize-sup-flags sup-flags)
        validated (check-child-specs child-specs)]
    (if (:error validated)
      (do
        (deliver init-promise {:error (:error validated)})
        (exit/exit :bad-child-specs))
      (let [result (init-children (:ok validated))]
        (if (:ok result)
          (do
            (deliver init-promise {:ok (core/self)})
            (supervisor-loop {:flags flags
                              :children (:ok result)
                              :restarts []}))
          (do
            (deliver init-promise {:error (:error result)})
            (exit/exit :start-children-failed)))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start-link
  "Start a supervisor linked to current process.
   
   sup-flags: {:strategy :one-for-one, :intensity 1, :period 5000}
   child-specs: [{:id :child1, :start [f args], :restart :permanent, ...} ...]
   
   Returns {:ok pid} or {:error reason}."
  ([sup-flags child-specs] (start-link sup-flags child-specs {}))
  ([sup-flags child-specs opts]
   (let [timeout (get opts :timeout 5000)
         init-promise (promise)
         pid (proc/spawn-opt!
              {:link true :trap-exit true :reg-name (get opts :name)}
              (supervisor-init sup-flags child-specs init-promise))]
     
     (let [result (deref init-promise timeout :timeout)]
        (cond
          (= result :timeout)
          (do
            (exit/exit pid :kill)
            {:error :timeout})
          
          (:ok result)
          {:ok pid}
          
          :else
          {:error (:error result)})))))

(defn which-children
  "Get list of children managed by supervisor."
  [sup]
  (let [self-pid (core/self)]
    (core/send sup [::which-children self-pid])
    (match/receive!
      [::children-reply children] children
      (after 5000 (throw (ex-info "timeout getting children" {:sup sup}))))))

(defn start-child
  "Dynamically start a child process."
  [sup child-spec]
  (let [self-pid (core/self)]
    (core/send sup [::start-child self-pid child-spec])
    (match/receive!
      [::start-child-reply result] result
      (after 5000 {:error :timeout}))))

(defn terminate-child
  "Terminate a child process by id."
  [sup child-id]
  (let [self-pid (core/self)]
    (core/send sup [::terminate-child self-pid child-id])
    (match/receive!
      [::terminate-child-reply result] result
      (after 5000 {:error :timeout}))))

(defn delete-child
  "Delete a terminated child spec."
  [sup child-id]
  (let [self-pid (core/self)]
    (core/send sup [::delete-child self-pid child-id])
    (match/receive!
      [::delete-child-reply result] result
      (after 5000 {:error :timeout}))))

(defn count-children
  "Count children by status."
  [sup]
  (let [children (which-children sup)]
    {:specs (count children)
     :active (count (filter #(proc/alive? (:pid %)) children))
     :supervisors (count (filter #(= :supervisor (:type %)) children))
     :workers (count (filter #(= :worker (:type %)) children))}))
