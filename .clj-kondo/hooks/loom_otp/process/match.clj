(ns hooks.loom-otp.process.match
  "clj-kondo hooks for loom-otp.process.match/receive! and selective-receive!.

   These macros have the syntax:
     (receive!
       pattern1 body1
       pattern2 body2
       (after timeout-ms timeout-body...))

   They expand internally to clojure.core.match/match but the first argument
   (the value to match against) is implicit (the received message). So we
   can't use lint-as to map to clojure.core.match/match (different arity),
   and hooks are mutually exclusive with lint-as.

   We replicate the binding extraction logic from clj-kondo's built-in
   match analyzer (clj-kondo.impl.analyzer.match) to walk each pattern,
   collect bound symbols, and emit (let [sym1 (new Object) ...] body)
   for each clause so clj-kondo sees the bindings. We use (new Object)
   rather than nil so clj-kondo doesn't infer a nil type on the bindings,
   which would cause false type-mismatch errors when pattern-bound
   variables are used in arithmetic or other typed operations."
  (:require [clj-kondo.hooks-api :as api]))

;; ---------------------------------------------------------------------------
;; Opaque init value for generated let-bindings
;;
;; We use (new Object) so clj-kondo sees an unknown type rather than nil,
;; avoiding false type-mismatch errors like "Expected: number, received: nil"
;; when pattern-bound variables are used in arithmetic.
;; ---------------------------------------------------------------------------

(defn- opaque-value-node
  "Create a (new Object) node — an expression whose type clj-kondo cannot infer."
  []
  (api/list-node [(api/token-node 'new) (api/token-node 'Object)]))

;; ---------------------------------------------------------------------------
;; Pattern binding extraction
;;
;; Replicates clj-kondo.impl.analyzer.match logic:
;; - Tokens: symbol that isn't _ -> binding; keywords/numbers/strings/nil -> no binding
;; - Vectors: recurse into children, handling & rest, :when/:guard flattened syntax
;; - Lists: handle (:or p1 p2), (p :when pred), (p :<< fn), (:seq), recurse otherwise
;; - Maps: recurse into values (keys are literal match targets)
;; ---------------------------------------------------------------------------

(defn- symbol-node?
  "True if node is a token holding a symbol."
  [node]
  (and (api/token-node? node)
       (symbol? (api/sexpr node))))

(defn- keyword-node?*
  "True if node is a keyword token."
  [node]
  (api/keyword-node? node))

(defn- binding-symbol?
  "True if node is a symbol token that should be treated as a pattern binding.
   Excludes _ (wildcard) and & (rest marker)."
  [node]
  (and (symbol-node? node)
       (let [s (api/sexpr node)]
         (and (not= '_ s)
              (not= '& s)))))

(defn- literal-node?
  "True if node is a literal (keyword, number, string, nil, boolean) — not a binding."
  [node]
  (and (api/token-node? node)
       (let [v (api/sexpr node)]
         (or (keyword? v)
             (number? v)
             (string? v)
             (nil? v)
             (true? v)
             (false? v)))))

(declare extract-bindings)

(defn- extract-from-vector
  "Extract bindings from a vector pattern like [a b c] or [a & rest].
   Also handles flattened :when/:guard syntax: [_ _ :when even? _ _]."
  [node]
  (loop [children (seq (:children node))
         bindings []]
    (if-not children
      bindings
      (let [[child maybe-op & rchildren] children]
        (if (and (keyword-node?* maybe-op)
                 (let [k (api/sexpr maybe-op)]
                   (or (= :when k) (= :guard k))))
          ;; Flattened :when/:guard — the child is a pattern, skip the op and predicate
          (recur (rest rchildren)
                 (into bindings (extract-bindings child)))
          (recur (next children)
                 (into bindings (extract-bindings child))))))))

(defn- extract-from-list
  "Extract bindings from a list pattern.
   Handles:
   - (pattern :when pred) / (pattern :guard pred) / (pattern :<< fn) — extract from first child only
   - (:or p1 p2 ...) — extract from all alternatives
   - (p1 p2 ... :seq) — extract from children (seq matching)
   - other lists — recurse into all children"
  [node]
  (let [children (:children node)
        snd (second children)]
    (if (and (keyword-node?* snd)
             (let [k (api/sexpr snd)]
               (or (= :<< k) (= :when k) (= :guard k))))
      ;; (pattern :when pred) or (pattern :<< fn) — only the first child contributes bindings
      (extract-bindings (first children))
      ;; Check for :or
      (let [fst (first children)]
        (if (and (keyword-node?* fst)
                 (= :or (api/sexpr fst)))
          ;; (:or p1 p2 ...) — all alternatives, take union of bindings
          (mapcat extract-bindings (rest children))
          ;; Check for trailing :seq — (p1 p2 ... :seq)
          (let [lst (last children)
                items (if (and (keyword-node?* lst)
                               (= :seq (api/sexpr lst)))
                        (butlast children)
                        children)]
            (mapcat extract-bindings items)))))))

(defn- extract-from-map
  "Extract bindings from a map pattern like {:a b :c d}.
   Keys are literal match targets, values are patterns."
  [node]
  (let [children (:children node)
        pairs (partition 2 children)]
    (mapcat (fn [[_k v]] (extract-bindings v)) pairs)))

(defn extract-bindings
  "Walk a core.match pattern node and return a seq of symbol nodes that are bindings."
  [node]
  (let [tag (api/tag node)]
    (case tag
      :token
      (cond
        (binding-symbol? node) [node]
        :else [])

      :vector
      (extract-from-vector node)

      :list
      (extract-from-list node)

      :map
      (extract-from-map node)

      ;; sets, other — no bindings
      [])))

;; ---------------------------------------------------------------------------
;; Clause parsing and transformation
;; ---------------------------------------------------------------------------

(defn- after-clause?
  "True if node is an (after ...) list form."
  [node]
  (and (api/list-node? node)
       (let [children (:children node)]
         (when-let [head (first children)]
           (and (api/token-node? head)
                (let [v (api/sexpr head)]
                  (= 'after v)))))))

(defn- parse-clauses
  "Parse receive! arguments into {:match-clauses [...] :after-clause <node-or-nil>}.
   The after clause is the last form if it looks like (after ...)."
  [args]
  (let [last-arg (last args)]
    (if (after-clause? last-arg)
      {:match-clauses (butlast args)
       :after-clause last-arg}
      {:match-clauses args
       :after-clause nil})))

(defn- clause-pair->let
  "Transform a pattern/body pair into (let [binding1 (new Object) ...] body).
   If the pattern has no bindings, just returns the body node."
  [pattern body]
  (let [binding-nodes (extract-bindings pattern)
         let-bindings (mapcat (fn [sym-node] [sym-node (opaque-value-node)])
                             binding-nodes)]
    (if (seq let-bindings)
      (api/list-node
       [(api/token-node 'let)
        (api/vector-node (vec let-bindings))
        body])
      body)))

(defn- build-transformed-node
  "Build the full transformed node from parsed clauses.

   For each pattern/body pair, emits (let [bindings...] body).
   For the after clause, emits (do timeout-ms timeout-body...).
   Everything is wrapped in a (do ...) form."
  [match-clauses after-clause meta-info]
  (let [pairs (partition 2 match-clauses)
        let-forms (map (fn [[pattern body]]
                         (clause-pair->let pattern body))
                       pairs)
        after-nodes (when after-clause
                      (let [children (:children after-clause)]
                        ;; children = [after timeout-ms body1 body2 ...]
                        (rest children)))  ;; [timeout-ms body1 body2 ...]
        all-nodes (concat let-forms after-nodes)]
    (with-meta
      (api/list-node
       (list* (api/token-node 'do) all-nodes))
      meta-info)))

(defn receive!
  "Hook for loom-otp.process.match/receive!

   Transforms:
     (receive!
       [:hello name] (println name)
       [:add a b]    (+ a b)
       (after 1000 :timeout))

   Into:
     (do
       (let [name (new Object)] (println name))
       (let [a (new Object) b (new Object)] (+ a b))
       1000
       :timeout)"
  [{:keys [node]}]
  (let [args (rest (:children node))
        {:keys [match-clauses after-clause]} (parse-clauses args)
        new-node (build-transformed-node match-clauses after-clause (meta node))]
    {:node new-node}))

(defn selective-receive!
  "Hook for loom-otp.process.match/selective-receive!

   Same clause syntax as receive!, so same transformation applies."
  [{:keys [node]}]
  (receive! {:node node}))
