(ns loom-otp.otplike.gen-server
  "Compatibility shim providing otplike.gen-server API backed by loom-otp.
   
   Key differences handled:
   - Returns [:ok pid] tuples instead of {:ok pid} maps
   - Uses otplike-style process/! for messaging
   - Integrates with loom-otp.otplike.process async/await"
  (:refer-clojure :exclude [cast get])
  (:require [loom-otp.process :as proc]
            [loom-otp.process.core :as core]
            [loom-otp.process.exit :as exit]
            [loom-otp.process.match :as match]
            [loom-otp.process.monitor :as monitor]
            [loom-otp.registry :as reg]
            [loom-otp.otplike.process :as otp-proc]
            [clojure.core.match :as cm]))

;; =============================================================================
;; Protocol - same as otplike
;; =============================================================================

(defprotocol IGenServer
  (init [_ args]
    "Initialize server state.
     Returns: [:ok state] | [:ok state timeout] | [:stop reason]")
  
  (handle-call [_ request from state]
    "Handle synchronous call.
     Returns: [:reply response new-state]
            | [:reply response new-state timeout]
            | [:noreply new-state]
            | [:noreply new-state timeout]
            | [:stop reason response new-state]
            | [:stop reason new-state]")
  
  (handle-cast [_ request state]
    "Handle asynchronous cast.
     Returns: [:noreply new-state]
            | [:noreply new-state timeout]
            | [:stop reason new-state]")
  
  (handle-info [_ request state]
    "Handle other messages.
     Returns: same as handle-cast")
  
  (terminate [_ reason state]
    "Called when server is terminating."))

;; =============================================================================
;; Namespace-based gen_server (same as otplike)
;; =============================================================================

(defn- ns-function [fun-ns fun-name]
  (if-let [fun-var (ns-resolve fun-ns fun-name)]
    (var-get fun-var)))

(defn- call-init [init args]
  (if init
    (apply init args)
    (otp-proc/exit [:undef ['init [args]]])))

(defn- call-handle-call [handle-call request from state]
  (if handle-call
    (handle-call request from state)
    (otp-proc/exit [:undef ['handle-call [request from state]]])))

(defn- call-handle-cast [handle-cast request state]
  (if handle-cast
    (handle-cast request state)
    (otp-proc/exit [:undef ['handle-cast [request state]]])))

(defn- call-handle-info [handle-info request state]
  (if handle-info
    (handle-info request state)
    (otp-proc/exit [:undef ['handle-info [request state]]])))

(defn- call-terminate [terminate reason state]
  (when terminate
    (terminate reason state)))

(defn- coerce-map
  [{:keys [init handle-call handle-cast handle-info terminate]}]
  (reify IGenServer
    (init [_ args]
      (call-init init args))
    (handle-cast [_ request state]
      (call-handle-cast handle-cast request state))
    (handle-call [_ request from state]
      (call-handle-call handle-call request from state))
    (handle-info [_ request state]
      (call-handle-info handle-info request state))
    (terminate [_ reason state]
      (call-terminate terminate reason state))))

(defn- coerce-ns-dynamic [impl-ns]
  (reify IGenServer
    (init [_ args]
      (call-init (ns-function impl-ns 'init) args))
    (handle-cast [_ request state]
      (call-handle-cast (ns-function impl-ns 'handle-cast) request state))
    (handle-call [_ request from state]
      (call-handle-call (ns-function impl-ns 'handle-call) request from state))
    (handle-info [_ request state]
      (call-handle-info (ns-function impl-ns 'handle-info) request state))
    (terminate [_ reason state]
      (call-terminate (ns-function impl-ns 'terminate) reason state))))

(defn- ->gen-server [server-impl]
  (cond
    (satisfies? IGenServer server-impl) server-impl
    (map? server-impl) (coerce-map server-impl)
    (instance? clojure.lang.Namespace server-impl) (coerce-ns-dynamic server-impl)
    :else (throw (ex-info "Invalid gen-server impl" {:impl server-impl}))))

;; =============================================================================
;; Reply
;; =============================================================================

(defn reply
  "Send reply to a call."
  [[mref pid] response]
  (otp-proc/! pid [mref response]))

;; =============================================================================
;; Internal message types
;; =============================================================================

(def ^:private call-tag ::call)
(def ^:private cast-tag ::cast)
(def ^:private get-state-tag ::get-state)

;; =============================================================================
;; Server loop
;;
;; Internal functions execute synchronously without async wrapping.
;; User callback boundaries use await?! to handle cases where user code
;; might return async values.
;; =============================================================================

(defn- do-terminate [impl reason state]
  (cm/match (otp-proc/ex-catch
              [:ok (otp-proc/await?! (terminate impl reason state))])
    [:ok _] [:terminate reason state]
    [:EXIT exit-reason] [:terminate exit-reason state]))

(defn- cast-or-info [rqtype impl message state]
  (let [[rqfn rqtype-name] (case rqtype
                             ::cast [handle-cast 'handle-cast]
                             ::info [handle-info 'handle-info])]
    (cm/match (otp-proc/ex-catch
                [:ok (otp-proc/await?! (rqfn impl message state))])
      [:ok [:noreply new-state]]
      [:recur new-state :infinity]

      [:ok [:noreply new-state timeout]]
      [:recur new-state timeout]

      [:ok [:stop reason new-state]]
      (do-terminate impl reason new-state)

      [:ok other]
      (do-terminate impl [:bad-return-value rqtype-name other] state)

      [:EXIT reason]
      (do-terminate impl reason state))))

(defn- do-handle-call [impl from request state]
  (cm/match (otp-proc/ex-catch
              [:ok (otp-proc/await?! (handle-call impl request from state))])
    [:ok [:reply response new-state]]
    (do
      (reply from response)
      [:recur new-state :infinity])

    [:ok [:reply response new-state timeout]]
    (do
      (reply from response)
      [:recur new-state timeout])

    [:ok [:noreply new-state]]
    [:recur new-state :infinity]

    [:ok [:noreply new-state timeout]]
    [:recur new-state timeout]

    [:ok [:stop reason response new-state]]
    (let [ret (do-terminate impl reason new-state)]
      (reply from response)
      ret)

    [:ok [:stop reason new-state]]
    (do-terminate impl reason new-state)

    [:ok other]
    (do-terminate impl [:bad-return-value 'handle-call other] state)

    [:EXIT reason]
    (do-terminate impl reason state)))

(defn- dispatch [impl parent state message]
  (cm/match message
    [::call from ::get-state]
    (do
      (reply from state)
      :recur)

    [::call from request]
    (do-handle-call impl from request state)

    [::cast request]
    (cast-or-info ::cast impl request state)

    [:EXIT parent reason]
    (do-terminate impl reason state)

    _
    (cast-or-info ::info impl message state)))

(defn- enter-loop [impl parent state timeout]
  (loop [state state
         timeout timeout]
    (let [message (if (or (nil? timeout) (= timeout :infinity))
                    (otp-proc/receive!
                      message message)
                    (otp-proc/receive!
                      message message
                      (after timeout :timeout)))]
      (cm/match (dispatch impl parent state message)
        :recur (recur state timeout)
        [:recur new-state new-timeout] (recur new-state new-timeout)
        ;; Exit with :normal reason explicitly
        [:terminate :normal _new-state] (otp-proc/exit :normal)
        [:terminate reason _new-state] (otp-proc/exit reason)))))

(defn- gen-server-proc [impl init-args parent response]
  (cm/match (otp-proc/ex-catch
              [:ok (otp-proc/await?! (init impl init-args))])
    [:ok [:ok initial-state]]
    (do
      (deliver response :ok)
      (enter-loop impl parent initial-state :infinity))

    [:ok [:ok initial-state timeout]]
    (do
      (deliver response :ok)
      (enter-loop impl parent initial-state timeout))

    [:ok [:stop reason]]
    (do
      (deliver response [:error reason])
      (otp-proc/exit reason))

    [:ok other]
    (let [reason [:bad-return-value 'init other]]
      (deliver response [:error reason])
      (otp-proc/exit reason))

    [:EXIT reason]
    (do
      (deliver response [:error reason])
      (otp-proc/exit reason))))

;; =============================================================================
;; Start functions - return [:ok pid] tuples like otplike
;; =============================================================================

(defn- convert-spawn-opt
  "Convert otplike-style spawn-opt to loom-otp format.
   otplike: {:flags {:trap-exit true}, :link true, :register :name}
   loom-otp: {:trap-exit true, :link true, :reg-name :name}"
  [spawn-opt]
  (cond-> {}
    (get-in spawn-opt [:flags :trap-exit]) (assoc :trap-exit true)
    (:link spawn-opt) (assoc :link true)
    (:register spawn-opt) (assoc :reg-name (:register spawn-opt))
    (:reg-name spawn-opt) (assoc :reg-name (:reg-name spawn-opt))))

(defn- start*-impl [gs args spawn-opt response parent timeout]
  (let [loom-spawn-opt (convert-spawn-opt spawn-opt)
        exit-or-pid
        (try
          (proc/spawn-opt 
            loom-spawn-opt  ;; Convert to loom-otp format
            (fn [] (gen-server-proc gs args parent response)))
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (if (= :already-registered (:type data))
                [:error [:already-registered (:existing-pid data)]]
                (throw e)))))]
    (cm/match exit-or-pid
      [:error reason]
      [:error reason]

      pid
      (let [timeout-ms (if (= timeout :infinity) nil timeout)
            result (if timeout-ms
                     (deref response timeout-ms :timeout)
                     @response)]
        (cm/match result
          :ok [:ok pid]
          :timeout (do
                     (otp-proc/unlink pid)
                     (otp-proc/exit pid :kill)
                     [:error :timeout])
          [:error reason] [:error reason])))))

(defn- validate-timeout!
  "Validate timeout parameter. Throws if invalid."
  [timeout]
  (when-not (or (= :infinity timeout)
                (and (integer? timeout) (pos? timeout)))
    (throw (IllegalArgumentException.
             (str "timeout must be :infinity or positive integer, got: " timeout)))))

(defn- try-self
  "Try to get self pid, return nil if not in a process context."
  []
  (try
    (otp-proc/self)
    (catch clojure.lang.ExceptionInfo _
      nil)))

(defn- start*
  [server args {:keys [timeout spawn-opt] :or {timeout :infinity spawn-opt {}}}]
  (validate-timeout! timeout)
  ;; Get parent before async to avoid capturing wrong thread context
  (let [parent (try-self)]
    (otp-proc/async
      (let [gs (->gen-server server)
            response (promise)]
        ;; Check for already-registered first
        (if-let [reg-name (:reg-name spawn-opt)]
          (if-let [existing-pid (reg/whereis reg-name)]
            [:error [:already-registered existing-pid]]
            ;; Name not registered, proceed with spawn
            (start*-impl gs args spawn-opt response parent timeout))
          ;; No reg-name, proceed with spawn
          (start*-impl gs args spawn-opt response parent timeout))))))

(defn start
  "Start gen-server. Returns async value."
  ([server]
   (start server []))
  ([server args]
   (start server args {}))
  ([server args options]
   (start* server args options))
  ([reg-name server args options]
   (start server args (assoc-in options [:spawn-opt :reg-name] reg-name))))

(defmacro start!
  "Start gen-server synchronously. Returns [:ok pid] or [:error reason]."
  ([server]
   `(start! ~server [] {}))
  ([server args]
   `(start! ~server ~args {}))
  ([server args options]
   `(otp-proc/await! (start ~server ~args ~options)))
  ([reg-name server args options]
   `(start! ~server ~args (assoc-in ~options [:spawn-opt :reg-name] ~reg-name))))

(defn start-link
  "Start gen-server linked to caller. Returns async value."
  ([server]
   (start-link server [] {}))
  ([server args]
   (start-link server args {}))
  ([server args options]
   (start server args (assoc-in options [:spawn-opt :link] true)))
  ([reg-name server args options]
   (start-link server args (assoc-in options [:spawn-opt :reg-name] reg-name))))

(defmacro start-link!
  "Start gen-server linked to caller. Returns [:ok pid] or [:error reason]."
  ([server]
   `(start-link! ~server [] {}))
  ([server args]
   `(start-link! ~server ~args {}))
  ([server args options]
   `(start! ~server ~args (assoc-in ~options [:spawn-opt :link] true)))
  ([reg-name server args options]
   `(start-link! ~server ~args (assoc-in ~options [:spawn-opt :reg-name] ~reg-name))))

;; Namespace-based start macros
(defmacro start-ns
  "Start gen-server using current namespace as implementation. Returns async."
  ([]
   `(start-ns [] {}))
  ([args]
   `(start-ns ~args {}))
  ([args options]
   `(start ~*ns* ~args ~options))
  ([reg-name args options]
   `(start ~reg-name ~*ns* ~args ~options)))

(defmacro start-ns!
  "Start gen-server using current namespace as implementation."
  ([]
   `(start-ns! [] {}))
  ([args]
   `(start-ns! ~args {}))
  ([args options]
   `(start! ~*ns* ~args ~options))
  ([reg-name args options]
   `(start! ~reg-name ~*ns* ~args ~options)))

(defmacro start-link-ns
  "Start linked gen-server using current namespace. Returns async."
  ([]
   `(start-link-ns [] {}))
  ([args]
   `(start-link-ns ~args {}))
  ([args options]
   `(start-link ~*ns* ~args ~options))
  ([reg-name args options]
   `(start-link ~reg-name ~*ns* ~args ~options)))

(defmacro start-link-ns!
  "Start linked gen-server using current namespace."
  ([]
   `(start-link-ns! [] {}))
  ([args]
   `(start-link-ns! ~args {}))
  ([args options]
   `(start-link! ~*ns* ~args ~options))
  ([reg-name args options]
   `(start-link! ~reg-name ~*ns* ~args ~options)))

;; =============================================================================
;; Call/Cast
;; =============================================================================

(defn ^:no-doc call* [server request timeout-ms call-args]
  (otp-proc/async
    (let [mref (otp-proc/monitor server)]
      (otp-proc/! server [::call [mref (otp-proc/self)] request])
      (otp-proc/selective-receive!
        [mref resp]
        (do
          (otp-proc/demonitor mref {:flush true})
          resp)

        [:DOWN mref _ _ reason]
        (otp-proc/exit [reason [`call call-args]])

        (after timeout-ms
          (otp-proc/demonitor mref {:flush true})
          (otp-proc/exit [:timeout [`call call-args]]))))))

(defmacro call!
  "Make synchronous call to gen-server."
  ([server request]
   `(let [server# ~server
          request# ~request]
      (otp-proc/await! (call* server# request# 5000 [server# request#]))))
  ([server request timeout-ms]
   `(let [server# ~server
          request# ~request
          timeout-ms# ~timeout-ms]
      (otp-proc/await!
        (call* server# request# timeout-ms# [server# request# timeout-ms#])))))

(defn call
  "Make synchronous call to gen-server. Returns async value."
  ([server request]
   (call* server request 5000 [server request]))
  ([server request timeout-ms]
   (call* server request timeout-ms [server request timeout-ms])))

(defn cast
  "Send asynchronous request to gen-server."
  [server request]
  (otp-proc/! server [::cast request]))

(defmacro get!
  "Get the current state of a gen-server (for debugging)."
  [server]
  `(call! ~server ::get-state))
