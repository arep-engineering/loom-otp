(ns loom-otp.otplike.supervisor
  "Compatibility shim providing otplike.supervisor API backed by loom-otp.
   
   This wraps the gen-server-based supervisor implementation to return
   [:ok pid] tuples instead of {:ok pid} maps."
  (:require [loom-otp.otplike.process :as process]
            [loom-otp.otplike.gen-server :as gen-server]
            [clojure.core.match :refer [match]]))

;; =============================================================================
;; Supervisor flags validation - same as otplike
;; =============================================================================

(def ^:private valid-strategies #{:one-for-one :one-for-all :rest-for-one})

(defn- check-sup-flags
  "Validate supervisor flags. Returns :ok or [:error [:bad-supervisor-flags reason]]."
  [{:keys [strategy intensity period]}]
  (let [problems
        (cond-> []
          (and strategy (not (valid-strategies strategy)))
          (conj {:path [:strategy] :val strategy :expected valid-strategies})
          
          (and intensity (not (and (integer? intensity) (>= intensity 0))))
          (conj {:path [:intensity] :val intensity :expected "non-negative integer"})
          
          (and period (not (and (integer? period) (pos? period))))
          (conj {:path [:period] :val period :expected "positive integer"}))]
    (if (seq problems)
      [:error [:bad-supervisor-flags [:problems problems]]]
      :ok)))

;; =============================================================================
;; Child spec validation - same as otplike
;; =============================================================================

(defn- check-child-spec [spec]
  (cond
    (not (map? spec)) [:error [:bad-child-spec [:not-a-map spec]]]
    (not (:id spec)) [:error [:bad-child-spec [:missing-id spec]]]
    (not (:start spec)) [:error [:bad-child-spec [:missing-start spec]]]
    (not (vector? (:start spec))) [:error [:bad-child-spec [:invalid-start spec]]]
    (not (fn? (first (:start spec)))) [:error [:bad-child-spec [:start-not-fn spec]]]
    :else :ok))

(defn check-child-specs
  "Validate a list of child specifications."
  [specs]
  (if-not (sequential? specs)
    [:error [:bad-child-specs [:not-sequential specs]]]
    (let [errors (keep #(let [r (check-child-spec %)]
                          (when (not= :ok r) r))
                       specs)]
      (if (seq errors)
        ;; Convert individual child-spec errors to child-specs (plural) wrapper
        (let [[_ [_ details]] (first errors)]
          [:error [:bad-child-specs details]])
        ;; Check for duplicate IDs
        (let [ids (map :id specs)
              dups (filter #(> (val %) 1) (frequencies ids))]
          (if (seq dups)
            [:error [:bad-child-specs [:duplicate-child-id (ffirst dups)]]]
            :ok))))))

;; =============================================================================
;; Child spec normalization
;; =============================================================================

(defn- spec->child [{:keys [id start restart shutdown type]}]
  {::id id
   ::start start
   ::restart (or restart :permanent)
   ::shutdown (or shutdown (if (= :supervisor type) :infinity 5000))
   ::type (or type :worker)
   ::pid nil})

(defn- sup-flags [{:keys [strategy intensity period]}]
  {::strategy (or strategy :one-for-one)
   ::intensity (or intensity 1)
   ::period (or period 5000)})

;; =============================================================================
;; Child lifecycle
;; =============================================================================

(defn- dispatch-child-start [child res]
  (match res
    [:ok (pid :guard process/pid?)]
    [:ok (assoc child ::pid pid)]

    [:ok (pid :guard process/pid?) _info]
    [:ok (assoc child ::pid pid)]

    [:error reason]
    [:error reason]

    [:ok other]
    [:error [:bad-return other]]))

(defn- start-child* [{[f args] ::start :as child}]
  (match (process/ex-catch [:ok (apply f args)])
    [:ok (async :guard process/async?)]
    (process/map-async #(dispatch-child-start child %) async)

    [:ok res]
    (process/async-value (dispatch-child-start child res))

    [:EXIT reason]
    (process/async-value [:error reason])))

(defn- shutdown-child [{pid ::pid shutdown ::shutdown :as child} reason]
  (process/async
    (when (and pid (process/pid? pid) (process/alive? pid))
      (process/exit pid (if (= shutdown :brutal-kill) :kill reason))
      (if (= shutdown :brutal-kill)
        (process/selective-receive!
          [:EXIT pid _] :ok)
        (process/selective-receive!
          [:EXIT pid _] :ok
          (after (if (= shutdown :infinity) 300000 shutdown)
            (process/exit pid :kill)
            (process/selective-receive!
              [:EXIT pid _] :ok)))))
    (assoc child ::pid nil)))

(defn- terminate-children [children]
  (process/async
    (loop [children children
           result []]
      (if (empty? children)
        result
        (let [child (first children)
              new-child (process/await! (shutdown-child child :shutdown))]
          (recur (rest children)
                 (if (= :temporary (::restart child))
                   result
                   (cons new-child result))))))))

(defn- start-children [children]
  (process/async
    (loop [to-start children
           started []]
      (if (empty? to-start)
        [:ok started]
        (let [child (first to-start)]
          (match (process/await! (start-child* child))
            [:ok started-child]
            (recur (rest to-start) (cons started-child started))

            [:error reason]
            [:error [:failed-to-start-child (::id child) reason]
             child (concat (reverse (rest to-start)) (cons child started))]))))))

;; =============================================================================
;; Restart logic
;; =============================================================================

(defn- monotonic-time-ms []
  (quot (System/nanoTime) 1000000))

(defn- add-restart [{intensity ::intensity period ::period restarts ::restarts :as state}]
  (let [time-ms (monotonic-time-ms)
        restarts (take-while #(>= period (- time-ms %))
                             (cons time-ms restarts))
        state (assoc state ::restarts restarts)]
    (if (> (count restarts) intensity)
      [:shutdown state]
      [:continue state])))

(defn- child-by-pid [children pid]
  (first (filter #(= pid (::pid %)) children)))

(defn- child-by-id [children id]
  (first (filter #(= id (::id %)) children)))

(defn- replace-child [children {id ::id :as child}]
  (map #(if (= id (::id %)) child %) children))

(defn- delete-child-by-id [children id]
  (remove #(= id (::id %)) children))

;; =============================================================================
;; Restart strategies
;; =============================================================================

(defn- restart-child--one-for-one [child state]
  (process/with-async [res (start-child* child)]
    (match res
      [:ok new-child]
      (update state ::children replace-child new-child)

      [:error _reason]
      (do
        (gen-server/cast (process/self) [:restart (::id child)])
        state))))

(defn- restart-child--one-for-all [child {children ::children :as state}]
  (process/async
    (let [terminated (process/await! (terminate-children children))]
      (match (process/await! (start-children terminated))
        [:ok started]
        (assoc state ::children started)

        [:error _reason failed-child new-children]
        (do
          (gen-server/cast (process/self) [:restart (::id failed-child)])
          (assoc state ::children (replace-child new-children (assoc failed-child ::pid :restarting))))))))

(defn- split-children-after [id children]
  (let [[before after-with-child] (split-with #(not= id (::id %)) children)]
    [(concat before [(first after-with-child)]) (rest after-with-child)]))

(defn- restart-child--rest-for-one [child {children ::children :as state}]
  (process/async
    (let [[after before] (split-children-after (::id child) children)
          terminated (process/await! (terminate-children after))]
      (match (process/await! (start-children terminated))
        [:ok started]
        (assoc state ::children (concat started before))

        [:error _reason failed-child new-children]
        (do
          (gen-server/cast (process/self) [:restart (::id failed-child)])
          (assoc state ::children (concat (replace-child new-children (assoc failed-child ::pid :restarting)) before)))))))

(defn- restart-child* [child {strategy ::strategy :as state}]
  (let [child (assoc child ::pid :restarting)
        state (update state ::children replace-child child)]
    (match (add-restart state)
      [:continue new-state]
      (process/with-async
        [res (case strategy
               :one-for-one (restart-child--one-for-one child new-state)
               :one-for-all (restart-child--one-for-all child new-state)
               :rest-for-one (restart-child--rest-for-one child new-state))]
        [:ok res])

      [:shutdown new-state]
      (process/async-value
        [:shutdown (update new-state ::children delete-child-by-id (::id child))]))))

(defn- handle-child-exit [{restart ::restart id ::id :as child} reason state]
  (match [restart reason]
    [:permanent _]
    (restart-child* child state)

    [_ (:or :normal :shutdown)]
    (process/async-value
      [:ok (update state ::children delete-child-by-id id)])

    [:transient _]
    (restart-child* child state)

    [:temporary _]
    (process/async-value
      [:ok (update state ::children delete-child-by-id id)])))

(defn- handle-exit [pid reason {children ::children :as state}]
  (if-let [child (child-by-pid children pid)]
    (handle-child-exit child reason state)
    (process/async-value [:ok state])))

;; =============================================================================
;; gen-server callbacks
;; =============================================================================

(defn- init [sup-fn args]
  (process/async
    (match (process/await?! (apply sup-fn args))
      [:ok [sup-spec child-specs]]
      (do
        ;; Validate supervisor flags first
        (match (check-sup-flags sup-spec)
          :ok :ok
          [:error reason] (process/exit reason))
        ;; Then validate child specs
        (match (check-child-specs child-specs)
          :ok :ok
          [:error reason] (process/exit reason))
        (let [flags (sup-flags sup-spec)
              children (map spec->child child-specs)]
          (match (process/await! (start-children children))
            [:ok started]
            [:ok (merge flags {::children started ::restarts []})]

            [:error reason _child new-children]
            (do
              (process/await! (terminate-children new-children))
              [:stop [:shutdown reason]]))))

      other
      [:stop [:bad-return other]])))

(defn- handle-call [request _from {children ::children :as state}]
  (match request
    [:start-child child-spec]
    (process/async
      (match (check-child-spec child-spec)
        :ok
        (if-let [existing (child-by-id children (:id child-spec))]
          (if (process/pid? (::pid existing))
            [[:error [:already-started (::pid existing)]] state]
            [[:error :already-present] state])
          (let [child (spec->child child-spec)]
            (match (process/await! (start-child* child))
              [:ok started]
              [[:ok (::pid started)] (update state ::children #(cons started %))]
              [:error reason]
              [[:error reason] state])))

        error
        [error state]))

    [:restart-child id]
    (process/async
      (if-let [child (child-by-id children id)]
        (cond
          (nil? (::pid child))
          (match (process/await! (start-child* child))
            [:ok started]
            [[:ok (::pid started)] (update state ::children replace-child started)]
            [:error reason]
            [[:error reason] state])

          (= :restarting (::pid child))
          [[:error :restarting] state]

          :else
          [[:error :running] state])
        [[:error :not-found] state]))

    [:terminate-child id]
    (process/async
      (if-let [child (child-by-id children id)]
        (do
          (process/await! (shutdown-child child :shutdown))
          (if (= :temporary (::restart child))
            [:ok (update state ::children delete-child-by-id id)]
            [:ok (update state ::children replace-child (assoc child ::pid nil))]))
        [[:error :not-found] state]))

    [:delete-child id]
    (if-let [child (child-by-id children id)]
      (cond
        (nil? (::pid child))
        (process/async-value [:ok (update state ::children delete-child-by-id id)])
        (= :restarting (::pid child))
        (process/async-value [[:error :restarting] state])
        :else
        (process/async-value [[:error :running] state]))
      (process/async-value [[:error :not-found] state]))))

(defn- handle-cast [request state]
  (match request
    [:restart id]
    (if-let [child (child-by-id (::children state) id)]
      (when (= :restarting (::pid child))
        (process/with-async [res (restart-child* child state)]
          (match res
            [:ok new-state] [:noreply new-state]
            [:shutdown new-state] [:stop :shutdown new-state])))
      (process/async-value [:noreply state]))))

(defn- handle-info [request state]
  (match request
    [:EXIT pid reason]
    (process/with-async [res (handle-exit pid reason state)]
      (match res
        [:ok new-state] [:noreply new-state]
        [:shutdown new-state] [:stop :shutdown new-state]))

    _
    (process/async-value [:noreply state])))

(defn- terminate [_reason {children ::children}]
  (terminate-children children))

;; =============================================================================
;; API
;; =============================================================================

(defn start-link
  "Start supervisor. Returns async value."
  ([sup-fn]
   (start-link sup-fn []))
  ([sup-fn args]
   (when-not (fn? sup-fn)
     (throw (IllegalArgumentException. (str "sup-fn must be a function, got: " (type sup-fn)))))
   (gen-server/start-link
     {:init (fn [& _] (init sup-fn args))
      :handle-call (fn [req from state]
                     (process/with-async [[res new-state] (handle-call req from state)]
                       [:reply res new-state]))
      :handle-cast handle-cast
      :handle-info handle-info
      :terminate terminate}
     []
     {:spawn-opt {:flags {:trap-exit true}}}))
  ([sup-name sup-fn args]
   (when-not (fn? sup-fn)
     (throw (IllegalArgumentException. (str "sup-fn must be a function, got: " (type sup-fn)))))
   (gen-server/start-link
     {:init (fn [& _] (init sup-fn args))
      :handle-call (fn [req from state]
                     (process/with-async [[res new-state] (handle-call req from state)]
                       [:reply res new-state]))
      :handle-cast handle-cast
      :handle-info handle-info
      :terminate terminate}
     []
     {:spawn-opt {:flags {:trap-exit true} :register sup-name}})))

(defmacro start-link!
  "Start supervisor. Returns [:ok pid] or [:error reason]."
  ([sup-fn]
   `(start-link! ~sup-fn []))
  ([sup-fn args]
   `(process/await! (start-link ~sup-fn ~args)))
  ([sup-name sup-fn args]
   `(process/await! (start-link ~sup-name ~sup-fn ~args))))

(defmacro start-child!
  "Dynamically add a child. Returns [:ok pid] or [:error reason]."
  [sup child-spec]
  `(gen-server/call! ~sup [:start-child ~child-spec]))

(defn start-child
  "Dynamically add a child. Returns async value."
  [sup child-spec]
  (gen-server/call sup [:start-child child-spec]))

(defmacro restart-child!
  "Restart a stopped child. Returns [:ok pid] or [:error reason]."
  [sup id]
  `(gen-server/call! ~sup [:restart-child ~id]))

(defn restart-child
  "Restart a stopped child. Returns async value."
  [sup id]
  (gen-server/call sup [:restart-child id]))

(defmacro terminate-child!
  "Terminate a child. Returns :ok or [:error :not-found]."
  [sup id]
  `(gen-server/call! ~sup [:terminate-child ~id]))

(defn terminate-child
  "Terminate a child. Returns async value."
  [sup id]
  (gen-server/call sup [:terminate-child id]))

(defmacro delete-child!
  "Delete a stopped child spec. Returns :ok or [:error reason]."
  [sup id]
  `(gen-server/call! ~sup [:delete-child ~id]))

(defn delete-child
  "Delete a stopped child spec. Returns async value."
  [sup id]
  (gen-server/call sup [:delete-child id]))
