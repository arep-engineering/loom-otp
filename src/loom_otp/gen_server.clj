(ns loom-otp.gen-server
  "Generic server behavior implementation.
   
   Provides a standard way to implement servers with synchronous calls,
   asynchronous casts, and message handling."
  (:refer-clojure :exclude [cast])
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.receive :as recv]
            [loom-otp.process.match :as match]
            [loom-otp.process.monitor :as monitor]
            [clojure.core.match :as cm]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol IGenServer
  "Protocol for gen_server callbacks."
  
  (init [this args]
    "Initialize server state.
     Returns: [:ok state] | [:ok state timeout] | [:stop reason]")
  
  (handle-call [this request from state]
    "Handle synchronous call.
     Returns: [:reply response new-state]
            | [:reply response new-state timeout]
            | [:noreply new-state]
            | [:noreply new-state timeout]
            | [:stop reason response new-state]
            | [:stop reason new-state]")
  
  (handle-cast [this request state]
    "Handle asynchronous cast.
     Returns: [:noreply new-state]
            | [:noreply new-state timeout]
            | [:stop reason new-state]")
  
  (handle-info [this message state]
    "Handle other messages.
     Returns: same as handle-cast")
  
  (terminate [this reason state]
    "Called when server is terminating. Return value ignored."))

;; Default implementations
(extend-type Object
  IGenServer
  (init [_ _args] [:ok nil])
  (handle-call [_ _request _from state] [:noreply state])
  (handle-cast [_ _request state] [:noreply state])
  (handle-info [_ _message state] [:noreply state])
  (terminate [_ _reason _state] nil))

;; =============================================================================
;; Namespace-based gen_server
;; =============================================================================

(defn- resolve-callback
  "Resolve a callback function from a namespace.
   Returns the function or nil if not found."
  [ns-sym fn-name]
  (when-let [ns (find-ns ns-sym)]
    (when-let [v (ns-resolve ns fn-name)]
      @v)))

(defn ns->gen-server
  "Create an IGenServer implementation from a namespace.
   
   The namespace should define functions matching the IGenServer protocol:
   - init [args] -> [:ok state] | [:ok state timeout] | [:stop reason]
   - handle-call [request from state] -> [:reply response new-state] | ...
   - handle-cast [request state] -> [:noreply new-state] | ...
   - handle-info [message state] -> [:noreply new-state] | ...
   - terminate [reason state] -> ignored
   
   Missing functions use default implementations.
   
   Usage:
   (ns my-server)
   (defn init [args] [:ok {:count 0}])
   (defn handle-call [req from state] [:reply (:count state) state])
   
   ;; Then start with:
   (gen-server/start (gen-server/ns->gen-server 'my-server))"
  [ns-sym]
  (let [ns-sym (if (instance? clojure.lang.Namespace ns-sym)
                 (ns-name ns-sym)
                 ns-sym)]
    (reify IGenServer
      (init [_ args]
        (if-let [f (resolve-callback ns-sym 'init)]
          (f args)
          [:ok nil]))
      
      (handle-call [_ request from state]
        (if-let [f (resolve-callback ns-sym 'handle-call)]
          (f request from state)
          [:noreply state]))
      
      (handle-cast [_ request state]
        (if-let [f (resolve-callback ns-sym 'handle-cast)]
          (f request state)
          [:noreply state]))
      
      (handle-info [_ message state]
        (if-let [f (resolve-callback ns-sym 'handle-info)]
          (f message state)
          [:noreply state]))
      
      (terminate [_ reason state]
        (when-let [f (resolve-callback ns-sym 'terminate)]
          (f reason state))))))

;; =============================================================================
;; Reply
;; =============================================================================

(defn reply
  "Send reply to a call. Used when handle-call returns :noreply.
   from is [ref caller-pid]."
  [[ref caller-pid] response]
  (core/send caller-pid [::reply ref response])
  :ok)



;; =============================================================================
;; Server Loop
;; =============================================================================

(defn- dispatch-result
  "Process callback result and continue or stop."
  [impl result from]
  (cm/match result
    [:reply response new-state]
    (do
      (when from (reply from response))
      [:continue new-state nil])
    
    [:reply response new-state timeout]
    (do
      (when from (reply from response))
      [:continue new-state timeout])
    
    [:noreply new-state]
    [:continue new-state nil]
    
    [:noreply new-state timeout]
    [:continue new-state timeout]
    
    [:stop reason response new-state]
    (do
      (when from (reply from response))
      [:stop reason new-state])
    
    [:stop reason new-state]
    [:stop reason new-state]
    
    :else
    (throw (ex-info "invalid callback result" {:result result}))))

(defn- handle-msg
  "Handle a message and return [action new-state new-timeout]."
  [impl msg state]
  (cm/match msg
    ;; Synchronous call
    [::call from request]
    (let [result (handle-call impl request from state)]
      (dispatch-result impl result from))
    
    ;; Asynchronous cast
    [::cast request]
    (let [result (handle-cast impl request state)]
      (dispatch-result impl result nil))
    
    ;; Exit signal from linked process (when trapping exits)
    [:EXIT pid reason]
    (let [result (handle-info impl [:EXIT pid reason] state)]
      (dispatch-result impl result nil))
    
    ;; Any other message -> handle-info
    other-msg
    (let [result (handle-info impl other-msg state)]
      (dispatch-result impl result nil))))

(defn- server-loop
  "Main server loop."
  [impl state timeout]
  (let [msg (recv/receive! timeout ::timeout)
        [action new-state new-timeout] (if (= msg ::timeout)
                                         (dispatch-result impl (handle-info impl :timeout state) nil)
                                         (handle-msg impl msg state))]
    (if (= action :stop)
      (do
        (terminate impl new-state state)
        (exit/exit new-state))
      (recur impl new-state new-timeout))))

(defn- server-init
  "Initialize and run server."
  [impl args]
  (cm/match (init impl args)
    [:ok state]
    (server-loop impl state nil)
    
    [:ok state timeout]
    (server-loop impl state timeout)
    
    [:stop reason]
    (exit/exit reason)
    
    :else
    (exit/exit :bad-init-return)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start
  "Start a gen_server process.
   Returns {:ok pid} or {:error reason}."
  ([impl] (start impl [] {}))
  ([impl args] (start impl args {}))
  ([impl args opts]
   (let [timeout (get opts :timeout 5000)
         name (get opts :name)
         result-promise (promise)
         
         init-wrapper (fn [impl args]
                        (let [init-result (init impl args)]
                          (deliver result-promise init-result)
                          init-result))
         
          pid (proc/spawn
               (fn []
                 (when name
                   (proc/register name))
                 (cm/match (init-wrapper impl args)
                   [:ok state] (server-loop impl state nil)
                   [:ok state timeout] (server-loop impl state timeout)
                   [:stop reason] (exit/exit reason)
                   :else (exit/exit :bad-init-return))))]
     
     ;; Wait for init to complete
      (let [init-result (deref result-promise timeout :timeout)]
        (cond
          (= init-result :timeout)
          (do
            (exit/exit pid :kill)
            {:error :timeout})
          
          (and (vector? init-result) (= :ok (first init-result)))
          {:ok pid}
          
          (and (vector? init-result) (= :stop (first init-result)))
          {:error (second init-result)}
          
          :else
          {:error :bad-init-return})))))

(defn start-link
  "Start a gen_server process linked to current process.
   Returns {:ok pid} or {:error reason}."
  ([impl] (start-link impl [] {}))
  ([impl args] (start-link impl args {}))
   ([impl args opts]
    (let [timeout (get opts :timeout 5000)
          name (get opts :name)
          result-promise (promise)
          parent-pid (core/self)
          
          pid (proc/spawn-link
               (fn []
                 (when name
                   (proc/register name))
                 (let [init-result (init impl args)]
                   (deliver result-promise init-result)
                   (cm/match init-result
                     [:ok state] (server-loop impl state nil)
                     [:ok state timeout] (server-loop impl state timeout)
                     [:stop reason] (exit/exit reason)
                     :else (exit/exit :bad-init-return)))))]
     
     ;; Wait for init to complete
      (let [init-result (deref result-promise timeout :timeout)]
        (cond
          (= init-result :timeout)
          (do
            (exit/exit pid :kill)
            {:error :timeout})
          
          (and (vector? init-result) (= :ok (first init-result)))
          {:ok pid}
          
          (and (vector? init-result) (= :stop (first init-result)))
          {:error (second init-result)}
          
          :else
          {:error :bad-init-return})))))

(defn call
  "Make synchronous call to server. Returns response or throws on timeout/error."
  ([server request] (call server request 5000))
  ([server request timeout-ms]
   (let [ref (monitor/monitor server)
         self-pid (core/self)]
     (core/send server [::call [ref self-pid] request])
     
     ;; Wait for reply or DOWN using selective receive
     (match/selective-receive!
       [::reply r response] (when (= r ref)
                              (monitor/demonitor ref)
                              response)
       [:DOWN r :process _ reason] (when (= r ref)
                                     (monitor/demonitor ref)
                                     (throw (ex-info "server died" {:type :exit
                                                                    :reason [reason [server request]]})))
       (after timeout-ms
         (monitor/demonitor ref)
         (throw (ex-info "call timeout" {:type :exit
                                         :reason [:timeout [server request]]})))))))

(defn cast
  "Send asynchronous request to server. Returns :ok."
  [server request]
  (core/send server [::cast request])
  :ok)

(defn stop
  "Stop the server."
  ([server] (stop server :normal))
  ([server reason] (stop server reason 5000))
  ([server reason timeout]
   (let [ref (monitor/monitor server)]
     (exit/exit server reason)
     ;; Wait for DOWN using selective receive
     (match/selective-receive!
       [:DOWN r :process _ _] (when (= r ref)
                                (monitor/demonitor ref)
                                :ok)
       (after timeout
         (monitor/demonitor ref)
         (exit/exit server :kill)
         :ok)))))
