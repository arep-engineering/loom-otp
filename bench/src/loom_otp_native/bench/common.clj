(ns loom-otp.bench.common
  "Shared benchmark utilities for timing, memory measurement, and output.")

;; =============================================================================
;; Memory Measurement
;; =============================================================================

(defn used-memory-bytes
  "Current used heap memory in bytes."
  []
  (let [rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn used-memory-mb
  "Current used heap memory in megabytes."
  []
  (/ (used-memory-bytes) 1024.0 1024.0))

(defn force-gc!
  "Force garbage collection and wait for it to settle.
   Runs GC twice with sleep to allow finalization."
  []
  (System/gc)
  (Thread/sleep 100)
  (System/gc)
  (Thread/sleep 100))

(defn measure-memory-after-gc
  "Force GC and return used memory in MB."
  []
  (force-gc!)
  (used-memory-mb))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn println-result
  "Print a benchmark result with consistent formatting."
  [label value unit]
  (printf "%-50s %15.2f %s%n" label (double value) unit)
  (flush))

(defn println-header
  "Print a section header."
  [title]
  (println)
  (println (str "=== " title " ==="))
  (flush))

(defn println-subheader
  "Print a subsection header."
  [title]
  (println)
  (println (str "--- " title " ---"))
  (flush))

;; =============================================================================
;; Timing
;; =============================================================================

(defmacro timed
  "Execute body and return {:result <result> :time-ms <elapsed-ms>}."
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         elapsed# (/ (- (System/nanoTime) start#) 1e6)]
     {:result result# :time-ms elapsed#}))

(defmacro time-ms
  "Execute body and return elapsed time in milliseconds."
  [& body]
  `(:time-ms (timed ~@body)))

;; =============================================================================
;; Benchmark Scales
;; =============================================================================

(def SPAWN-SCALES [1000 10000 100000])
(def SPAWN-SCALES-SMALL [100 1000 10000])
(def TREE-DEPTHS [10 15 18])
(def MEMORY-SCALES [1000 10000 50000])
(def MESSAGE-SCALES [1000 10000 100000 1000000])

;; =============================================================================
;; System Info
;; =============================================================================

(defn- get-gc-name
  "Get the name of the primary garbage collector."
  []
  (let [gc-beans (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans)]
    (if (seq gc-beans)
      (->> gc-beans
           (map #(.getName %))
           (filter #(or (.contains % "G1")
                        (.contains % "ZGC")
                        (.contains % "Shenandoah")
                        (.contains % "Parallel")
                        (.contains % "Serial")))
           first)
      "Unknown")))

(defn print-system-info
  "Print JVM and system information."
  [name]
  (println "========================================")
  (println (str name " benchmarks"))
  (println (str "JVM: " (System/getProperty "java.version")))
  (println (str "GC: " (get-gc-name)))
  (println (str "Heap max: " (/ (.maxMemory (Runtime/getRuntime)) 1024 1024) " MB"))
  (println (str "Available processors: " (.availableProcessors (Runtime/getRuntime))))
  (println (str "OS: " (System/getProperty "os.name") " " (System/getProperty "os.arch")))
  (println "========================================")
  (flush))

;; =============================================================================
;; Warm-up
;; =============================================================================

(def WARM-UP-ITERATIONS 6)

(defn warm-up!
  "Run function n times to warm up JIT compiler, then force GC."
  ([f] (warm-up! WARM-UP-ITERATIONS f))
  ([n f]
   (println (str "Warming up (" n " iterations)..."))
   (flush)
   (dotimes [_ n] (f))
   (force-gc!)
   (println "Warm-up complete.")
   (flush)))
